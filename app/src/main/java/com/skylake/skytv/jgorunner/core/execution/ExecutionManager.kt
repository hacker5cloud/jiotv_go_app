package com.skylake.skytv.jgorunner.core.execution

import android.content.Context
import com.skylake.skytv.jgorunner.core.data.JTVConfigurationManager
import com.skylake.skytv.jgorunner.core.update.BinaryUpdater
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.utils.LogCollector
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

object ExecutionManager {
    fun executeServerBinary(
        context: Context,
        arguments: Array<String>,
        onOutput: (String) -> Unit,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        val prefs = SkySharedPref.getInstance(context).myPreferences
        val binaryName = prefs.jtvGoBinaryName?.takeIf { it.isNotBlank() }
            ?: BinaryUpdater.getTargetBinaryAssetName()
        val binaryFile = File(context.filesDir, binaryName)

        LogCollector.log("[ExecManager] Preparing to start binaryName='$binaryName', path='${binaryFile.absolutePath}', exists=${binaryFile.exists()}, size=${binaryFile.length()}B")

        // Pre-validate the ELF binary before executing so incompatible NDK binaries on KitKat are caught immediately
        val elfError = BinaryUpdater.validateElfBinaryFile(binaryFile)
        if (elfError != null) {
            val msg = "Invalid binary (${binaryFile.name}): $elfError"
            LogCollector.log("[ERROR] $msg")
            onOutput("[ERROR] $msg")
            onError(IOException(msg))
            return
        }

        // Determine if this binary is a statically linked Linux binary (no PT_INTERP) or a dynamic Android binary
        val isStaticBinary = isStaticallyLinkedElf(binaryFile) || binaryFile.name.contains("linux", ignoreCase = true)
        LogCollector.log("[ExecManager] Binary '${binaryFile.name}' isStaticBinary=$isStaticBinary")

        // Patch 32-bit ARM Go runtime.open (SYS_open #5 -> SYS_openat #322) in-place so Android seccomp-bpf
        // does not kill the binary with SIGSYS (exit code 31) during runtime.osinit().
        patchArmSeccompSyscallsInPlace(binaryFile)

        // Start local KitKat DNS/TCP Bridge Proxy so static Go binaries (`jiotv_go-linux-arm`)
        // resolve JioTV domains via Android's native Java DNS instead of missing `/etc/resolv.conf`.
        // Note: FasthttpHTTPDialer in jiotv_go expects "host:port" WITHOUT "http://" prefix!
        val bridgeProxyPort = if (isStaticBinary) KitKatLocalBridgeProxy.ensureStarted() else -1
        val fasthttpProxyHostPort = if (isStaticBinary && bridgeProxyPort > 0) "127.0.0.1:$bridgeProxyPort" else ""
        val standardHttpProxyUrl = if (isStaticBinary && bridgeProxyPort > 0) "http://127.0.0.1:$bridgeProxyPort" else null

        // Ensure config file is saved and up-to-date before starting server
        val configManager = JTVConfigurationManager.getInstance(context)
        configManager.saveJTVConfiguration(overrideProxyUrl = fasthttpProxyHostPort)
        val configPath = prefs.jtvConfigLocation
            ?: File(File(context.filesDir, "jiotv_go"), "jtv_config.json").absolutePath

        try {
            val cfgFile = File(configPath)
            if (cfgFile.exists()) {
                LogCollector.log("[ExecManager] Config file ($configPath): ${cfgFile.readText().replace("\n", " ")}")
            }
        } catch (_: Exception) {
        }

        // Prepare modern Root CA bundle (`cacert.pem`) for Go's `crypto/x509` on Android 4.4 KitKat
        val caBundleFile = KitKatCaBundleProvider.ensureCaBundleFile(context)

        val port = prefs.jtvGoServerPort
        val isPublic = !prefs.serveLocal

        val env = hashMapOf(
            "HOME" to context.filesDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "JIOTV_PATH_PREFIX" to context.filesDir.absolutePath,
            "JIOTV_EPG" to "false"
        )
        if (fasthttpProxyHostPort.isNotEmpty() && standardHttpProxyUrl != null) {
            env["JIOTV_PROXY"] = fasthttpProxyHostPort
            env["HTTP_PROXY"] = standardHttpProxyUrl
            env["HTTPS_PROXY"] = standardHttpProxyUrl
            env["NO_PROXY"] = "127.0.0.1,localhost"
        }
        if (caBundleFile != null && caBundleFile.exists()) {
            env["SSL_CERT_FILE"] = caBundleFile.absolutePath
        }
        val sysCaDir = File("/system/etc/security/cacerts")
        if (sysCaDir.exists() && sysCaDir.isDirectory) {
            env["SSL_CERT_DIR"] = sysCaDir.absolutePath
        }

        // Candidate CLI argument layouts matching jiotv_go CLI (urfave/cli):
        // NOTE: jiotv_go `run` subcommand ONLY supports `--port` (`-p`) and `--public`.
        // It does NOT support `--host` (omitting `--public` already binds to localhost).
        val primaryArgs = if (arguments.isNotEmpty()) {
            arguments
        } else {
            mutableListOf(
                "--config",
                configPath,
                "run",
                "--port",
                port.toString()
            ).apply {
                if (isPublic) add("--public")
            }.toTypedArray()
        }

        val fallbackSubcommandArgs = mutableListOf(
            "run",
            "--port",
            port.toString(),
            "--config",
            configPath
        ).apply {
            if (isPublic) add("--public")
        }.toTypedArray()

        val minimalArgs = mutableListOf(
            "run",
            "--port",
            port.toString()
        ).apply {
            if (isPublic) add("--public")
        }.toTypedArray()

        val startMs = System.currentTimeMillis()
        BinaryExecutor.executeBinary(
            binaryFile = binaryFile,
            arguments = primaryArgs,
            workingDirectory = context.filesDir,
            environment = env,
            onOutput = { line ->
                LogCollector.log("[jiotv_go] $line")
                onOutput(line)
            },
            onSuccess = onSuccess,
            onError = { firstErr ->
                val elapsed = System.currentTimeMillis() - startMs
                // If process failed within 2.5 seconds (e.g. CLI flag syntax mismatch on older binary), try fallback layout
                if (elapsed < 2500L && !BinaryExecutor.isStoppedByUser()) {
                    LogCollector.log("[Retry #1] Primary CLI args exited early after ${elapsed}ms (${firstErr.message}); retrying with subcommand flags...")
                    onOutput("[Retry] Retrying server startup with alternate CLI flags...")
                    val retryStartMs = System.currentTimeMillis()
                    BinaryExecutor.executeBinary(
                        binaryFile = binaryFile,
                        arguments = fallbackSubcommandArgs,
                        workingDirectory = context.filesDir,
                        environment = env,
                        onOutput = { line ->
                            LogCollector.log("[jiotv_go] $line")
                            onOutput(line)
                        },
                        onSuccess = onSuccess,
                        onError = { secondErr ->
                            val retryElapsed = System.currentTimeMillis() - retryStartMs
                            if (retryElapsed < 2500L && !BinaryExecutor.isStoppedByUser()) {
                                LogCollector.log("[Retry #2] Subcommand CLI args exited after ${retryElapsed}ms (${secondErr.message}); retrying minimal 'run --port $port'...")
                                BinaryExecutor.executeBinary(
                                    binaryFile = binaryFile,
                                    arguments = minimalArgs,
                                    workingDirectory = context.filesDir,
                                    environment = env,
                                    onOutput = { line ->
                                        LogCollector.log("[jiotv_go] $line")
                                        onOutput(line)
                                    },
                                    onSuccess = onSuccess,
                                    onError = onError
                                )
                            } else {
                                onError(secondErr)
                            }
                        }
                    )
                } else {
                    onError(firstErr)
                }
            }
        )
    }

    /**
     * Checks whether the ELF binary has a PT_INTERP program header (dynamic linker).
     * Returns true if statically linked (no PT_INTERP), false if dynamically linked.
     */
    private fun isStaticallyLinkedElf(file: File): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 64) return false
                val hdr = ByteArray(64)
                raf.readFully(hdr)
                val is64 = hdr[4].toInt() == 2
                val isLE = hdr[5].toInt() == 1
                fun u16(off: Int): Int {
                    val b0 = hdr[off].toInt() and 0xFF
                    val b1 = hdr[off + 1].toInt() and 0xFF
                    return if (isLE) b0 or (b1 shl 8) else (b0 shl 8) or b1
                }
                fun u32(off: Int): Long {
                    val b0 = (hdr[off].toInt() and 0xFF).toLong()
                    val b1 = (hdr[off + 1].toInt() and 0xFF).toLong()
                    val b2 = (hdr[off + 2].toInt() and 0xFF).toLong()
                    val b3 = (hdr[off + 3].toInt() and 0xFF).toLong()
                    return if (isLE) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                    else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                }
                val phoff = if (is64) u32(32) else u32(28)
                val phentsize = if (is64) u16(54) else u16(42)
                val phnum = if (is64) u16(56) else u16(44)
                if (phoff <= 0 || phentsize < 32 || phnum <= 0 || phnum > 64) return false
                val phBuf = ByteArray(phentsize)
                for (i in 0 until phnum) {
                    val entryPos = phoff + i.toLong() * phentsize.toLong()
                    if (entryPos + phentsize > raf.length()) break
                    raf.seek(entryPos)
                    raf.readFully(phBuf)
                    val b0 = phBuf[0].toInt() and 0xFF
                    val b1 = phBuf[1].toInt() and 0xFF
                    val b2 = phBuf[2].toInt() and 0xFF
                    val b3 = phBuf[3].toInt() and 0xFF
                    val pType = if (isLE) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                    else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                    if (pType == 3) {
                        // PT_INTERP found -> Dynamically linked
                        return false
                    }
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Patches Go's 32-bit ARM `runtime.open` (`SYS_open` #5 -> `SYS_openat` #322 with `AT_FDCWD`)
     * in-place inside the ELF binary so Android `seccomp-bpf` does not terminate the process
     * with `SIGSYS` (Signal 31) on startup.
     */
    private fun patchArmSeccompSyscallsInPlace(file: File) {
        try {
            if (!file.exists() || !file.canWrite() || file.length() < 1024) return
            RandomAccessFile(file, "rw").use { raf ->
                val hdr = ByteArray(20)
                raf.readFully(hdr)
                val is32Bit = hdr[4].toInt() == 1
                val isLE = hdr[5].toInt() == 1
                val eMachine = if (isLE) {
                    (hdr[18].toInt() and 0xFF) or ((hdr[19].toInt() and 0xFF) shl 8)
                } else {
                    ((hdr[18].toInt() and 0xFF) shl 8) or (hdr[19].toInt() and 0xFF)
                }
                // Only patch 32-bit little-endian ARM (EM_ARM = 40)
                if (!is32Bit || !isLE || eMachine != 40) return

                val origRuntimeOpen = intArrayOf(
                    0x04, 0x00, 0x9d, 0xe5, // ldr r0, [sp, #4]
                    0x08, 0x10, 0x9d, 0xe5, // ldr r1, [sp, #8]
                    0x0c, 0x20, 0x9d, 0xe5, // ldr r2, [sp, #12]
                    0x05, 0x70, 0xa0, 0xe3, // mov r7, #5 (__NR_open)
                    0x00, 0x00, 0x00, 0xef, // svc #0
                    0x10, 0x10, 0x9f, 0xe5, // ldr r1, [pc, #16]
                    0x01, 0x00, 0x50, 0xe1, // cmp r0, r1
                    0x00, 0x00, 0xe0, 0x83, // mvnhi r0, #0
                    0x10, 0x00, 0x8d, 0xe5, // str r0, [sp, #16]
                    0x00, 0xf0, 0x8e, 0xe2, // add pc, lr, #0
                    0xfe, 0xff, 0xff, 0xea, // b .
                    0x01, 0xf0, 0xff, 0xff  // .word 0xfffff001
                ).map { it.toByte() }.toByteArray()

                val patchedRuntimeOpenat = intArrayOf(
                    0x63, 0x00, 0xe0, 0xe3, // mvn r0, #99 (r0 = -100 = AT_FDCWD)
                    0x04, 0x10, 0x9d, 0xe5, // ldr r1, [sp, #4] (name)
                    0x08, 0x20, 0x9d, 0xe5, // ldr r2, [sp, #8] (mode)
                    0x0c, 0x30, 0x9d, 0xe5, // ldr r3, [sp, #12] (perm)
                    0x42, 0x71, 0x00, 0xe3, // movw r7, #322 (__NR_openat = 0x142)
                    0x00, 0x00, 0x00, 0xef, // svc #0
                    0x0c, 0x10, 0x9f, 0xe5, // ldr r1, [pc, #12]
                    0x01, 0x00, 0x50, 0xe1, // cmp r0, r1
                    0x00, 0x00, 0xe0, 0x83, // mvnhi r0, #0
                    0x10, 0x00, 0x8d, 0xe5, // str r0, [sp, #16]
                    0x00, 0xf0, 0x8e, 0xe2, // add pc, lr, #0
                    0x01, 0xf0, 0xff, 0xff  // .word 0xfffff001
                ).map { it.toByte() }.toByteArray()

                val fileLen = raf.length()
                val chunkSize = 256 * 1024
                val overlap = 64
                val buf = ByteArray(chunkSize + overlap)
                var pos = 0L
                var patchesApplied = 0

                while (pos < fileLen) {
                    raf.seek(pos)
                    val toRead = minOf(buf.size.toLong(), fileLen - pos).toInt()
                    if (toRead < origRuntimeOpen.size) break
                    raf.readFully(buf, 0, toRead)

                    var i = 0
                    val limit = toRead - origRuntimeOpen.size
                    while (i <= limit) {
                        if (buf[i] == origRuntimeOpen[0] && buf[i + 12] == origRuntimeOpen[12] && buf[i + 16] == origRuntimeOpen[16]) {
                            var match = true
                            for (j in 0 until origRuntimeOpen.size) {
                                if (buf[i + j] != origRuntimeOpen[j]) {
                                    match = false
                                    break
                                }
                            }
                            if (match) {
                                val patchOffset = pos + i
                                raf.seek(patchOffset)
                                raf.write(patchedRuntimeOpenat)
                                patchesApplied++
                                LogCollector.log("[ExecManager] Patched ARM runtime.open (SYS_open #5 -> SYS_openat #322) at offset 0x${patchOffset.toString(16)}")
                                i += origRuntimeOpen.size
                                continue
                            }
                        }
                        i += 4
                    }
                    pos += chunkSize
                }
                if (patchesApplied > 0) {
                    file.setExecutable(true, false)
                }
            }
        } catch (e: Exception) {
            LogCollector.log("[ExecManager] ARM syscall patch check failed: ${e.message}")
        }
    }

    fun stopExecution() {
        BinaryExecutor.stopExecution()
    }
}
