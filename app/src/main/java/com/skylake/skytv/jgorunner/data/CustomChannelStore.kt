package com.skylake.skytv.jgorunner.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.skylake.skytv.jgorunner.ui.tvhome.Channel
import com.skylake.skytv.jgorunner.ui.tvhome.ChannelResponse
import com.skylake.skytv.jgorunner.utils.Tls12SocketFactory
import okhttp3.Request
import java.io.File

/**
 * Represents a channel with its assigned number (either user-assigned custom number
 * or default sequential number) and whether it belongs to the user's Custom Channel List.
 */
data class NumberedChannel(
    val channelId: String,
    val assignedNumber: Int,
    val isCustomAssigned: Boolean,
    val channelName: String,
    val logoUrl: String,
    val streamUrl: String,
    val categoryId: Int,
    val languageId: Int,
    val isHD: Boolean
)

/**
 * Persisted entry in the user's Custom Channel List.
 */
data class CustomChannelEntry(
    val channelId: String,
    var customNumber: Int,
    val channelName: String,
    val logoUrl: String,
    val streamUrl: String,
    val categoryId: Int = 0,
    val languageId: Int = 0,
    val isHD: Boolean = false
)

/**
 * Manages:
 * 1. The user's Custom Channel List & Custom Number Assignments (1..9999)
 * 2. Fast local caching of the JioTV Go channel list for instant startup on KitKat
 * 3. Instant lookup of any channel by number (prioritizing user-assigned numbers, then sequential numbers)
 */
object CustomChannelStore {

    private const val PREF_NAME = "CustomChannelStorePref"
    private const val KEY_CUSTOM_CHANNELS = "custom_channels_v1"
    private const val CACHE_FILE_NAME = "channels_cache_v1.json"

    private val gson = Gson()
    private var memoryCustomEntries: MutableList<CustomChannelEntry>? = null
    private var memoryAllChannels: List<Channel>? = null

    @Synchronized
    fun getCustomEntries(context: Context): MutableList<CustomChannelEntry> {
        memoryCustomEntries?.let { return it }
        val sp = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_CUSTOM_CHANNELS, null)
        val loaded: MutableList<CustomChannelEntry> = if (!raw.isNullOrBlank()) {
            try {
                val type = object : TypeToken<MutableList<CustomChannelEntry>>() {}.type
                gson.fromJson<MutableList<CustomChannelEntry>>(raw, type) ?: mutableListOf()
            } catch (_: Exception) {
                mutableListOf()
            }
        } else {
            mutableListOf()
        }
        loaded.sortBy { it.customNumber }
        memoryCustomEntries = loaded
        return loaded
    }

    @Synchronized
    private fun saveCustomEntries(context: Context, entries: MutableList<CustomChannelEntry>) {
        entries.sortBy { it.customNumber }
        memoryCustomEntries = entries
        val sp = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_CUSTOM_CHANNELS, gson.toJson(entries)).apply()
    }

    /**
     * Assigns a custom channel number (e.g. 1..9999) to a channel and includes it in the user's
     * Custom Channel List.
     * If another custom channel already uses [desiredNumber], it swaps numbers (if this channel
     * already had a custom number) or shifts conflicting custom channels up by +1 so every
     * custom channel has a unique memorable number.
     */
    @Synchronized
    fun assignCustomNumber(
        context: Context,
        channel: NumberedChannel,
        desiredNumber: Int
    ): Int {
        val safeNumber = desiredNumber.coerceIn(1, 9999)
        val entries = getCustomEntries(context)

        val existingForChannel = entries.firstOrNull { it.channelId == channel.channelId }
        val previousNumber = existingForChannel?.customNumber

        val conflicting = entries.firstOrNull {
            it.customNumber == safeNumber && it.channelId != channel.channelId
        }

        if (conflicting != null) {
            if (previousNumber != null && previousNumber != safeNumber) {
                // Clean swap between the two channels
                conflicting.customNumber = previousNumber
            } else {
                // Shift conflicting channel (and any consecutive collisions) up by 1
                var numToShift = safeNumber
                while (true) {
                    val hit = entries.firstOrNull {
                        it.customNumber == numToShift && it.channelId != channel.channelId
                    } ?: break
                    numToShift = (numToShift + 1).coerceAtMost(9999)
                    hit.customNumber = numToShift
                }
            }
        }

        if (existingForChannel != null) {
            existingForChannel.customNumber = safeNumber
        } else {
            entries.add(
                CustomChannelEntry(
                    channelId = channel.channelId,
                    customNumber = safeNumber,
                    channelName = channel.channelName,
                    logoUrl = channel.logoUrl,
                    streamUrl = channel.streamUrl,
                    categoryId = channel.categoryId,
                    languageId = channel.languageId,
                    isHD = channel.isHD
                )
            )
        }

        saveCustomEntries(context, entries)
        return safeNumber
    }

    /**
     * Returns the next available custom number (1, 2, 3...) not yet used in the Custom Channel List.
     */
    @Synchronized
    fun getNextAvailableCustomNumber(context: Context): Int {
        val used = getCustomEntries(context).map { it.customNumber }.toHashSet()
        var candidate = 1
        while (used.contains(candidate) && candidate < 9999) {
            candidate++
        }
        return candidate
    }

    /**
     * Removes a channel from the user's Custom Channel List.
     */
    @Synchronized
    fun removeCustomChannel(context: Context, channelId: String) {
        val entries = getCustomEntries(context)
        val removed = entries.removeAll { it.channelId == channelId }
        if (removed) {
            saveCustomEntries(context, entries)
        }
    }

    /**
     * Clears all custom channels.
     */
    @Synchronized
    fun clearAllCustomChannels(context: Context) {
        saveCustomEntries(context, mutableListOf())
    }

    /**
     * Auto-renumbers all channels in the user's Custom Channel List sequentially (1, 2, 3... N)
     * in their current order.
     */
    @Synchronized
    fun renumberCustomListSequentially(context: Context) {
        val entries = getCustomEntries(context)
        entries.sortBy { it.customNumber }
        entries.forEachIndexed { index, entry ->
            entry.customNumber = index + 1
        }
        saveCustomEntries(context, entries)
    }

    /**
     * Moves a custom channel up (`delta = -1`) or down (`delta = +1`) in the Custom Channel List
     * by swapping its `customNumber` with its neighbor.
     */
    @Synchronized
    fun moveCustomChannel(context: Context, channelId: String, delta: Int) {
        val entries = getCustomEntries(context)
        entries.sortBy { it.customNumber }
        val index = entries.indexOfFirst { it.channelId == channelId }
        if (index == -1) return
        val targetIndex = index + delta
        if (targetIndex !in entries.indices) return
        val current = entries[index]
        val neighbor = entries[targetIndex]
        val tmp = current.customNumber
        current.customNumber = neighbor.customNumber
        neighbor.customNumber = tmp
        saveCustomEntries(context, entries)
    }

    /**
     * Builds the list of [NumberedChannel] for the user's Custom Channel List, sorted by
     * their assigned custom number (#1, #2, #3...).
     * Automatically refreshes stream/logo URLs against the current server port.
     */
    @Synchronized
    fun getCustomNumberedChannels(
        context: Context,
        allChannels: List<Channel>,
        port: Int
    ): List<NumberedChannel> {
        val entries = getCustomEntries(context)
        val byId = allChannels.associateBy { it.channel_id }
        val baseUrl = "http://127.0.0.1:$port"

        return entries.map { entry ->
            val live = byId[entry.channelId]
            val logoPath = live?.logoUrl ?: entry.logoUrl
            val fullLogo = if (logoPath.startsWith("http")) {
                logoPath.replace(Regex("http://(localhost|127\\.0\\.0\\.1):\\d+"), baseUrl)
            } else {
                "$baseUrl/jtvimage/${logoPath.removePrefix("/")}"
            }
            val streamUrl = "$baseUrl/live/${entry.channelId}.m3u8"
            NumberedChannel(
                channelId = entry.channelId,
                assignedNumber = entry.customNumber,
                isCustomAssigned = true,
                channelName = live?.channel_name ?: entry.channelName,
                logoUrl = fullLogo,
                streamUrl = streamUrl,
                categoryId = live?.channelCategoryId ?: entry.categoryId,
                languageId = live?.channelLanguageId ?: entry.languageId,
                isHD = live?.isHD ?: entry.isHD
            )
        }.sortedBy { it.assignedNumber }
    }

    /**
     * Builds the full list of [NumberedChannel] for ALL channels.
     * - Channels that are in the user's Custom Channel List get their exact `customNumber`
     *   and `isCustomAssigned = true`.
     * - Remaining channels get unique sequential numbers that do not collide with any custom number.
     * - Custom-numbered channels appear first (sorted by customNumber), followed by the rest.
     */
    @Synchronized
    fun getAllNumberedChannels(
        context: Context,
        allChannels: List<Channel>,
        port: Int
    ): List<NumberedChannel> {
        val entries = getCustomEntries(context)
        val customById = entries.associateBy { it.channelId }
        val usedNumbers = entries.map { it.customNumber }.toHashSet()
        val baseUrl = "http://127.0.0.1:$port"

        val customList = mutableListOf<NumberedChannel>()
        val regularList = mutableListOf<NumberedChannel>()

        var nextAutoNumber = 1
        fun nextFreeNumber(): Int {
            while (usedNumbers.contains(nextAutoNumber)) {
                nextAutoNumber++
            }
            val assigned = nextAutoNumber
            usedNumbers.add(assigned)
            nextAutoNumber++
            return assigned
        }

        for (ch in allChannels) {
            val logoPath = ch.logoUrl
            val fullLogo = if (logoPath.startsWith("http")) {
                logoPath.replace(Regex("http://(localhost|127\\.0\\.0\\.1):\\d+"), baseUrl)
            } else {
                "$baseUrl/jtvimage/${logoPath.removePrefix("/")}"
            }
            val streamUrl = "$baseUrl/live/${ch.channel_id}.m3u8"
            val custom = customById[ch.channel_id]
            if (custom != null) {
                customList.add(
                    NumberedChannel(
                        channelId = ch.channel_id,
                        assignedNumber = custom.customNumber,
                        isCustomAssigned = true,
                        channelName = ch.channel_name,
                        logoUrl = fullLogo,
                        streamUrl = streamUrl,
                        categoryId = ch.channelCategoryId,
                        languageId = ch.channelLanguageId,
                        isHD = ch.isHD
                    )
                )
            } else {
                regularList.add(
                    NumberedChannel(
                        channelId = ch.channel_id,
                        assignedNumber = nextFreeNumber(),
                        isCustomAssigned = false,
                        channelName = ch.channel_name,
                        logoUrl = fullLogo,
                        streamUrl = streamUrl,
                        categoryId = ch.channelCategoryId,
                        languageId = ch.channelLanguageId,
                        isHD = ch.isHD
                    )
                )
            }
        }

        // Also include any custom channels that might not be in allChannels yet (e.g. before fetch)
        if (allChannels.isEmpty() && entries.isNotEmpty()) {
            return getCustomNumberedChannels(context, emptyList(), port)
        }

        customList.sortBy { it.assignedNumber }
        return customList + regularList
    }

    /**
     * Finds a channel by its assigned number!
     * 1. First checks if the user assigned [number] in their Custom Channel List.
     * 2. Next checks the full numbered channel list for [number].
     * 3. Next checks if [number] matches a channel_id directly.
     */
    @Synchronized
    fun findChannelByNumber(
        context: Context,
        number: Int,
        allChannels: List<Channel>,
        port: Int
    ): NumberedChannel? {
        // 1. Check Custom Channel List first
        val customChannels = getCustomNumberedChannels(context, allChannels, port)
        customChannels.firstOrNull { it.assignedNumber == number }?.let { return it }

        // 2. Check all numbered channels
        val allNumbered = getAllNumberedChannels(context, allChannels, port)
        allNumbered.firstOrNull { it.assignedNumber == number }?.let { return it }

        // 3. Check 1-based index in allChannels
        if (number in 1..allChannels.size) {
            val ch = allChannels[number - 1]
            val baseUrl = "http://127.0.0.1:$port"
            return NumberedChannel(
                channelId = ch.channel_id,
                assignedNumber = number,
                isCustomAssigned = false,
                channelName = ch.channel_name,
                logoUrl = "$baseUrl/jtvimage/${ch.logoUrl.removePrefix("/")}",
                streamUrl = "$baseUrl/live/${ch.channel_id}.m3u8",
                categoryId = ch.channelCategoryId,
                languageId = ch.channelLanguageId,
                isHD = ch.isHD
            )
        }
        return null
    }

    /**
     * Builds the adaptive or fixed-quality playback URL for a channel ID.
     * By default ("auto"), returns `/live/{id}.m3u8` which serves the multi-variant adaptive
     * master playlist from jiotv_go so ExoPlayer can start playback immediately and adapt smoothly.
     */
    fun buildPlaybackUrl(port: Int, channelId: String, qualitySetting: String?): String {
        val cleanId = channelId.trim()
        val q = qualitySetting?.lowercase()?.trim() ?: "auto"
        return when (q) {
            "low", "medium", "high" -> "http://127.0.0.1:$port/live/$q/$cleanId.m3u8"
            else -> "http://127.0.0.1:$port/live/$cleanId.m3u8"
        }
    }

    /**
     * Fetches the live channel list from `http://127.0.0.1:$port/channels` and caches it to disk.
     */
    fun fetchChannelsFromServer(context: Context, port: Int): List<Channel>? {
        return try {
            val req = Request.Builder()
                .url("http://127.0.0.1:$port/channels")
                .get()
                .build()
            val resp = Tls12SocketFactory.apiHttpClient.newCall(req).execute()
            resp.use { r ->
                if (!r.isSuccessful) return null
                val body = r.body()?.string() ?: return null
                val parsed = gson.fromJson(body, ChannelResponse::class.java)
                val list = parsed?.result
                if (!list.isNullOrEmpty()) {
                    synchronized(this) {
                        memoryAllChannels = list
                    }
                    try {
                        File(context.applicationContext.filesDir, CACHE_FILE_NAME).writeText(body)
                    } catch (_: Exception) {
                    }
                }
                list
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Loads cached channels from memory or disk (`channels_cache_v1.json`) for instant cold start.
     */
    fun loadCachedChannels(context: Context): List<Channel> {
        synchronized(this) {
            memoryAllChannels?.let { if (it.isNotEmpty()) return it }
        }
        return try {
            val file = File(context.applicationContext.filesDir, CACHE_FILE_NAME)
            if (file.exists() && file.length() > 0) {
                val parsed = gson.fromJson(file.readText(), ChannelResponse::class.java)
                val list = parsed?.result ?: emptyList()
                synchronized(this) {
                    memoryAllChannels = list
                }
                list
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
