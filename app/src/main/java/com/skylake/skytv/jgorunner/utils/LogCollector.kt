package com.skylake.skytv.jgorunner.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import com.skylake.skytv.jgorunner.core.update.BinaryUpdater
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object LogCollector {
    private const val TAG = "JTVGo::LogCollector"
    private const val LOG_FILE_NAME = "jiotv_go_logs.txt"
    private const val MAX_LOGS = 500

    private val logs = CopyOnWriteArrayList<String>()
    private val targetLogFiles = CopyOnWriteArrayList<File>()

    @Volatile
    private var initialized = false

    @Volatile
    private var primaryLogPath: String = "/sdcard/Download/$LOG_FILE_NAME"

    fun getPrimaryLogFilePath(): String = primaryLogPath

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true

        discoverWritableLogFiles(context)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logError("FATAL UNCAUGHT EXCEPTION on thread ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        log("============================================================")
        log("JioTV Go KitKat Diagnostic Log Session Started")
        log("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Board=${Build.BOARD}, HW=${Build.HARDWARE})")
        @Suppress("DEPRECATION")
        log("Android: ${Build.VERSION.RELEASE} (SDK_INT=${Build.VERSION.SDK_INT}) | ABIs=${Build.CPU_ABI}, ${Build.CPU_ABI2}")
        log("Recommended Binaries: ${BinaryUpdater.getCompatibleAssetNamesForDevice().joinToString(", ")}")
        log("filesDir: ${context.filesDir.absolutePath} (canExec=${context.filesDir.canExecute()}, canWrite=${context.filesDir.canWrite()})")
        log("nativeLibraryDir: ${context.applicationInfo.nativeLibraryDir}")

        try {
            val kernel = File("/proc/version").takeIf { it.canRead() }?.readText()?.trim() ?: "unreadable"
            log("Kernel: $kernel")
        } catch (_: Exception) {
        }

        try {
            val mounts = File("/proc/mounts").takeIf { it.canRead() }?.readLines()
                ?.filter { it.contains(" /data ") || it.contains(" /sdcard ") || it.contains(" /storage") }
                ?.joinToString(" || ")
            if (!mounts.isNullOrBlank()) {
                log("Mounts: $mounts")
            }
        } catch (_: Exception) {
        }

        try {
            val libc32 = File("/system/lib/libc.so")
            val hasAtfork = if (libc32.exists()) BinaryUpdater.fileContainsSymbolFast(libc32, "__register_atfork") else false
            log("System Bionic /system/lib/libc.so exists=${libc32.exists()}, size=${if (libc32.exists()) libc32.length() else 0}, has__register_atfork=$hasAtfork")
        } catch (_: Exception) {
        }

        try {
            val existingFiles = context.filesDir.listFiles()?.joinToString(", ") { f ->
                "${f.name}(size=${f.length()},r=${f.canRead()},x=${f.canExecute()})"
            } ?: "none"
            log("Existing filesDir contents: $existingFiles")
        } catch (_: Exception) {
        }

        log("Log file output path: $primaryLogPath")
        log("============================================================")
    }

    @Suppress("DEPRECATION")
    private fun discoverWritableLogFiles(context: Context) {
        val candidates = linkedSetOf<File>()
        try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { dl ->
                candidates.add(File(dl, LOG_FILE_NAME))
            }
        } catch (_: Exception) {
        }
        try {
            Environment.getExternalStorageDirectory()?.let { sd ->
                candidates.add(File(File(sd, "Download"), LOG_FILE_NAME))
                candidates.add(File(File(sd, "Downloads"), LOG_FILE_NAME))
                candidates.add(File(sd, LOG_FILE_NAME))
            }
        } catch (_: Exception) {
        }

        listOf(
            "/sdcard/Download/$LOG_FILE_NAME",
            "/sdcard/Downloads/$LOG_FILE_NAME",
            "/storage/emulated/0/Download/$LOG_FILE_NAME",
            "/storage/emulated/0/$LOG_FILE_NAME",
            "/sdcard/$LOG_FILE_NAME"
        ).forEach { path ->
            candidates.add(File(path))
        }

        try {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let {
                candidates.add(File(it, LOG_FILE_NAME))
            }
            context.getExternalFilesDir(null)?.let {
                candidates.add(File(it, LOG_FILE_NAME))
            }
        } catch (_: Exception) {
        }

        candidates.add(File(context.filesDir, LOG_FILE_NAME))
        candidates.add(File(context.cacheDir, "app_logs.txt"))

        val seenCanonical = HashSet<String>()
        val activeFiles = mutableListOf<File>()

        for (file in candidates) {
            try {
                val parent = file.parentFile ?: continue
                if (!parent.exists()) {
                    parent.mkdirs()
                }
                if (!parent.exists() || !parent.canWrite()) continue
                val canonical = try {
                    file.canonicalPath
                } catch (_: Exception) {
                    file.absolutePath
                }
                if (seenCanonical.add(canonical)) {
                    activeFiles.add(file)
                }
            } catch (_: Exception) {
            }
        }

        targetLogFiles.clear()
        targetLogFiles.addAll(activeFiles)
        primaryLogPath = activeFiles.firstOrNull()?.absolutePath
            ?: File(context.filesDir, LOG_FILE_NAME).absolutePath
    }

    fun log(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = "[$timestamp] $message"
        Log.d(TAG, message)
        logs.add(entry)
        while (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }
        appendLineToFiles(entry)
    }

    fun logError(message: String, throwable: Throwable?) {
        log("ERROR: $message")
        throwable?.let {
            log("CAUSE: ${it.javaClass.name}: ${it.message}")
            log("STACKTRACE: ${Log.getStackTraceString(it)}")
        }
    }

    @Synchronized
    private fun appendLineToFiles(line: String) {
        val bytes = "$line\n".toByteArray(Charsets.UTF_8)
        for (file in targetLogFiles) {
            try {
                file.parentFile?.let { if (!it.exists()) it.mkdirs() }
                FileOutputStream(file, true).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not append to ${file.absolutePath}: ${e.message}")
            }
        }
    }

    @Synchronized
    fun flushFullLogToDownloads(context: Context): String {
        if (!initialized) init(context)
        discoverWritableLogFiles(context)
        val content = logs.joinToString("\n") + "\n"
        var writtenPath: String? = null
        for (file in targetLogFiles) {
            try {
                file.parentFile?.let { if (!it.exists()) it.mkdirs() }
                file.writeText(content, Charsets.UTF_8)
                if (writtenPath == null) {
                    writtenPath = file.absolutePath
                }
            } catch (_: Exception) {
            }
        }
        return writtenPath ?: primaryLogPath
    }

    fun clear() {
        logs.clear()
        for (file in targetLogFiles) {
            try {
                if (file.exists()) file.writeText("", Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }

    fun getLogs(): String {
        return logs.joinToString("\n")
    }

    fun copyToClipboard(context: Context) {
        val logText = getLogs()
        if (logText.isEmpty()) {
            Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SkyTV Logs", logText)
            clipboard.setPrimaryClip(clip)

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                Toast.makeText(context, "Logs copied to clipboard & saved to $primaryLogPath", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to copy: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
