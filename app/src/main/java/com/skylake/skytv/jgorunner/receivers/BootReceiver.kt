package com.skylake.skytv.jgorunner.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.services.BinaryService

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action ||
            "android.intent.action.QUICKBOOT_POWERON" == intent.action
        ) {
            handleBootCompleted(context)
        }
    }

    private fun handleBootCompleted(context: Context) {
        val preferenceManager = SkySharedPref.getInstance(context)
        val isAutoStartOnBootEnabled = preferenceManager.myPreferences.autoStartOnBoot
        val autoStartOnBootForeground = preferenceManager.myPreferences.autoStartOnBootForeground

        if (isAutoStartOnBootEnabled) {
            if (autoStartOnBootForeground) {
                preferenceManager.myPreferences.autoStartServer = true
                preferenceManager.savePreferences()
                launchMainActivity(context)
            } else {
                startBinaryService(context)
            }
        }
    }

    private fun startBinaryService(context: Context) {
        Toast.makeText(context, "[JGO] Running Server in Background", Toast.LENGTH_SHORT).show()
        val serviceIntent = Intent(context, BinaryService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        Log.d(TAG, "BinaryService started in the background.")
    }

    private fun launchMainActivity(context: Context) {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(context.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } else {
            Log.e(TAG, "Unable to launch app: Launch intent is null.")
        }
    }
}
