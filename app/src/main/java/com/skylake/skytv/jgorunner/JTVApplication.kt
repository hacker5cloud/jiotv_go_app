package com.skylake.skytv.jgorunner

import android.app.Application
import com.skylake.skytv.jgorunner.utils.LogCollector
import com.skylake.skytv.jgorunner.utils.Tls12SocketFactory

class JTVApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize real-time Downloads diagnostic logger (/sdcard/Download/jiotv_go_logs.txt)
        LogCollector.init(this)
        // Enable TLS 1.2/1.3 for Android 4.4 KitKat (API 19)
        Tls12SocketFactory.installForKitKat()
    }
}
