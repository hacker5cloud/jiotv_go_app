package com.skylake.skytv.jgorunner.activities

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.video.VideoSize
import com.skylake.skytv.jgorunner.data.CustomChannelStore
import com.skylake.skytv.jgorunner.data.NumberedChannel
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.ui.tvhome.Channel
import com.skylake.skytv.jgorunner.utils.LogoLoader
import com.skylake.skytv.jgorunner.utils.Tls12SocketFactory

/**
 * KitKat-optimized (API 19), ultra-fast, low-latency adaptive video player activity.
 *
 * Features:
 * - Zero-copy hardware SurfaceView inside AspectRatioFrameLayout for smooth playback on KitKat
 * - Aggressive low-latency startup buffer (450ms bufferForPlaybackMs) + chunkless HLS preparation
 * - Adaptive bitrate streaming (auto-adjusts quality to network speed) or fixed quality override
 * - Persistent ExoPlayer instance across channel zapping (Ch Up/Down or Direct Number Entry)
 * - Mode 1: TV Remote D-Pad navigation (Up/Down zapping, Left quick channel drawer, Right/OK HUD)
 * - Mode 2: Direct Number Entry (type 0-9 on remote or on-screen keypad with live channel preview)
 */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        const val EXTRA_CHANNEL_NUMBER = "extra_channel_number"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_LOGO_URL = "extra_logo_url"
        const val EXTRA_USE_CUSTOM_LIST = "extra_use_custom_list"

        fun createIntent(
            context: Context,
            channel: NumberedChannel,
            useCustomList: Boolean
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL_ID, channel.channelId)
                putExtra(EXTRA_CHANNEL_NUMBER, channel.assignedNumber)
                putExtra(EXTRA_CHANNEL_NAME, channel.channelName)
                putExtra(EXTRA_STREAM_URL, channel.streamUrl)
                putExtra(EXTRA_LOGO_URL, channel.logoUrl)
                putExtra(EXTRA_USE_CUSTOM_LIST, useCustomList)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SkySharedPref

    // ExoPlayer components
    private var exoPlayer: ExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var httpDataSourceFactory: DefaultHttpDataSource.Factory

    // Views
    private lateinit var rootFrame: FrameLayout
    private lateinit var aspectFrame: AspectRatioFrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var bufferingSpinner: ProgressBar

    // Info HUD Banner (Bottom)
    private lateinit var hudContainer: LinearLayout
    private lateinit var hudNumberBadge: TextView
    private lateinit var hudLogo: ImageView
    private lateinit var hudChannelName: TextView
    private lateinit var hudStatusText: TextView
    private lateinit var hudQualityBtn: Button
    private lateinit var hudAssignNumBtn: Button
    private lateinit var hudNumpadBtn: Button
    private lateinit var hudAspectBtn: Button

    // Direct Number Entry OSD (Top-Right)
    private lateinit var numberOsdCard: LinearLayout
    private lateinit var numberOsdDigits: TextView
    private lateinit var numberOsdPreview: TextView

    // On-screen Numpad Overlay (for Mode 2 / touch / pointer users inside player)
    private lateinit var numpadOverlay: LinearLayout

    // Quick Channel Drawer (Left Sidebar on DPAD_LEFT)
    private lateinit var drawerContainer: LinearLayout
    private lateinit var drawerTitle: TextView
    private lateinit var drawerListView: ListView

    // State
    private var allRawChannels: List<Channel> = emptyList()
    private var activeChannelList: List<NumberedChannel> = emptyList()
    private var allNumberedChannels: List<NumberedChannel> = emptyList()
    private var currentChannel: NumberedChannel? = null
    private var currentRetryCount = 0

    // Number entry buffer
    private val numberBuffer = StringBuilder()

    private val hideHudRunnable = Runnable {
        hudContainer.visibility = View.GONE
    }

    private val commitNumberRunnable = Runnable {
        commitTypedChannelNumber()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        prefs = SkySharedPref.getInstance(this)
        buildUi()
        initPlayerEngine()
        loadChannelsAndStartPlayback(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            loadChannelsAndStartPlayback(intent)
        }
    }

    private fun hideSystemBars() {
        try {
            @Suppress("DEPRECATION")
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                @Suppress("DEPRECATION")
                flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
            window.decorView.systemUiVisibility = flags
        } catch (_: Exception) {
        }
    }

    private fun buildUi() {
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                if (drawerContainer.visibility == View.VISIBLE) {
                    drawerContainer.visibility = View.GONE
                } else if (numpadOverlay.visibility == View.VISIBLE) {
                    numpadOverlay.visibility = View.GONE
                } else {
                    toggleHud()
                }
            }
        }

        // 1. Hardware SurfaceView inside AspectRatioFrameLayout
        aspectFrame = AspectRatioFrameLayout(this).apply {
            resizeMode = getExoResizeMode(prefs.myPreferences.aspectRatioMode)
        }
        surfaceView = SurfaceView(this).apply {
            isFocusable = false
        }
        aspectFrame.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        rootFrame.addView(
            aspectFrame,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        // 2. Lightweight Buffering Indicator
        bufferingSpinner = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        rootFrame.addView(
            bufferingSpinner,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)
        )

        // 3. Top-Right Direct Number Entry OSD (YouTube Deep Black & Crisp White)
        numberOsdCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.parseColor("#F00A0A0A"), Color.WHITE, dp(2), dp(10))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            visibility = View.GONE
        }
        numberOsdDigits = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.DEFAULT_BOLD
            text = "#-"
        }
        numberOsdPreview = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = ""
        }
        numberOsdCard.addView(numberOsdDigits)
        numberOsdCard.addView(numberOsdPreview)
        rootFrame.addView(
            numberOsdCard,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(24)
                rightMargin = dp(24)
            }
        )

        // 4. Bottom Channel Info & Quick Controls HUD (YouTube Deep Black & Crisp White)
        hudContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.parseColor("#E60A0A0A"), Color.parseColor("#2E2E2E"), dp(1), dp(12))
            setPadding(dp(18), dp(14), dp(18), dp(14))
            visibility = View.GONE
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        hudNumberBadge = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBg(Color.parseColor("#FF0033"), Color.TRANSPARENT, 0, dp(6))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            text = "#1"
        }
        topRow.addView(hudNumberBadge)

        hudLogo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        topRow.addView(
            hudLogo,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                leftMargin = dp(12)
            }
        )

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
            }
        }
        hudChannelName = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = "Loading..."
        }
        hudStatusText = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = "Adaptive Stream • UP/DOWN: Change Ch • 0-9: Type Ch # • LEFT: Ch List"
        }
        titleCol.addView(hudChannelName)
        titleCol.addView(hudStatusText)
        topRow.addView(titleCol)
        hudContainer.addView(topRow)

        // Action row inside HUD
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }

        val prevBtn = createHudButton("◀ Prev Ch") { switchRelativeChannel(-1) }
        val nextBtn = createHudButton("Next Ch ▶") { switchRelativeChannel(1) }
        val listBtn = createHudButton("☰ Ch List") { openChannelDrawer() }
        hudNumpadBtn = createHudButton("🔢 Dial #") { showOnScreenNumpad() }
        hudAssignNumBtn = createHudButton("★ Assign #") {
            currentChannel?.let { showAssignNumberDialog(it) }
        }
        hudQualityBtn = createHudButton("⚡ Quality: ${formatQualityLabel(prefs.myPreferences.filterQX)}") {
            showQualityDialog()
        }
        hudAspectBtn = createHudButton("📐 Aspect") {
            cycleAspectRatio()
        }

        btnRow.addView(prevBtn)
        btnRow.addView(nextBtn)
        btnRow.addView(listBtn)
        btnRow.addView(hudNumpadBtn)
        btnRow.addView(hudAssignNumBtn)
        btnRow.addView(hudQualityBtn)
        btnRow.addView(hudAspectBtn)
        hudContainer.addView(btnRow)

        rootFrame.addView(
            hudContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(20)
                rightMargin = dp(20)
                bottomMargin = dp(18)
            }
        )

        // 5. Left Quick Channel Drawer (YouTube Deep Black & Crisp White)
        drawerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.parseColor("#F50A0A0A"), Color.parseColor("#2E2E2E"), dp(1), 0)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            visibility = View.GONE
        }
        drawerTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            text = "Quick Channel Switch"
            setPadding(dp(6), 0, dp(6), dp(8))
        }
        drawerListView = ListView(this).apply {
            dividerHeight = dp(4)
            isFocusable = true
            isFocusableInTouchMode = false
            setDrawSelectorOnTop(true)
            selector = roundedBg(Color.TRANSPARENT, Color.WHITE, dp(2), dp(8))
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                if (position in activeChannelList.indices) {
                    val target = activeChannelList[position]
                    drawerContainer.visibility = View.GONE
                    playChannel(target, showBanner = true)
                }
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    (adapter as? BaseAdapter)?.notifyDataSetChanged()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {
                    (adapter as? BaseAdapter)?.notifyDataSetChanged()
                }
            }
        }
        drawerContainer.addView(drawerTitle)
        drawerContainer.addView(
            drawerListView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        rootFrame.addView(
            drawerContainer,
            FrameLayout.LayoutParams(
                dp(300),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
        )

        // 6. On-Screen Keypad Overlay (for Mode 2 / Touch / Pointer remotes)
        numpadOverlay = buildOnScreenNumpadOverlay()
        rootFrame.addView(
            numpadOverlay,
            FrameLayout.LayoutParams(
                dp(300),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        setContentView(rootFrame)
    }

    private fun buildOnScreenNumpadOverlay(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.parseColor("#F5161616"), Color.WHITE, dp(2), dp(12))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
        }
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            text = "Type Channel Number"
        }
        card.addView(title)

        val rows = arrayOf(
            arrayOf("1", "2", "3"),
            arrayOf("4", "5", "6"),
            arrayOf("7", "8", "9"),
            arrayOf("CLR", "0", "GO")
        )
        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            for (key in row) {
                val btn = Button(this).apply {
                    text = key
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(createFocusableTextColors(Color.WHITE, Color.BLACK))
                    background = focusableDrawable(
                        normalFill = when (key) {
                            "GO" -> Color.parseColor("#FF0033")
                            "CLR" -> Color.parseColor("#383838")
                            else -> Color.parseColor("#272727")
                        },
                        normalStroke = Color.parseColor("#3F3F3F"),
                        focusFill = Color.WHITE,
                        focusStroke = Color.WHITE,
                        radiusDp = 8
                    )
                    layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                        leftMargin = dp(4)
                        rightMargin = dp(4)
                    }
                    setOnClickListener {
                        when (key) {
                            "CLR" -> {
                                mainHandler.removeCallbacks(commitNumberRunnable)
                                numberBuffer.clear()
                                numberOsdCard.visibility = View.GONE
                            }
                            "GO" -> {
                                numpadOverlay.visibility = View.GONE
                                mainHandler.removeCallbacks(commitNumberRunnable)
                                commitTypedChannelNumber()
                            }
                            else -> {
                                onDigitEntered(key[0])
                            }
                        }
                    }
                }
                rowLayout.addView(btn)
            }
            card.addView(rowLayout)
        }
        return card
    }

    private fun initPlayerEngine() {
        val bandwidthMeter = DefaultBandwidthMeter.Builder(this)
            .setResetOnNetworkTypeChange(true)
            .build()

        // Adaptive track selector tuned for fast startup and seamless resolution switching on KitKat
        val adaptiveTrackSelectionFactory = AdaptiveTrackSelection.Factory(
            /* minDurationForQualityIncreaseMs = */ 2000,
            /* maxDurationForQualityDecreaseMs = */ 6000,
            /* minDurationToRetainAfterDiscardMs = */ 3000,
            /* bandwidthFraction = */ 0.85f
        )

        trackSelector = DefaultTrackSelector(this, adaptiveTrackSelectionFactory).apply {
            parameters = buildUponParameters()
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setExceedVideoConstraintsIfNecessary(true)
                .setPreferredAudioLanguage("hi")
                .build()
        }

        // Low-latency load control: starts playback in 450ms!
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 6000,
                /* maxBufferMs = */ 18000,
                /* bufferForPlaybackMs = */ 450,
                /* bufferForPlaybackAfterRebufferMs = */ 1000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Tls12SocketFactory.JIO_USER_AGENT)
            .setConnectTimeoutMs(5000)
            .setReadTimeoutMs(8000)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to Tls12SocketFactory.JIO_USER_AGENT,
                    "Connection" to "keep-alive"
                )
            )

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                setVideoSurfaceView(surfaceView)
                playWhenReady = true
                addListener(playerListener)
            }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    bufferingSpinner.visibility = View.VISIBLE
                }
                Player.STATE_READY -> {
                    bufferingSpinner.visibility = View.GONE
                    currentRetryCount = 0
                    updateTrackStatusInHud()
                }
                Player.STATE_ENDED -> {
                    bufferingSpinner.visibility = View.GONE
                    // Live stream ended unexpectedly -> reconnect immediately
                    exoPlayer?.seekToDefaultPosition()
                    exoPlayer?.prepare()
                }
                Player.STATE_IDLE -> {
                    bufferingSpinner.visibility = View.GONE
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                val ratio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                aspectFrame.setAspectRatio(ratio)
                updateTrackStatusInHud()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            bufferingSpinner.visibility = View.GONE
            val ch = currentChannel ?: return
            val port = prefs.myPreferences.jtvGoServerPort

            if (currentRetryCount == 0 && prefs.myPreferences.filterQX != "auto") {
                // Fallback from fixed quality to adaptive master m3u8 immediately
                currentRetryCount++
                val adaptiveUrl = CustomChannelStore.buildPlaybackUrl(port, ch.channelId, "auto")
                startUrlInExoPlayer(adaptiveUrl)
                return
            }

            if (currentRetryCount < 3) {
                currentRetryCount++
                mainHandler.postDelayed({
                    val url = CustomChannelStore.buildPlaybackUrl(
                        port,
                        ch.channelId,
                        prefs.myPreferences.filterQX
                    )
                    startUrlInExoPlayer(url)
                }, 400L)
            } else {
                Toast.makeText(
                    this@PlayerActivity,
                    "Stream error on #${ch.assignedNumber} ${ch.channelName}. Check server/login.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateTrackStatusInHud() {
        val player = exoPlayer ?: return
        val vSize = player.videoSize
        val resLabel = if (vSize.height > 0) "${vSize.height}p" else "Live"
        val qMode = formatQualityLabel(prefs.myPreferences.filterQX)
        val customTag = if (currentChannel?.isCustomAssigned == true) "★ Custom #" else "Auto #"
        hudStatusText.text = "$qMode ($resLabel) • $customTag • UP/DOWN: Ch± • 0-9: Dial # • LEFT: List"
    }

    private fun loadChannelsAndStartPlayback(startIntent: Intent) {
        val port = prefs.myPreferences.jtvGoServerPort
        val channelId = startIntent.getStringExtra(EXTRA_CHANNEL_ID) ?: prefs.myPreferences.lastChannelId ?: "143"
        val channelNum = startIntent.getIntExtra(EXTRA_CHANNEL_NUMBER, prefs.myPreferences.lastChannelNumber)
        val channelName = startIntent.getStringExtra(EXTRA_CHANNEL_NAME) ?: prefs.myPreferences.lastChannelName ?: "Channel #$channelNum"
        val logoUrl = startIntent.getStringExtra(EXTRA_LOGO_URL) ?: prefs.myPreferences.lastLogoUrl ?: ""
        val streamUrl = startIntent.getStringExtra(EXTRA_STREAM_URL)
            ?: CustomChannelStore.buildPlaybackUrl(port, channelId, prefs.myPreferences.filterQX)
        val useCustomList = startIntent.getBooleanExtra(
            EXTRA_USE_CUSTOM_LIST,
            prefs.myPreferences.listMode == SkySharedPref.LIST_MODE_CUSTOM
        )

        val initialChannel = NumberedChannel(
            channelId = channelId,
            assignedNumber = channelNum,
            isCustomAssigned = CustomChannelStore.getCustomEntries(this).any { it.channelId == channelId },
            channelName = channelName,
            logoUrl = logoUrl,
            streamUrl = streamUrl,
            categoryId = 0,
            languageId = 0,
            isHD = false
        )

        // Start playback immediately without waiting for channel list parsing!
        playChannel(initialChannel, showBanner = true)

        // Load cached channels on background thread, and refresh if empty
        Thread({
            var raw = CustomChannelStore.loadCachedChannels(this)
            if (raw.isEmpty()) {
                raw = CustomChannelStore.fetchChannelsFromServer(this, port) ?: emptyList()
            }
            val allNum = CustomChannelStore.getAllNumberedChannels(this, raw, port)
            val customNum = CustomChannelStore.getCustomNumberedChannels(this, raw, port)
            val active = if (useCustomList && customNum.isNotEmpty()) customNum else allNum

            mainHandler.post {
                allRawChannels = raw
                allNumberedChannels = allNum
                activeChannelList = active
                // Update currentChannel metadata if matched
                allNum.firstOrNull { it.channelId == currentChannel?.channelId }?.let { matched ->
                    currentChannel = matched
                    updateHudViews(matched)
                }
                refreshDrawerList()
            }
        }, "Player-ChannelLoader").start()
    }

    private fun playChannel(channel: NumberedChannel, showBanner: Boolean) {
        currentChannel = channel
        currentRetryCount = 0

        val port = prefs.myPreferences.jtvGoServerPort
        val effectiveUrl = CustomChannelStore.buildPlaybackUrl(
            port = port,
            channelId = channel.channelId,
            qualitySetting = prefs.myPreferences.filterQX
        )

        // Save as last played channel for instant resume / Startup Launch
        prefs.myPreferences.apply {
            lastChannelId = channel.channelId
            lastChannelNumber = channel.assignedNumber
            lastChannelName = channel.channelName
            lastStreamUrl = effectiveUrl
            lastLogoUrl = channel.logoUrl
        }

        updateHudViews(channel)
        if (showBanner) {
            showHudTemporarily()
        }

        startUrlInExoPlayer(effectiveUrl)
    }

    private fun startUrlInExoPlayer(url: String) {
        val player = exoPlayer ?: return
        bufferingSpinner.visibility = View.VISIBLE

        val uri = Uri.parse(url)
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(1.04f)
                    .setMinPlaybackSpeed(0.96f)
                    .build()
            )
            .build()

        val mediaSource: MediaSource = if (url.contains(".mpd", ignoreCase = true) || url.contains("/mpd/", ignoreCase = true)) {
            DashMediaSource.Factory(httpDataSourceFactory)
                .createMediaSource(mediaItem)
        } else {
            // Chunkless preparation skips extra segment probing for faster HLS startup on KitKat
            HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
        }

        player.setMediaSource(mediaSource, /* resetPosition = */ true)
        player.prepare()
        player.playWhenReady = true
    }

    private fun updateHudViews(channel: NumberedChannel) {
        val prefix = if (channel.isCustomAssigned) "★ #" else "#"
        hudNumberBadge.text = "$prefix${channel.assignedNumber}"
        hudChannelName.text = channel.channelName
        hudQualityBtn.text = "⚡ Quality: ${formatQualityLabel(prefs.myPreferences.filterQX)}"
        LogoLoader.load(this, channel.logoUrl, hudLogo)
        updateTrackStatusInHud()
    }

    private fun switchRelativeChannel(delta: Int) {
        val list = if (activeChannelList.isNotEmpty()) activeChannelList else allNumberedChannels
        if (list.isEmpty()) return

        val cur = currentChannel
        val curIdx = list.indexOfFirst { it.channelId == cur?.channelId }
        val nextIdx = if (curIdx == -1) {
            0
        } else {
            (curIdx + delta + list.size) % list.size
        }
        playChannel(list[nextIdx], showBanner = true)
    }

    private fun onDigitEntered(digit: Char) {
        if (numberBuffer.length >= 4) {
            numberBuffer.clear()
        }
        numberBuffer.append(digit)
        val typedNum = numberBuffer.toString().toIntOrNull() ?: return

        val port = prefs.myPreferences.jtvGoServerPort
        val matched = CustomChannelStore.findChannelByNumber(this, typedNum, allRawChannels, port)

        numberOsdDigits.text = "#$typedNum"
        numberOsdPreview.text = if (matched != null) {
            val badge = if (matched.isCustomAssigned) "★ " else ""
            "$badge${matched.channelName}"
        } else {
            "Channel #$typedNum not assigned"
        }
        numberOsdCard.visibility = View.VISIBLE

        mainHandler.removeCallbacks(commitNumberRunnable)
        mainHandler.postDelayed(commitNumberRunnable, 1100L)
    }

    private fun commitTypedChannelNumber() {
        val str = numberBuffer.toString()
        numberBuffer.clear()
        numberOsdCard.visibility = View.GONE
        val typedNum = str.toIntOrNull() ?: return

        val port = prefs.myPreferences.jtvGoServerPort
        val matched = CustomChannelStore.findChannelByNumber(this, typedNum, allRawChannels, port)
        if (matched != null) {
            playChannel(matched, showBanner = true)
        } else {
            Toast.makeText(
                this,
                "No channel at #$typedNum. Use 'Assign #' to set a channel to #$typedNum!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode

            // 1. Direct Number Entry (0-9 on remote or numpad) works at all times!
            val digit = keyCodeToDigit(keyCode)
            if (digit != null && drawerContainer.visibility != View.VISIBLE) {
                onDigitEntered(digit)
                return true
            }

            // If user is currently typing a channel number and presses Enter/OK, commit immediately!
            if (numberBuffer.isNotEmpty() &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                mainHandler.removeCallbacks(commitNumberRunnable)
                commitTypedChannelNumber()
                return true
            }

            // If user presses DEL / BACK while typing a number, cancel number entry
            if (numberBuffer.isNotEmpty() &&
                (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_BACK)
            ) {
                mainHandler.removeCallbacks(commitNumberRunnable)
                numberBuffer.clear()
                numberOsdCard.visibility = View.GONE
                return true
            }

            // Handle Back when overlays are open
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (numpadOverlay.visibility == View.VISIBLE) {
                    numpadOverlay.visibility = View.GONE
                    return true
                }
                if (drawerContainer.visibility == View.VISIBLE) {
                    drawerContainer.visibility = View.GONE
                    return true
                }
                if (hudContainer.visibility == View.VISIBLE) {
                    hudContainer.visibility = View.GONE
                    return true
                }
            }

            // When neither drawer nor numpad is open, handle D-Pad TV Remote Navigation
            if (drawerContainer.visibility != View.VISIBLE &&
                numpadOverlay.visibility != View.VISIBLE &&
                hudContainer.visibility != View.VISIBLE
            ) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                        switchRelativeChannel(1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                        switchRelativeChannel(-1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        openChannelDrawer()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                        showHudTemporarily()
                        hudQualityBtn.requestFocus()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        showHudTemporarily()
                        hudAssignNumBtn.requestFocus()
                        return true
                    }
                }
            } else {
                // Reset HUD auto-hide timer when navigating HUD buttons
                if (hudContainer.visibility == View.VISIBLE) {
                    mainHandler.removeCallbacks(hideHudRunnable)
                    mainHandler.postDelayed(hideHudRunnable, 6000L)
                }
                //Still allow dedicated CH+/CH- buttons even if HUD is visible
                if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
                    switchRelativeChannel(1)
                    return true
                } else if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                    switchRelativeChannel(-1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun keyCodeToDigit(keyCode: Int): Char? {
        return when (keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> '0'
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> '1'
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> '2'
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> '3'
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> '4'
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> '5'
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> '6'
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> '7'
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> '8'
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> '9'
            else -> null
        }
    }

    private fun toggleHud() {
        if (hudContainer.visibility == View.VISIBLE) {
            mainHandler.removeCallbacks(hideHudRunnable)
            hudContainer.visibility = View.GONE
        } else {
            showHudTemporarily()
        }
    }

    private fun showHudTemporarily() {
        hudContainer.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideHudRunnable)
        mainHandler.postDelayed(hideHudRunnable, 5000L)
    }

    private fun showOnScreenNumpad() {
        hudContainer.visibility = View.GONE
        drawerContainer.visibility = View.GONE
        numpadOverlay.visibility = View.VISIBLE
    }

    private fun openChannelDrawer() {
        hudContainer.visibility = View.GONE
        numpadOverlay.visibility = View.GONE
        refreshDrawerList()
        drawerContainer.visibility = View.VISIBLE
        val curIdx = activeChannelList.indexOfFirst { it.channelId == currentChannel?.channelId }
        if (curIdx >= 0) {
            drawerListView.setSelection(curIdx)
        }
        drawerListView.requestFocus()
    }

    private fun refreshDrawerList() {
        val list = if (activeChannelList.isNotEmpty()) activeChannelList else allNumberedChannels
        drawerTitle.text = if (prefs.myPreferences.listMode == SkySharedPref.LIST_MODE_CUSTOM &&
            CustomChannelStore.getCustomEntries(this).isNotEmpty()
        ) {
            "★ My Custom Channels (${list.size})"
        } else {
            "All Channels (${list.size})"
        }
        drawerListView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = list.size
            override fun getItem(position: Int): Any = list[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@PlayerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    val numTv = TextView(this@PlayerActivity).apply {
                        id = 101
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        minWidth = dp(48)
                        setPadding(dp(6), dp(2), dp(6), dp(2))
                    }
                    val nameTv = TextView(this@PlayerActivity).apply {
                        id = 102
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            leftMargin = dp(10)
                        }
                    }
                    addView(numTv)
                    addView(nameTv)
                }

                val item = list[position]
                val numTv = row.findViewById<TextView>(101)
                val nameTv = row.findViewById<TextView>(102)
                val isPlaying = item.channelId == currentChannel?.channelId
                val isFocusedRow = drawerListView.hasFocus() && position == drawerListView.selectedItemPosition

                row.background = when {
                    isFocusedRow -> roundedBg(Color.WHITE, Color.WHITE, dp(2), dp(8))
                    isPlaying -> roundedBg(Color.parseColor("#272727"), Color.parseColor("#FF0033"), dp(1), dp(8))
                    else -> roundedBg(Color.parseColor("#161616"), Color.TRANSPARENT, 0, dp(8))
                }

                numTv.text = if (item.isCustomAssigned) "★#${item.assignedNumber}" else "#${item.assignedNumber}"
                numTv.setTextColor(Color.WHITE)
                numTv.background = roundedBg(
                    if (item.isCustomAssigned || isPlaying) Color.parseColor("#FF0033") else Color.parseColor("#272727"),
                    Color.TRANSPARENT,
                    0,
                    dp(5)
                )
                nameTv.text = if (isFocusedRow) "▶ ${item.channelName}" else item.channelName
                nameTv.setTextColor(if (isFocusedRow) Color.BLACK else Color.WHITE)
                return row
            }
        }
    }

    private fun showQualityDialog() {
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#D9000000"))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.parseColor("#F5161616"), Color.WHITE, dp(2), dp(14))
            setPadding(dp(22), dp(18), dp(22), dp(18))
            isClickable = true
        }
        val title = TextView(this).apply {
            text = "⚡ Stream Quality Mode"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }
        card.addView(title)

        val options = arrayOf(
            "⚡ Adaptive (Auto — Fast & Smooth)" to "auto",
            "💎 High (Maximum HD Quality)" to "high",
            "📺 Medium (Balanced SD/HD)" to "medium",
            "🚀 Low (Data Saver / Instant Start)" to "low"
        )
        val currentVal = prefs.myPreferences.filterQX ?: "auto"

        for ((label, value) in options) {
            val active = currentVal.equals(value, ignoreCase = true)
            val btn = Button(this).apply {
                text = if (active) "✓  $label" else "    $label"
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(createFocusableTextColors(Color.WHITE, Color.BLACK))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                background = focusableDrawable(
                    normalFill = if (active) Color.parseColor("#272727") else Color.parseColor("#161616"),
                    normalStroke = if (active) Color.parseColor("#FF0033") else Color.parseColor("#2E2E2E"),
                    focusFill = Color.WHITE,
                    focusStroke = Color.WHITE,
                    radiusDp = 8
                )
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener {
                    prefs.myPreferences.filterQX = value
                    hudQualityBtn.text = "⚡ Quality: ${formatQualityLabel(value)}"
                    dialog.dismiss()
                    currentChannel?.let { playChannel(it, showBanner = true) }
                }
            }
            card.addView(
                btn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            )
        }

        scrim.addView(
            card,
            FrameLayout.LayoutParams(dp(400), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        dialog.setContentView(scrim)
        dialog.show()
    }

    private fun showAssignNumberDialog(channel: NumberedChannel) {
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#D9000000"))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.parseColor("#F5161616"), Color.WHITE, dp(2), dp(14))
            setPadding(dp(22), dp(18), dp(22), dp(18))
            isClickable = true
        }
        val title = TextView(this).apply {
            text = "★ Assign Channel # & Startup"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val info = TextView(this).apply {
            text = "Assign a custom number (1–9999) for '${channel.channelName}' in your Custom Channel List:"
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(10))
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(channel.assignedNumber.toString())
            setSelection(text.length)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = focusableDrawable(
                normalFill = Color.parseColor("#0F0F0F"),
                normalStroke = Color.parseColor("#2E2E2E"),
                focusFill = Color.parseColor("#161616"),
                focusStroke = Color.WHITE,
                radiusDp = 8
            )
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        card.addView(title)
        card.addView(info)
        card.addView(
            input,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        )

        val saveBtn = createHudButton("★ Save # to My List") {
            val desired = input.text.toString().toIntOrNull()
            if (desired != null && desired in 1..9999) {
                val assigned = CustomChannelStore.assignCustomNumber(this, channel, desired)
                val port = prefs.myPreferences.jtvGoServerPort
                allNumberedChannels = CustomChannelStore.getAllNumberedChannels(this, allRawChannels, port)
                activeChannelList = if (prefs.myPreferences.listMode == SkySharedPref.LIST_MODE_CUSTOM) {
                    CustomChannelStore.getCustomNumberedChannels(this, allRawChannels, port)
                } else {
                    allNumberedChannels
                }
                val updated = channel.copy(assignedNumber = assigned, isCustomAssigned = true)
                currentChannel = updated
                updateHudViews(updated)
                dialog.dismiss()
                Toast.makeText(
                    this,
                    "Saved! '${channel.channelName}' is now Channel #$assigned",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val startupBtn = createHudButton("🚀 Set as Startup Channel") {
            val desired = input.text.toString().toIntOrNull() ?: channel.assignedNumber
            val assigned = CustomChannelStore.assignCustomNumber(this, channel, desired)
            prefs.myPreferences.apply {
                startupLaunchMode = SkySharedPref.STARTUP_SPECIFIC_CHANNEL
                startupChannelNumber = assigned
                startupChannelId = channel.channelId
                startupChannelName = channel.channelName
            }
            dialog.dismiss()
            Toast.makeText(
                this,
                "App will now launch directly into #$assigned (${channel.channelName})!",
                Toast.LENGTH_LONG
            ).show()
        }

        val cancelBtn = createHudButton("Cancel") {
            dialog.dismiss()
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(startupBtn)
        btnRow.addView(saveBtn)
        card.addView(btnRow)

        scrim.addView(
            card,
            FrameLayout.LayoutParams(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        dialog.setContentView(scrim)
        dialog.show()
    }

    private fun cycleAspectRatio() {
        val next = (prefs.myPreferences.aspectRatioMode + 1) % 3
        prefs.myPreferences.aspectRatioMode = next
        aspectFrame.resizeMode = getExoResizeMode(next)
        val label = when (next) {
            1 -> "Fill (Stretch)"
            2 -> "Zoom"
            else -> "Fit (Original)"
        }
        Toast.makeText(this, "Aspect Ratio: $label", Toast.LENGTH_SHORT).show()
    }

    private fun getExoResizeMode(mode: Int): Int {
        return when (mode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun formatQualityLabel(q: String?): String {
        return when (q?.lowercase()) {
            "low" -> "Low"
            "medium" -> "Med"
            "high" -> "High"
            else -> "Adaptive"
        }
    }

    private fun createFocusableTextColors(normalColor: Int, focusedColor: Int = Color.BLACK): ColorStateList {
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf()
            ),
            intArrayOf(
                focusedColor,
                focusedColor,
                normalColor
            )
        )
    }

    private fun createHudButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(createFocusableTextColors(Color.WHITE, Color.BLACK))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = createFocusableButtonDrawable()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
            ).apply {
                rightMargin = dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createFocusableButtonDrawable(): android.graphics.drawable.StateListDrawable {
        val focused = roundedBg(Color.WHITE, Color.WHITE, dp(2), dp(8))
        val normal = roundedBg(Color.parseColor("#272727"), Color.parseColor("#3F3F3F"), dp(1), dp(8))
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun focusableDrawable(
        normalFill: Int,
        normalStroke: Int,
        focusFill: Int,
        focusStroke: Int,
        radiusDp: Int
    ): android.graphics.drawable.StateListDrawable {
        val focused = roundedBg(focusFill, focusStroke, dp(2), dp(radiusDp))
        val normal = roundedBg(normalFill, normalStroke, dp(1), dp(radiusDp))
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun roundedBg(fillColor: Int, strokeColor: Int, strokeWidth: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fillColor)
            if (strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        exoPlayer?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) {
            releasePlayer()
        } else {
            exoPlayer?.playWhenReady = false
        }
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }

    private fun releasePlayer() {
        exoPlayer?.let { player ->
            player.removeListener(playerListener)
            player.clearVideoSurfaceView(surfaceView)
            player.release()
        }
        exoPlayer = null
    }
}
