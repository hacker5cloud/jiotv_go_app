package com.skylake.skytv.jgorunner.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.skylake.skytv.jgorunner.activities.MainActivity
import com.skylake.skytv.jgorunner.core.execution.BinaryExecutor
import com.skylake.skytv.jgorunner.core.execution.ExecutionManager
import com.skylake.skytv.jgorunner.core.server.EmbeddedJioTvServer
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.utils.LogCollector
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

class BinaryService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "JTVGo::BinaryService"
        private const val WAKE_LOCK_TAG = "JTVGo:BinaryServiceWakeLock"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "binary_service_channel"

        const val ACTION_STOP_BINARY = "com.skylake.skytv.jgorunner.action.STOP_BINARY"
        const val ACTION_BINARY_STOPPED = "com.skylake.skytv.jgorunner.action.BINARY_STOPPED"
        const val ACTION_BINARY_STARTED = "com.skylake.skytv.jgorunner.action.BINARY_STARTED"
        const val ACTION_BINARY_LOG = "com.skylake.skytv.jgorunner.action.BINARY_LOG"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
        const val EXTRA_LOG_LINE = "extra_log_line"

        private val runGeneration = AtomicLong(0)

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var isStarting: Boolean = false

        @Volatile
        var lastErrorMessage: String? = null
            private set

        /**
         * Fast TCP port readiness check on 127.0.0.1:port.
         * Never performs blocking network I/O on the Main UI Thread (returns cached [isRunning] if on Main Looper).
         */
        fun isPortListening(port: Int, timeoutMs: Int = 180): Boolean {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return isRunning
            }
            return try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogCollector.init(this)
        LogCollector.log("[BinaryService] onCreate()")
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_BINARY) {
            LogCollector.log("[BinaryService] Received ACTION_STOP_BINARY")
            stopBinaryExecution(null)
            return START_NOT_STICKY
        }

        val prefs = SkySharedPref.getInstance(this).myPreferences
        val port = prefs.jtvGoServerPort
        val isPublic = !prefs.serveLocal
        val forceRestart = intent?.getBooleanExtra("force_restart", false) ?: false
        LogCollector.log("[BinaryService] onStartCommand(port=$port, forceRestart=$forceRestart, isRunning=$isRunning, isStarting=$isStarting)")

        if (!forceRestart && isRunning && (BinaryExecutor.isProcessRunning() || EmbeddedJioTvServer.isRunning())) {
            lastErrorMessage = null
            sendBroadcast(Intent(ACTION_BINARY_STARTED).apply { setPackage(packageName) })
            return START_STICKY
        }

        if (!forceRestart && isStarting && (BinaryExecutor.isProcessRunning() || EmbeddedJioTvServer.isRunning())) {
            Log.d(TAG, "jiotv_go is already starting; skipping duplicate start request")
            LogCollector.log("[BinaryService] Already starting; skipping duplicate start request")
            return START_STICKY
        }

        val myGen = runGeneration.incrementAndGet()
        isStarting = true
        if (forceRestart) {
            isRunning = false
            ExecutionManager.stopExecution()
            EmbeddedJioTvServer.stop()
        }
        lastErrorMessage = null
        val args = intent?.getStringArrayExtra("arguments") ?: emptyArray()

        Thread({
            if (!forceRestart && isPortListening(port, 120)) {
                if (runGeneration.get() == myGen) {
                    isStarting = false
                    isRunning = true
                    lastErrorMessage = null
                    LogCollector.log("[BinaryService #$myGen] Port $port already listening; broadcasting ACTION_BINARY_STARTED")
                    sendBroadcast(Intent(ACTION_BINARY_STARTED).apply { setPackage(packageName) })
                }
                return@Thread
            }

            ExecutionManager.executeServerBinary(
                context = this,
                arguments = args,
                onOutput = { output ->
                    Log.d(TAG, output)
                    sendBroadcast(
                        Intent(ACTION_BINARY_LOG).apply {
                            setPackage(packageName)
                            putExtra(EXTRA_LOG_LINE, output)
                        }
                    )
                },
                onSuccess = {
                    if (runGeneration.get() == myGen && !EmbeddedJioTvServer.isRunning()) {
                        isStarting = false
                        isRunning = false
                        LogCollector.log("[BinaryService #$myGen] Server process exited cleanly (onSuccess)")
                    }
                },
                onError = { err ->
                    if (runGeneration.get() != myGen) {
                        LogCollector.log("[BinaryService #$myGen] Ignoring error from superseded runner generation: ${err.message}")
                        return@executeServerBinary
                    }
                    val errMsg = err.message ?: "Unknown server startup error"
                    Log.w(TAG, "Native binary failed ($errMsg); activating EmbeddedJioTvServer on port $port")
                    LogCollector.log("[BinaryService #$myGen] Native binary exited ($errMsg) -> Activating in-process EmbeddedJioTvServer on port $port")

                    val embeddedStarted = EmbeddedJioTvServer.start(
                        context = this@BinaryService,
                        port = port,
                        isPublic = isPublic,
                        onLog = { line ->
                            sendBroadcast(
                                Intent(ACTION_BINARY_LOG).apply {
                                    setPackage(packageName)
                                    putExtra(EXTRA_LOG_LINE, line)
                                }
                            )
                        }
                    )

                    if (embeddedStarted && runGeneration.get() == myGen) {
                        isStarting = false
                        isRunning = true
                        lastErrorMessage = null
                        val okLine = "[EmbeddedServer] Active on port $port (KitKat Native Engine)"
                        LogCollector.log("[BinaryService #$myGen] SUCCESS: $okLine")
                        sendBroadcast(
                            Intent(ACTION_BINARY_LOG).apply {
                                setPackage(packageName)
                                putExtra(EXTRA_LOG_LINE, okLine)
                            }
                        )
                        sendBroadcast(Intent(ACTION_BINARY_STARTED).apply { setPackage(packageName) })
                    } else if (runGeneration.get() == myGen) {
                        isStarting = false
                        isRunning = false
                        lastErrorMessage = errMsg
                        Log.e(TAG, "Binary & Embedded server execution error: $errMsg")
                        LogCollector.log("[BinaryService #$myGen] ERROR: $errMsg")
                        sendBroadcast(
                            Intent(ACTION_BINARY_STOPPED).apply {
                                setPackage(packageName)
                                putExtra(EXTRA_ERROR_MESSAGE, errMsg)
                            }
                        )
                        mainHandler.post {
                            if (runGeneration.get() == myGen) {
                                stopSelf()
                            }
                        }
                    }
                }
            )
        }, "JioTVGo-Runner-$myGen").start()

        // Fast readiness probe: poll localhost port every 80ms so startup is immediate (usually < 400ms!)
        Thread({
            val startMs = System.currentTimeMillis()
            while (System.currentTimeMillis() - startMs < 15_000L) {
                if (runGeneration.get() != myGen) {
                    return@Thread
                }
                if (isPortListening(port, 120)) {
                    if (runGeneration.get() == myGen) {
                        val readyElapsed = System.currentTimeMillis() - startMs
                        isStarting = false
                        isRunning = true
                        lastErrorMessage = null
                        LogCollector.log("[BinaryService #$myGen] SUCCESS: Port $port is now listening after ${readyElapsed}ms!")
                        sendBroadcast(Intent(ACTION_BINARY_STARTED).apply { setPackage(packageName) })
                    }
                    return@Thread
                }
                if (!isStarting && !BinaryExecutor.isProcessRunning() && !EmbeddedJioTvServer.isRunning() && (System.currentTimeMillis() - startMs > 800L)) {
                    break
                }
                try {
                    Thread.sleep(80)
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (runGeneration.get() == myGen) {
                isStarting = false
            }
        }, "JioTVGo-PortProbe-$myGen").start()

        return START_STICKY
    }

    private fun stopBinaryExecution(errorMsg: String?) {
        runGeneration.incrementAndGet()
        isStarting = false
        ExecutionManager.stopExecution()
        EmbeddedJioTvServer.stop()
        isRunning = false
        LogCollector.log("[BinaryService] stopBinaryExecution(errorMsg=$errorMsg)")
        sendBroadcast(
            Intent(ACTION_BINARY_STOPPED).apply {
                setPackage(packageName)
                if (!errorMsg.isNullOrBlank()) {
                    putExtra(EXTRA_ERROR_MESSAGE, errorMsg)
                }
            }
        )
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        LogCollector.log("[BinaryService] onDestroy()")
        runGeneration.incrementAndGet()
        isStarting = false
        ExecutionManager.stopExecution()
        EmbeddedJioTvServer.stop()
        isRunning = false
        releaseWakeLock()
        sendBroadcast(
            Intent(ACTION_BINARY_STOPPED).apply {
                setPackage(packageName)
                if (!lastErrorMessage.isNullOrBlank()) {
                    putExtra(EXTRA_ERROR_MESSAGE, lastErrorMessage)
                }
            }
        )
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JioTV Go Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs the local JioTV Go streaming server"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        val stopIntent = Intent(this, BinaryService::class.java).apply {
            action = ACTION_STOP_BINARY
        }
        val stopPending = PendingIntent.getService(this, 1, stopIntent, pendingFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        val port = SkySharedPref.getInstance(this).myPreferences.jtvGoServerPort
        builder.setContentTitle("JioTV Go Server Active (Port $port)")
            .setContentText("Adaptive TV streaming engine ready")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop Server", stopPending)

        return builder.build()
    }
}
