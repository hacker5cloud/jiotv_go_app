package com.skylake.skytv.jgorunner.core.update

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import com.skylake.skytv.jgorunner.core.execution.BinaryExecutor
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.utils.LogCollector
import com.skylake.skytv.jgorunner.utils.Tls12SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

object BinaryUpdater {
    private const val TAG = "JTVGo::BinaryUpdater"
    private const val BINARY_RELEASE_URL = "https://api.github.com/repos/JioTV-Go/jiotv_go/releases/latest"

    /**
     * Detects the device CPU ABI safely on Android 4.4 KitKat (API 19) using Build.CPU_ABI / CPU_ABI2.
     */
    @Suppress("DEPRECATION")
    fun getDeviceAbiList(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.toList()
        } else {
            listOfNotNull(Build.CPU_ABI, Build.CPU_ABI2).filter { it.isNotBlank() }
        }
    }

    fun getTargetArchSuffix(): String {
        val abis = getDeviceAbiList()
        val primary = abis.firstOrNull()?.lowercase() ?: "armeabi-v7a"
        return when {
            primary.contains("arm64") || primary.contains("aarch64") -> "arm64"
            primary.contains("armeabi") || primary.contains("armv7") || primary.contains("arm") -> "armv7"
            primary.contains("x86_64") || primary.contains("amd64") -> "amd64"
            primary.contains("x86") || primary.contains("i686") || primary.contains("i386") -> "386"
            else -> "armv7"
        }
    }

    /**
     * Returns the ordered list of upstream GitHub release asset names compatible with this device.
     */
    fun getCompatibleAssetNamesForDevice(): List<String> {
        val arch = getTargetArchSuffix()
        val isKitKatOrOlder = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP

        return when (arch) {
            "armv7" -> if (isKitKatOrOlder) {
                listOf(
                    "jiotv_go-linux-arm",
                    "jiotv_go-android5-armv7"
                )
            } else {
                listOf(
                    "jiotv_go-android5-armv7",
                    "jiotv_go-linux-arm",
                    "jiotv_go-android-arm"
                )
            }
            "arm64" -> if (isKitKatOrOlder) {
                listOf(
                    "jiotv_go-linux-arm64",
                    "jiotv_go-linux-arm",
                    "jiotv_go-android5-armv7"
                )
            } else {
                listOf(
                    "jiotv_go-android-arm64",
                    "jiotv_go-linux-arm64",
                    "jiotv_go-android5-armv7"
                )
            }
            "386" -> if (isKitKatOrOlder) {
                listOf(
                    "jiotv_go-linux-386",
                    "jiotv_go-linux-arm"
                )
            } else {
                listOf(
                    "jiotv_go-linux-386",
                    "jiotv_go-android-386"
                )
            }
            "amd64" -> if (isKitKatOrOlder) {
                listOf(
                    "jiotv_go-linux-amd64",
                    "jiotv_go-linux-386"
                )
            } else {
                listOf(
                    "jiotv_go-android-amd64",
                    "jiotv_go-linux-amd64"
                )
            }
            else -> listOf("jiotv_go-linux-arm", "jiotv_go-android5-armv7")
        }
    }

    fun getTargetBinaryAssetName(): String {
        return getCompatibleAssetNamesForDevice().first()
    }

    /**
     * Ensures the jiotv_go binary exists in `context.filesDir`, passes ELF validation,
     * and is executable. If already present and valid on disk, returns immediately without network delay.
     */
    suspend fun ensureBinaryReady(
        context: Context,
        forceUpdate: Boolean = false,
        onProgress: (String, Int) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val prefs = SkySharedPref.getInstance(context).myPreferences
            val targetName = getTargetBinaryAssetName()
            val expectedFile = File(context.filesDir, targetName)
            val savedName = prefs.jtvGoBinaryName
            val savedFile = if (!savedName.isNullOrBlank()) File(context.filesDir, savedName) else expectedFile

            if (!forceUpdate && savedFile.exists() && savedFile.length() > 100_000L) {
                val elfErr = validateElfBinaryFile(savedFile)
                if (elfErr == null) {
                    makeBinaryExecutable(savedFile)
                    if (prefs.jtvGoBinaryName.isNullOrBlank()) {
                        prefs.jtvGoBinaryName = savedFile.name
                    }
                    LogCollector.log("[BinaryUpdater] Using existing binary: ${savedFile.absolutePath} (${savedFile.length()} bytes)")
                    return@withContext Result.success(savedFile)
                } else {
                    LogCollector.log("[BinaryUpdater] Cached binary ${savedFile.name} failed ELF check ($elfErr); replacing...")
                    savedFile.delete()
                }
            }

            if (!forceUpdate && expectedFile.exists() && expectedFile.length() > 100_000L) {
                val elfErr = validateElfBinaryFile(expectedFile)
                if (elfErr == null) {
                    makeBinaryExecutable(expectedFile)
                    prefs.jtvGoBinaryName = expectedFile.name
                    LogCollector.log("[BinaryUpdater] Using expected binary: ${expectedFile.absolutePath} (${expectedFile.length()} bytes)")
                    return@withContext Result.success(expectedFile)
                } else {
                    LogCollector.log("[BinaryUpdater] Expected binary ${expectedFile.name} failed ELF check ($elfErr); replacing...")
                    expectedFile.delete()
                }
            }

            val compatibleAssetNames = getCompatibleAssetNamesForDevice()
            onProgress("Checking latest JioTV Go binary (${compatibleAssetNames.first()})...", 5)
            LogCollector.log("[BinaryUpdater] Starting download check for compatible assets: $compatibleAssetNames")

            var chosenAssetName = compatibleAssetNames.first()
            var apiDownloadUrl: String? = null
            var releaseTag = "latest"

            try {
                val req = Request.Builder()
                    .url(BINARY_RELEASE_URL)
                    .header("User-Agent", "JioTVGo-KitKat/1.0")
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                Tls12SocketFactory.downloadHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bodyStr = resp.body()?.string()
                        if (!bodyStr.isNullOrBlank()) {
                            val json = JSONObject(bodyStr)
                            releaseTag = json.optString("tag_name", "latest")
                            val assets = json.optJSONArray("assets")
                            if (assets != null) {
                                outer@ for (candidateName in compatibleAssetNames) {
                                    for (i in 0 until assets.length()) {
                                        val asset = assets.getJSONObject(i)
                                        if (asset.optString("name").equals(candidateName, ignoreCase = true)) {
                                            chosenAssetName = asset.optString("name")
                                            apiDownloadUrl = asset.optString("browser_download_url")
                                            break@outer
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        LogCollector.log("[BinaryUpdater] GitHub API HTTP ${resp.code()}")
                    }
                }
            } catch (e: Exception) {
                LogCollector.log("[BinaryUpdater] GitHub API lookup failed (${e.javaClass.simpleName}: ${e.message}), using direct/mirror URLs")
            }

            val candidateUrls = linkedMapOf<String, String>()
            if (!apiDownloadUrl.isNullOrBlank()) {
                candidateUrls[apiDownloadUrl!!] = chosenAssetName
            }
            for (assetName in compatibleAssetNames) {
                val directUrl = "https://github.com/JioTV-Go/jiotv_go/releases/latest/download/$assetName"
                candidateUrls[directUrl] = assetName
                candidateUrls["https://ghproxy.net/$directUrl"] = assetName
                candidateUrls["https://mirror.ghproxy.com/$directUrl"] = assetName
            }

            val tempFile = File(context.filesDir, "$targetName.tmp")
            var lastErrorMsg = "Unknown download error"
            var downloadedSuccess = false
            var finalAssetName = targetName

            var attemptIdx = 0
            for ((candidateUrl, assetName) in candidateUrls) {
                attemptIdx++
                if (tempFile.exists()) tempFile.delete()
                val sourceLabel = if (attemptIdx == 1) assetName else "$assetName (Try #$attemptIdx)"
                onProgress("Downloading $sourceLabel ($releaseTag)...", 10)
                LogCollector.log("[BinaryUpdater] Download attempt #$attemptIdx: $candidateUrl")

                val result = DownloadAsset.downloadFile(candidateUrl, tempFile) { prog ->
                    onProgress("Downloading $sourceLabel... ${prog.progress}%", prog.progress)
                }

                if (result.status == DownloadStatus.SUCCESS && tempFile.exists() && tempFile.length() > 100_000L) {
                    val elfError = validateElfBinaryFile(tempFile)
                    if (elfError == null) {
                        downloadedSuccess = true
                        finalAssetName = assetName
                        break
                    } else {
                        lastErrorMsg = elfError
                    }
                } else {
                    lastErrorMsg = result.message
                }
                LogCollector.log("[BinaryUpdater] Attempt #$attemptIdx failed: $lastErrorMsg")
            }

            if (!downloadedSuccess || !tempFile.exists() || tempFile.length() < 100_000L) {
                return@withContext Result.failure(
                    Exception(
                        "Binary download failed ($lastErrorMsg). Tip: Use '📂 Import Binary' to select " +
                            "${compatibleAssetNames.joinToString(" or ")} from File Manager / USB."
                    )
                )
            }

            // Stop any running binary process before replacing the executable file
            BinaryExecutor.stopExecution(markUserStopped = false)

            val finalDestFile = File(context.filesDir, finalAssetName)
            if (finalDestFile.exists()) finalDestFile.delete()
            if (!tempFile.renameTo(finalDestFile)) {
                tempFile.copyTo(finalDestFile, overwrite = true)
                tempFile.delete()
            }

            makeBinaryExecutable(finalDestFile)

            prefs.jtvGoBinaryName = finalDestFile.name
            prefs.jtvGoBinaryVersion = releaseTag
            LogCollector.log("[BinaryUpdater] Downloaded & installed binary: ${finalDestFile.absolutePath} (${finalDestFile.length()} bytes)")
            onProgress("Binary ready (${finalDestFile.name} • $releaseTag)", 100)
            Result.success(finalDestFile)
        } catch (e: Exception) {
            LogCollector.logError("[BinaryUpdater] Failed to prepare binary", e)
            Result.failure(e)
        }
    }

    /**
     * Imports a `jiotv_go` binary from an Android `content://` or `file://` URI (selected via an external File Manager).
     */
    suspend fun importBinaryFromUri(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            LogCollector.log("[BinaryUpdater] Importing binary from URI: $uri")
            onProgress("Reading selected file...")
            val scheme = uri.scheme?.lowercase()
            if (scheme == "file") {
                val path = uri.path
                if (!path.isNullOrBlank()) {
                    return@withContext importBinaryFromFile(context, File(path), onProgress)
                }
            }

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(Exception("Cannot open selected URI: $uri"))

            inputStream.use { stream ->
                installBinaryFromStream(context, stream, uri.lastPathSegment ?: "imported_binary", onProgress)
            }
        } catch (e: Exception) {
            LogCollector.logError("[BinaryUpdater] Failed to import binary from URI: $uri", e)
            Result.failure(e)
        }
    }

    /**
     * Imports a `jiotv_go` binary directly from a local `File` on Internal Storage, Downloads, or USB drive.
     */
    suspend fun importBinaryFromFile(
        context: Context,
        sourceFile: File,
        onProgress: (String) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            LogCollector.log("[BinaryUpdater] Importing binary from file: ${sourceFile.absolutePath} (exists=${sourceFile.exists()}, size=${if (sourceFile.exists()) sourceFile.length() else -1})")
            if (!sourceFile.exists() || !sourceFile.isFile) {
                return@withContext Result.failure(Exception("File does not exist: ${sourceFile.absolutePath}"))
            }
            if (!sourceFile.canRead()) {
                return@withContext Result.failure(Exception("Cannot read file (check storage permissions): ${sourceFile.name}"))
            }
            onProgress("Importing ${sourceFile.name}...")
            FileInputStream(sourceFile).use { stream ->
                installBinaryFromStream(context, stream, sourceFile.name, onProgress)
            }
        } catch (e: Exception) {
            LogCollector.logError("[BinaryUpdater] Failed to import binary from file: ${sourceFile.absolutePath}", e)
            Result.failure(e)
        }
    }

    private fun installBinaryFromStream(
        context: Context,
        rawStream: InputStream,
        sourceLabel: String,
        onProgress: (String) -> Unit
    ): Result<File> {
        // Stop any running binary process BEFORE modifying files in filesDir to prevent ETXTBSY ("Text file busy")
        BinaryExecutor.stopExecution(markUserStopped = false)

        val cleanBaseName = sourceLabel
            .substringAfterLast('/')
            .substringAfterLast(':')
            .removeSuffix(".zip")
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .takeIf { it.isNotBlank() && it.contains("jiotv", ignoreCase = true) }
            ?: getTargetBinaryAssetName()

        val tempFile = File(context.filesDir, "$cleanBaseName.import.tmp")
        if (tempFile.exists()) tempFile.delete()

        val buffered = BufferedInputStream(rawStream, 32 * 1024)
        buffered.mark(8)
        val header = ByteArray(4)
        val headerRead = buffered.read(header)
        buffered.reset()

        if (headerRead < 4) {
            LogCollector.log("[BinaryUpdater] Selected file '$sourceLabel' is empty or < 4 bytes")
            return Result.failure(Exception("Selected file '$sourceLabel' is empty or too small."))
        }

        val isZip = header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() &&
            header[3] == 0x04.toByte()

        var chosenEntryName = cleanBaseName

        if (isZip) {
            onProgress("Extracting binary from ZIP archive...")
            LogCollector.log("[BinaryUpdater] Archive '$sourceLabel' detected as ZIP; scanning entries...")
            var extracted = false
            val expectedArch = getTargetArchSuffix()
            ZipInputStream(buffered).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory &&
                        (entryName.contains("jiotv_go", ignoreCase = true) ||
                            entryName.contains(expectedArch, ignoreCase = true) ||
                            entry.size > 1_000_000L ||
                            entry.size == -1L)
                    ) {
                        FileOutputStream(tempFile).use { out ->
                            val buf = ByteArray(32 * 1024)
                            var r: Int
                            while (zis.read(buf).also { r = it } != -1) {
                                out.write(buf, 0, r)
                            }
                            out.flush()
                        }
                        if (tempFile.length() > 100_000L && validateElfBinaryFile(tempFile) == null) {
                            chosenEntryName = entryName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                .takeIf { it.isNotBlank() } ?: cleanBaseName
                            extracted = true
                            break
                        } else {
                            tempFile.delete()
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (!extracted) {
                val msg = "No valid ELF binary found inside ZIP '$sourceLabel'."
                LogCollector.log("[BinaryUpdater] $msg")
                return Result.failure(Exception(msg))
            }
        } else {
            onProgress("Copying $sourceLabel to internal storage...")
            FileOutputStream(tempFile).use { out ->
                val buf = ByteArray(32 * 1024)
                var r: Int
                while (buffered.read(buf).also { r = it } != -1) {
                    out.write(buf, 0, r)
                }
                out.flush()
            }
        }

        if (!tempFile.exists() || tempFile.length() < 100_000L) {
            val len = if (tempFile.exists()) tempFile.length() else 0L
            tempFile.delete()
            val msg = "Invalid binary size (${len / 1024} KB). Expected a JioTV Go binary (> 1 MB)."
            LogCollector.log("[BinaryUpdater] $msg")
            return Result.failure(Exception(msg))
        }

        val elfError = validateElfBinaryFile(tempFile)
        if (elfError != null) {
            tempFile.delete()
            LogCollector.log("[BinaryUpdater] Import ELF validation rejected '$sourceLabel': $elfError")
            return Result.failure(Exception(elfError))
        }

        val destFile = File(context.filesDir, chosenEntryName)
        try {
            if (destFile.exists()) destFile.delete()
        } catch (_: Exception) {
        }
        if (!tempFile.renameTo(destFile)) {
            tempFile.copyTo(destFile, overwrite = true)
            tempFile.delete()
        }

        makeBinaryExecutable(destFile)

        val prefs = SkySharedPref.getInstance(context).myPreferences
        prefs.jtvGoBinaryName = destFile.name
        prefs.jtvGoBinaryVersion = "Imported (${sourceLabel.take(24)})"

        LogCollector.log(
            "[BinaryUpdater] Imported binary ready: path=${destFile.absolutePath}, size=${destFile.length()}, " +
                "canRead=${destFile.canRead()}, canExecute=${destFile.canExecute()}"
        )
        onProgress("Imported ${destFile.name} (${destFile.length() / (1024 * 1024)} MB)")
        return Result.success(destFile)
    }

    /**
     * Validates that a file is a Linux/Android ELF executable (`\x7FELF`), checks CPU architecture compatibility,
     * and logs detailed ELF header diagnostics (static vs dynamic, PT_INTERP, NDK symbols) to `LogCollector`.
     */
    fun validateElfBinaryFile(file: File): String? {
        return try {
            if (!file.exists() || file.length() < 52L) {
                return "File is too small to be a valid executable binary."
            }
            val header = ByteArray(64)
            FileInputStream(file).use { fis ->
                if (fis.read(header) < 52) {
                    return "Could not read binary header."
                }
            }
            // Check ELF magic: 0x7F 'E' 'L' 'F'
            if (header[0] != 0x7F.toByte() ||
                header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() ||
                header[3] != 'F'.code.toByte()
            ) {
                val recommended = getCompatibleAssetNamesForDevice().joinToString(" or ")
                return "Selected file is not a valid Linux/Android ELF binary (missing \\x7FELF header). Make sure you select $recommended (not .exe or .apk)."
            }

            val eiClass = header[4].toInt() and 0xFF // 1 = 32-bit, 2 = 64-bit
            val isLittleEndian = header[5].toInt() == 1
            val eType = readUInt16(header, 16, isLittleEndian) // 2 = ET_EXEC, 3 = ET_DYN (PIE)
            val eMachine = readUInt16(header, 18, isLittleEndian)

            val binaryArch = when (eMachine) {
                0x28 -> "armv7"   // EM_ARM (32-bit ARM)
                0xB7 -> "arm64"   // EM_AARCH64 (64-bit ARM)
                0x03 -> "386"     // EM_386 (32-bit x86)
                0x3E -> "amd64"   // EM_X86_64 (64-bit x86_64)
                else -> "unknown($eMachine)"
            }

            val interp = extractElfInterpreter(file, header, eiClass, isLittleEndian)
            val hasAtfork = fileContainsSymbolFast(file, "__register_atfork")
            val libc32 = File("/system/lib/libc.so")
            val systemHasAtfork = if (libc32.exists()) fileContainsSymbolFast(libc32, "__register_atfork") else true

            LogCollector.log(
                "[ELF-Inspect] file=${file.name}, size=${file.length()}, class=${if (eiClass == 1) "32-bit" else "64-bit"}, " +
                    "e_type=$eType (${if (eType == 2) "ET_EXEC" else if (eType == 3) "ET_DYN/PIE" else "other"}), " +
                    "arch=$binaryArch, PT_INTERP=${interp ?: "NONE (100% STATIC)"}, " +
                    "binaryNeeds__register_atfork=$hasAtfork, systemLibcHas__register_atfork=$systemHasAtfork"
            )

            val deviceAbis = getDeviceAbiList().joinToString(",").lowercase()
            val recommended = getCompatibleAssetNamesForDevice().joinToString(" or ")

            val compatible = when (binaryArch) {
                "armv7" -> deviceAbis.contains("arm")
                "arm64" -> deviceAbis.contains("arm64") || deviceAbis.contains("aarch64")
                "386" -> deviceAbis.contains("x86") || deviceAbis.contains("i686")
                "amd64" -> deviceAbis.contains("x86_64") || deviceAbis.contains("amd64")
                else -> true
            }

            if (!compatible) {
                return "CPU Architecture mismatch! Selected binary is '$binaryArch', but this TV requires $recommended (Device ABI: ${getDeviceAbiList().firstOrNull()})."
            }

            if (interp != null && hasAtfork && !systemHasAtfork) {
                LogCollector.log(
                    "[ELF-Inspect] WARNING: '${file.name}' is dynamically linked ($interp) and requires '__register_atfork', " +
                        "which is missing from this KitKat device's /system/lib/libc.so! 'jiotv_go-linux-arm' or 'jiotv_go-android5-armv7' is recommended."
                )
            }

            null
        } catch (e: Exception) {
            Log.w(TAG, "ELF validation warning", e)
            null
        }
    }

    private fun readUInt16(buf: ByteArray, offset: Int, littleEndian: Boolean): Int {
        return if (littleEndian) {
            (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
        } else {
            ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
        }
    }

    private fun readUInt32(buf: ByteArray, offset: Int, littleEndian: Boolean): Long {
        return if (littleEndian) {
            (buf[offset].toLong() and 0xFF) or
                ((buf[offset + 1].toLong() and 0xFF) shl 8) or
                ((buf[offset + 2].toLong() and 0xFF) shl 16) or
                ((buf[offset + 3].toLong() and 0xFF) shl 24)
        } else {
            ((buf[offset].toLong() and 0xFF) shl 24) or
                ((buf[offset + 1].toLong() and 0xFF) shl 16) or
                ((buf[offset + 2].toLong() and 0xFF) shl 8) or
                (buf[offset + 3].toLong() and 0xFF)
        }
    }

    /**
     * Reads ELF program headers to extract the `PT_INTERP` string (e.g. `/system/bin/linker`),
     * or returns `null` if the binary is statically linked (`CGO_ENABLED=0`).
     */
    private fun extractElfInterpreter(file: File, header: ByteArray, eiClass: Int, littleEndian: Boolean): String? {
        return try {
            if (eiClass != 1) return null // 32-bit ELF check primarily for KitKat ARMv7
            val phOff = readUInt32(header, 28, littleEndian)
            val phEntSize = readUInt16(header, 42, littleEndian)
            val phNum = readUInt16(header, 44, littleEndian)
            if (phOff <= 0L || phEntSize < 32 || phNum <= 0 || phNum > 32) return null

            RandomAccessFile(file, "r").use { raf ->
                val phBuf = ByteArray(phEntSize)
                for (i in 0 until phNum) {
                    raf.seek(phOff + i.toLong() * phEntSize)
                    raf.readFully(phBuf)
                    val pType = readUInt32(phBuf, 0, littleEndian)
                    if (pType == 3L) { // PT_INTERP == 3
                        val interpOff = readUInt32(phBuf, 4, littleEndian)
                        val interpFilesz = readUInt32(phBuf, 16, littleEndian).toInt().coerceIn(1, 256)
                        val strBuf = ByteArray(interpFilesz)
                        raf.seek(interpOff)
                        raf.readFully(strBuf)
                        return String(strBuf, Charsets.US_ASCII).trimEnd('\u0000')
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Scans the entire file in 128 KB chunks (with boundary overlap) to detect whether a symbol name
     * (such as `__register_atfork`) exists anywhere in the binary's string tables.
     */
    fun fileContainsSymbolFast(file: File, symbol: String): Boolean {
        return try {
            val needle = symbol.toByteArray(Charsets.US_ASCII)
            if (needle.isEmpty() || !file.exists()) return false
            val len = file.length()
            if (len < needle.size) return false

            RandomAccessFile(file, "r").use { raf ->
                val chunkSize = 128 * 1024
                val overlap = needle.size
                val buf = ByteArray(chunkSize + overlap)
                var offset = 0L
                while (offset < len) {
                    raf.seek(offset)
                    val toRead = minOf(buf.size.toLong(), len - offset).toInt()
                    var totalRead = 0
                    while (totalRead < toRead) {
                        val r = raf.read(buf, totalRead, toRead - totalRead)
                        if (r <= 0) break
                        totalRead += r
                    }
                    if (indexOfBytes(buf, totalRead, needle) >= 0) {
                        return true
                    }
                    if (totalRead <= chunkSize) break
                    offset += chunkSize
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun indexOfBytes(haystack: ByteArray, haystackLen: Int, needle: ByteArray): Int {
        if (needle.isEmpty() || haystackLen < needle.size) return -1
        val limit = haystackLen - needle.size
        val firstByte = needle[0]
        outer@ for (i in 0..limit) {
            if (haystack[i] != firstByte) continue
            for (j in 1 until needle.size) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    fun makeBinaryExecutable(file: File) {
        try {
            file.parentFile?.setExecutable(true, false)
            file.parentFile?.setReadable(true, false)
        } catch (_: Exception) {
        }
        val execSet = file.setExecutable(true, false)
        val readSet = file.setReadable(true, false)
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "755", file.absolutePath)).waitFor()
        } catch (_: Exception) {
        }
        Log.d(TAG, "makeBinaryExecutable(${file.name}): setExec=$execSet, setRead=$readSet, canExec=${file.canExecute()}")
    }

    /**
     * Scans common storage locations on Android 4.4 KitKat TV (Downloads, Internal SD, USB drives)
     * for candidate `jiotv_go` binaries so the user can import with one click on a TV remote.
     */
    @Suppress("DEPRECATION")
    fun scanStorageForCandidateBinaries(): List<File> {
        val candidates = linkedSetOf<File>()
        val searchDirs = linkedSetOf<File>()

        try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { searchDirs.add(it) }
        } catch (_: Exception) {
        }
        try {
            Environment.getExternalStorageDirectory()?.let { sd ->
                searchDirs.add(sd)
                searchDirs.add(File(sd, "Download"))
                searchDirs.add(File(sd, "Downloads"))
            }
        } catch (_: Exception) {
        }

        listOf(
            "/sdcard/Download",
            "/sdcard/Downloads",
            "/sdcard",
            "/storage/emulated/0/Download",
            "/storage/emulated/0",
            "/storage",
            "/mnt/usb_storage",
            "/mnt/usb",
            "/mnt/media_rw",
            "/mnt/external_sd",
            "/mnt"
        ).forEach { path ->
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                searchDirs.add(dir)
                dir.listFiles()?.filter { it.isDirectory && it.canRead() }?.take(12)?.forEach { sub ->
                    searchDirs.add(sub)
                    val subDl = File(sub, "Download")
                    if (subDl.exists() && subDl.isDirectory) searchDirs.add(subDl)
                }
            }
        }

        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory || !dir.canRead()) continue
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile || !f.canRead()) continue
                val name = f.name.lowercase()
                if (name.contains("jiotv_go") || name.contains("jiotv-go") || name.contains("jiotvgo")) {
                    if (!name.endsWith(".apk") && !name.endsWith(".txt") && f.length() > 100_000L) {
                        candidates.add(f)
                    }
                }
            }
        }

        return candidates.sortedByDescending { it.lastModified() }
    }
}
