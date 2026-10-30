package com.skylake.skytv.jgorunner.widgets

import android.content.Context
import com.skylake.skytv.jgorunner.data.SkySharedPref
import java.net.Inet4Address
import java.net.NetworkInterface

fun getFullServerUrl(context: Context): String {
    val prefs = SkySharedPref.getInstance(context).myPreferences
    val port = prefs.jtvGoServerPort
    val isPublic = !prefs.serveLocal

    if (!isPublic) return "http://127.0.0.1:$port/"

    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces != null && interfaces.hasMoreElements()) {
            val intf = interfaces.nextElement()
            if (!intf.isUp || intf.isLoopback) continue
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val host = addr.hostAddress
                    if (!host.isNullOrBlank()) {
                        return "http://$host:$port/"
                    }
                }
            }
        }
    } catch (_: Exception) {
    }
    return "http://127.0.0.1:$port/"
}
