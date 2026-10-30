package com.skylake.skytv.jgorunner.ui.tvhome

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class ChannelResponse(
    @SerializedName("code")
    val code: Int = -1,
    @SerializedName("message")
    val message: String = "",
    @SerializedName("result")
    val result: List<Channel> = emptyList()
)

@Keep
data class Channel(
    @SerializedName("channel_id")
    val channel_id: String = "",
    @SerializedName("channel_name")
    val channel_name: String = "",
    @SerializedName("channel_url")
    val channel_url: String = "",
    @SerializedName("key_url")
    val key_url: String = "",
    @SerializedName("logoUrl")
    val logoUrl: String = "",
    @SerializedName("channelCategoryId")
    val channelCategoryId: Int = 0,
    @SerializedName("channelLanguageId")
    val channelLanguageId: Int = 0,
    @SerializedName("isHD")
    val isHD: Boolean = false,
    @SerializedName("requiresSubscription")
    val requiresSubscription: Boolean = false
)

@Keep
data class EpgResponse(
    @SerializedName("epg")
    val epg: List<EpgProgram> = emptyList()
)

@Keep
data class EpgProgram(
    @SerializedName("srno")
    val srno: Long = 0L,
    @SerializedName("showId")
    val showId: String = "",
    @SerializedName("showtime")
    val showtime: String = "",
    @SerializedName("showname")
    val showname: String = "",
    @SerializedName("description")
    val description: String = "",
    @SerializedName("duration")
    val duration: Int = 0,
    @SerializedName("endtime")
    val endtime: String = "",
    @SerializedName("channel_name")
    val channel_name: String = "",
    @SerializedName("episodeThumbnail")
    val episodeThumbnail: String = "",
    @SerializedName("episodePoster")
    val episodePoster: String = "",
    @SerializedName("startEpoch")
    val startEpoch: Long = 0L,
    @SerializedName("endEpoch")
    val endEpoch: Long = 0L
)

@Keep
data class M3UChannelExp(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("url")
    val url: String = "",
    @SerializedName("logo")
    val logo: String? = null,
    @SerializedName("category")
    val category: String? = null
)

val CategoryMap: Map<Int, String> = linkedMapOf(
    0 to "All Categories",
    5 to "Entertainment",
    6 to "Movies",
    7 to "Kids",
    8 to "Sports",
    9 to "Lifestyle",
    10 to "Infotainment",
    12 to "News",
    13 to "Music",
    15 to "Devotional",
    16 to "Business",
    17 to "Educational",
    18 to "Shopping",
    19 to "JioDarshan"
)

val LanguageMap: Map<Int, String> = linkedMapOf(
    0 to "All Languages",
    1 to "Hindi",
    6 to "English",
    2 to "Marathi",
    3 to "Punjabi",
    4 to "Urdu",
    5 to "Bengali",
    7 to "Malayalam",
    8 to "Tamil",
    9 to "Gujarati",
    10 to "Odia",
    11 to "Telugu",
    12 to "Bhojpuri",
    13 to "Assamese",
    14 to "Kannada",
    15 to "French",
    18 to "Extra"
)
