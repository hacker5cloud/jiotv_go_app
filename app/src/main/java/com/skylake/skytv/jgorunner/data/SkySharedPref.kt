package com.skylake.skytv.jgorunner.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Zero-reflection SharedPreferences manager optimized for Android 4.4 KitKat (API 19).
 */
class SkySharedPref(context: Context) {

    companion object {
        private const val PREF_NAME = "SkySharedPref"

        @Volatile
        private var instance: SkySharedPref? = null

        fun getInstance(context: Context): SkySharedPref {
            return instance ?: synchronized(this) {
                instance ?: SkySharedPref(context.applicationContext).also { instance = it }
            }
        }

        // UI Modes
        const val UI_MODE_REMOTE = "remote"
        const val UI_MODE_NUMPAD = "numpad"

        // Channel List Modes
        const val LIST_MODE_ALL = "all"
        const val LIST_MODE_CUSTOM = "custom"

        // Startup Direct Launch Modes
        const val STARTUP_NONE = "none"
        const val STARTUP_LAST_CHANNEL = "last"
        const val STARTUP_SPECIFIC_CHANNEL = "custom"
    }

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    val myPreferences: SharedPreferencesData = SharedPreferencesData(sp)
    val myPrefs: SharedPreferencesData get() = myPreferences

    fun savePreferences() {
        // Values in SharedPreferencesData write directly to Editor; apply() is already called per setter,
        // or we ensure flush here.
    }

    class SharedPreferencesData(private val sp: SharedPreferences) {

        var serveLocal: Boolean
            get() = sp.getBoolean("serveLocal", true)
            set(v) = sp.edit().putBoolean("serveLocal", v).apply()

        var autoStartServer: Boolean
            get() = sp.getBoolean("autoStartServer", true)
            set(v) = sp.edit().putBoolean("autoStartServer", v).apply()

        var autoStartOnBoot: Boolean
            get() = sp.getBoolean("autoStartOnBoot", false)
            set(v) = sp.edit().putBoolean("autoStartOnBoot", v).apply()

        var autoStartOnBootForeground: Boolean
            get() = sp.getBoolean("autoStartOnBootForeground", false)
            set(v) = sp.edit().putBoolean("autoStartOnBootForeground", v).apply()

        var jtvGoServerPort: Int
            get() = sp.getInt("jtvGoServerPort", 5350)
            set(v) = sp.edit().putInt("jtvGoServerPort", v).apply()

        var jtvGoBinaryName: String?
            get() = sp.getString("jtvGoBinaryName", null)
            set(v) = sp.edit().putString("jtvGoBinaryName", v).apply()

        var jtvGoBinaryVersion: String?
            get() = sp.getString("jtvGoBinaryVersion", "v0.0.0")
            set(v) = sp.edit().putString("jtvGoBinaryVersion", v).apply()

        var jtvConfigLocation: String?
            get() = sp.getString("jtvConfigLocation", null)
            set(v) = sp.edit().putString("jtvConfigLocation", v).apply()

        /**
         * Stream quality mode:
         * "auto" (Adaptive HLS/DASH - default for fast & smooth playback),
         * "low", "medium", "high"
         */
        var filterQX: String?
            get() = sp.getString("filterQX", "auto")
            set(v) = sp.edit().putString("filterQX", v).apply()

        /**
         * Layout mode:
         * "remote" -> Mode 1: TV Remote D-Pad Grid/List Navigation
         * "numpad" -> Mode 2: Direct Number Entry / Keypad Launcher
         */
        var uiMode: String
            get() = sp.getString("uiMode", UI_MODE_REMOTE) ?: UI_MODE_REMOTE
            set(v) = sp.edit().putString("uiMode", v).apply()

        /**
         * Which channel list is shown in Mode 1:
         * "custom" -> User's Custom Channel List (with custom assigned numbers)
         * "all"    -> All JioTV Channels
         */
        var listMode: String
            get() = sp.getString("listMode", LIST_MODE_ALL) ?: LIST_MODE_ALL
            set(v) = sp.edit().putString("listMode", v).apply()

        var filterCategoryId: Int
            get() = sp.getInt("filterCategoryId", 0)
            set(v) = sp.edit().putInt("filterCategoryId", v).apply()

        var filterLanguageId: Int
            get() = sp.getInt("filterLanguageId", 0)
            set(v) = sp.edit().putInt("filterLanguageId", v).apply()

        /**
         * Direct Launch on App Startup:
         * "none"   -> Stay on Main screen
         * "last"   -> Immediately launch the Last Played Channel
         * "custom" -> Immediately launch the Specific Channel Number (startupChannelNumber)
         */
        var startupLaunchMode: String
            get() = sp.getString("startupLaunchMode", STARTUP_NONE) ?: STARTUP_NONE
            set(v) = sp.edit().putString("startupLaunchMode", v).apply()

        var startupChannelNumber: Int
            get() = sp.getInt("startupChannelNumber", 1)
            set(v) = sp.edit().putInt("startupChannelNumber", v).apply()

        var startupChannelId: String?
            get() = sp.getString("startupChannelId", null)
            set(v) = sp.edit().putString("startupChannelId", v).apply()

        var startupChannelName: String?
            get() = sp.getString("startupChannelName", null)
            set(v) = sp.edit().putString("startupChannelName", v).apply()

        // Last played channel state for instant resume / startup launch
        var lastChannelId: String?
            get() = sp.getString("lastChannelId", null)
            set(v) = sp.edit().putString("lastChannelId", v).apply()

        var lastChannelNumber: Int
            get() = sp.getInt("lastChannelNumber", 1)
            set(v) = sp.edit().putInt("lastChannelNumber", v).apply()

        var lastChannelName: String?
            get() = sp.getString("lastChannelName", null)
            set(v) = sp.edit().putString("lastChannelName", v).apply()

        var lastStreamUrl: String?
            get() = sp.getString("lastStreamUrl", null)
            set(v) = sp.edit().putString("lastStreamUrl", v).apply()

        var lastLogoUrl: String?
            get() = sp.getString("lastLogoUrl", null)
            set(v) = sp.edit().putString("lastLogoUrl", v).apply()

        // Aspect Ratio mode in player: 0 = Fit, 1 = Fill (Stretch), 2 = Zoom
        var aspectRatioMode: Int
            get() = sp.getInt("aspectRatioMode", 0)
            set(v) = sp.edit().putInt("aspectRatioMode", v).apply()

        var recentChannelsJson: String?
            get() = sp.getString("recentChannelsJson", null)
            set(v) = sp.edit().putString("recentChannelsJson", v).apply()

        var widgetShowLogs: Boolean
            get() = sp.getBoolean("widgetShowLogs", false)
            set(v) = sp.edit().putBoolean("widgetShowLogs", v).apply()

        var widgetLogs: String?
            get() = sp.getString("widgetLogs", "")
            set(v) = sp.edit().putString("widgetLogs", v).apply()
    }
}
