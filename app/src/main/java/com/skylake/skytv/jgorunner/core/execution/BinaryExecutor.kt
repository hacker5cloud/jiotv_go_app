package com.skylake.skytv.jgorunner.core.execution

import android.os.Build
import android.util.Log
import com.skylake.skytv.jgorunner.utils.LogCollector
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

object BinaryExecutor {
    private const val TAG = "JTVGo::BinaryExecutor"

    private val executionCounter = AtomicLong(0)

    @Volatile
    private var runningProcess: Process? = null

    @Volatile
    private var stoppedByUser: Boolean = false

    fun isStoppedByUser(): Boolean = stoppedByUser

    fun isProcessRunning(): Boolean {
        val proc = runningProcess ?: return false
        return try {
            proc.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    fun executeBinary(
        binaryFile: File,
        arguments: Array<String>,
        workingDirectory: File,
        environment: Map<String, String>?,
        onOutput: (String) -> Unit,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (!binaryFile.exists()) {
            val errMsg = "Binary file does not exist: ${binaryFile.absolutePath}"
            LogCollector.log("[ExecError] $errMsg")
            onError(IOException(errMsg))
            return
        }

        val myExecId = executionCounter.incrementAndGet()

        // Stop any previous process instance synchronously before starting a new one
        stopExecution(markUserStopped = false)
        stoppedByUser = false

        try {
            workingDirectory.setExecutable(true, false)
            workingDirectory.setReadable(true, false)
            workingDirectory.setWritable(true, true)
            binaryFile.parentFile?.setExecutable(true, false)
            binaryFile.parentFile?.setReadable(true, false)
        } catch (_: Exception) {
        }
        binaryFile.setExecutable(true, false)
        binaryFile.setReadable(true, false)
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "755", workingDirectory.absolutePath)).waitFor()
            Runtime.getRuntime().exec(arrayOf("chmod", "755", binaryFile.absolutePath)).waitFor()
        } catch (_: Exception) {
        }

        val command = arrayOf(binaryFile.absolutePath, *arguments)
        val cmdSummary = command.joinToString(" ")
        Log.d(TAG, "Starting jiotv_go command (#$myExecId): $cmdSummary")
        LogCollector.log(
            "[Exec #$myExecId] Starting binary: ${binaryFile.absolutePath} " +
                "(size=${binaryFile.length()}B, canExec=${binaryFile.canExecute()}, canRead=${binaryFile.canRead()})"
        )
        LogCollector.log("[Exec #$myExecId] Command: $cmdSummary")
        LogCollector.log("[Exec #$myExecId] WorkDir: ${workingDirectory.absolutePath}")
        if (environment != null) {
            LogCollector.log("[Exec #$myExecId] Env: ${environment.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
        }
        onOutput("[Exec] $cmdSummary")

        val recentLines = Collections.synchronizedList(mutableListOf<String>())
        var localProcess: Process? = null

        try {
            val process = try {
                val pb = ProcessBuilder(*command)
                    .directory(workingDirectory)
                    .redirectErrorStream(true)
                if (environment != null) {
                    pb.environment().putAll(environment)
                }
                pb.start()
            } catch (directErr: Throwable) {
                Log.w(TAG, "Direct ProcessBuilder.start() failed (${directErr.message}), falling back to sh -c", directErr)
                LogCollector.log("[Exec #$myExecId] Direct ProcessBuilder.start() failed (${directErr.javaClass.simpleName}: ${directErr.message}); falling back to sh -c")
                onOutput("[Exec] Direct exec failed (${directErr.message}); using sh -c fallback...")
                val shellCmd = buildShellCommand(binaryFile, arguments)
                LogCollector.log("[Exec #$myExecId] Shell Command: sh -c \"$shellCmd\"")
                val pb = ProcessBuilder("sh", "-c", shellCmd)
                    .directory(workingDirectory)
                    .redirectErrorStream(true)
                if (environment != null) {
                    pb.environment().putAll(environment)
                }
                pb.start()
            }

            localProcess = process
            runningProcess = process

            val pid = getProcessId(process)
            Log.d(TAG, "jiotv_go process started (PID=${pid ?: "unknown"}, execId=#$myExecId)")
            LogCollector.log("[Exec #$myExecId] Process spawned (PID=${pid ?: "unknown"})")

            val readerThread = Thread({
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            Log.d(TAG, "jiotv_go: $line")
                            synchronized(recentLines) {
                                if (recentLines.size >= 20) recentLines.removeAt(0)
                                recentLines.add(line)
                            }
                            onOutput(line)
                        }
                    }
                } catch (readErr: IOException) {
                    LogCollector.log("[Exec #$myExecId] Stdout reader closed: ${readErr.message}")
                }
            }, "JioTVGo-Stdout-$myExecId")
            readerThread.isDaemon = true
            readerThread.start()

            val exitCode = process.waitFor()
            try {
                readerThread.join(500)
            } catch (_: InterruptedException) {
            }

            val wasSuperseded = executionCounter.get() != myExecId || (runningProcess != null && runningProcess !== localProcess)
            LogCollector.log(
                "[Exec #$myExecId] Process exited with code=$exitCode " +
                    "(stoppedByUser=$stoppedByUser, wasSuperseded=$wasSuperseded)"
            )

            if (exitCode == 0 || stoppedByUser || wasSuperseded) {
                onSuccess()
            } else {
                val lastOutput = synchronized(recentLines) {
                    recentLines.takeLast(5).joinToString(" | ")
                }
                val detail = if (lastOutput.isNotBlank()) {
                    "jiotv_go exited ($exitCode): $lastOutput"
                } else {
                    "jiotv_go exited with code $exitCode"
                }
                LogCollector.log("[ExecError #$myExecId] $detail")
                onError(IOException(detail))
            }
        } catch (e: IOException) {
            LogCollector.log("[ExecException #$myExecId] IOException: ${e.message}")
            onError(e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LogCollector.log("[ExecException #$myExecId] InterruptedException: ${e.message}")
            onError(e)
        } catch (t: Throwable) {
            LogCollector.log("[ExecException #$myExecId] ${t.javaClass.simpleName}: ${t.message}")
            onError(IOException("${t.javaClass.simpleName}: ${t.message}", t))
        } finally {
            if (runningProcess === localProcess) {
                runningProcess = null
            }
        }
    }

    private fun buildShellCommand(binaryFile: File, arguments: Array<String>): String {
        val escapedArgs = arguments.joinToString(" ") { arg ->
            if (arg.contains(' ') || arg.contains('"')) {
                "'${arg.replace("'", "'\\''")}'"
            } else {
                arg
            }
        }
        return "chmod 755 '${binaryFile.absolutePath}' 2>/dev/null; '${binaryFile.absolutePath}' $escapedArgs"
    }

    @Synchronized
    fun stopExecution(markUserStopped: Boolean = true) {
        if (markUserStopped) {
            stoppedByUser = true
        }
        val proc = runningProcess ?: return
        runningProcess = null
        val pid = getProcessId(proc)
        LogCollector.log("[StopExec] Stopping running process (PID=${pid ?: "unknown"}, markUserStopped=$markUserStopped)")
        try {
            if (pid != null) {
                try {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -P $pid")).waitFor()
                } catch (_: Exception) {
                }
            }
            try {
                proc.inputStream.close()
                proc.outputStream.close()
                proc.errorStream.close()
            } catch (_: Exception) {
            }
            proc.destroy()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                proc.destroyForcibly()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying process", e)
        }
    }

    private fun getProcessId(process: Process): String? {
        return try {
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(process).toString()
        } catch (_: Exception) {
            null
        }
    }
}
