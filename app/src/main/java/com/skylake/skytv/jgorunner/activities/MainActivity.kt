package com.skylake.skytv.jgorunner.activities

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.skylake.skytv.jgorunner.core.data.JTVConfigurationManager
import com.skylake.skytv.jgorunner.core.update.BinaryUpdater
import com.skylake.skytv.jgorunner.data.CustomChannelStore
import com.skylake.skytv.jgorunner.data.NumberedChannel
import com.skylake.skytv.jgorunner.data.SkySharedPref
import com.skylake.skytv.jgorunner.services.BinaryService
import com.skylake.skytv.jgorunner.ui.tvhome.CategoryMap
import com.skylake.skytv.jgorunner.ui.tvhome.Channel
import com.skylake.skytv.jgorunner.ui.tvhome.LanguageMap
import com.skylake.skytv.jgorunner.utils.LogCollector
import com.skylake.skytv.jgorunner.utils.LogoLoader
import com.skylake.skytv.jgorunner.utils.Tls12SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.File

/**
 * Modern, Clean Multi-Page Android TV UI optimized exclusively for Android 4.4 KitKat (API 19).
 *
 * Architecture:
 * - Persistent Left TV Sidebar Rail with 5 dedicated full-screen pages:
 *   1. [PAGE_LIVE_TV]    ("📺 Live TV")      : Mode 1 — Clean TV Remote Channel Grid + Category Pill Bar
 *   2. [PAGE_KEYPAD]     ("🔢 Number Pad")   : Mode 2 — Direct Number Entry Dialer + Speed-Dial Directory
 *   3. [PAGE_MY_LIST]    ("⭐ My List & #")  : Full-screen Custom Channel Lineup & Number Studio
 *   4. [PAGE_STARTUP]    ("🚀 Startup & TV") : Direct Channel Launch on Startup, Quality & Language Settings
 *   5. [PAGE_ACCOUNT]    ("⚙ Account")       : JioTV OTP Login & Local Server Engine Dashboard
 * - Custom Glass TV Modal Overlays (no dated system AlertDialogs or Spinners)
 * - Global 0-9 Remote Key Entry from anywhere to jump straight to a channel by number.
 */
class MainActivity : Activity() {

    companion object {
        private const val PAGE_LIVE_TV = 0
        private const val PAGE_KEYPAD = 1
        private const val PAGE_MY_LIST = 2
        private const val PAGE_STARTUP = 3
        private const val PAGE_ACCOUNT = 4

        private const val REQ_CODE_PICK_BINARY_FILE = 4401

        // YouTube TV Deep Black & Crisp White Palette
        private val BG_DEEP = Color.parseColor("#000000")
        private val BG_RAIL = Color.parseColor("#0A0A0A")
        private val BG_CARD = Color.parseColor("#161616")
        private val BG_CARD_ELEVATED = Color.parseColor("#272727")
        private val BG_INPUT = Color.parseColor("#0F0F0F")
        private val BORDER_SUBTLE = Color.parseColor("#2E2E2E")
        private val ACCENT_CYAN = Color.parseColor("#FFFFFF")
        private val ACCENT_GOLD = Color.parseColor("#FF0033")
        private val ACCENT_GREEN = Color.parseColor("#2BA640")
        private val ACCENT_RED = Color.parseColor("#FF0033")
        private val TEXT_PRIMARY = Color.parseColor("#FFFFFF")
        private val TEXT_SECONDARY = Color.parseColor("#AAAAAA")
        private val TEXT_MUTED = Color.parseColor("#717171")
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SkySharedPref

    // Root & Navigation Rail
    private lateinit var rootFrame: FrameLayout
    private var currentPage: Int = PAGE_LIVE_TV
    private val navButtons = mutableListOf<Button>()
    private lateinit var railStatusDot: TextView
    private lateinit var railStatusLabel: TextView
    private lateinit var pageStage: FrameLayout

    // Page Containers
    private lateinit var pageLiveTvView: View
    private lateinit var pageKeypadView: View
    private lateinit var pageMyListView: View
    private lateinit var pageStartupView: View
    private lateinit var pageAccountView: View

    // Page 1: Live TV (Mode 1: TV Remote Guide)
    private lateinit var liveTvTitleText: TextView
    private lateinit var liveTvCountBadge: TextView
    private lateinit var liveTvStartupBadge: TextView
    private lateinit var categoryPillsRow: LinearLayout
    private lateinit var liveTvSearchBarContainer: LinearLayout
    private lateinit var liveTvSearchInput: EditText
    private lateinit var channelGridView: GridView
    private lateinit var liveTvEmptyStateBox: LinearLayout
    private var gridAdapter: ChannelGridAdapter? = null
    // Special category ID -1 represents "★ My Custom List" pill in the Live TV category bar
    private var selectedLiveCategoryPill: Int = 0

    // Page 2: Number Pad Dialer (Mode 2: Direct Number Entry)
    private lateinit var dialerDigitsDisplay: TextView
    private lateinit var dialerPreviewLogo: ImageView
    private lateinit var dialerPreviewTitle: TextView
    private lateinit var dialerPreviewSubtitle: TextView
    private lateinit var dialerWatchBtn: Button
    private lateinit var dialerDirectoryHeader: TextView
    private lateinit var dialerDirectoryListView: ListView

    // Page 3: My Custom List & Number Studio
    private lateinit var studioCustomHeader: TextView
    private lateinit var studioModeToggleBtn: Button
    private lateinit var studioCustomListView: ListView
    private lateinit var studioCatalogSearchInput: EditText
    private lateinit var studioCatalogListView: ListView
    private var studioCustomItems: List<NumberedChannel> = emptyList()
    private var studioCatalogItems: List<NumberedChannel> = emptyList()
    private var studioCustomAdapter: BaseAdapter? = null
    private var studioCatalogAdapter: BaseAdapter? = null

    // Page 4: Startup & Video Preferences
    private lateinit var startupModeOffBtn: Button
    private lateinit var startupModeLastBtn: Button
    private lateinit var startupModeSpecificBtn: Button
    private lateinit var startupSpecificInfoText: TextView
    private lateinit var startupDefaultUiRemoteBtn: Button
    private lateinit var startupDefaultUiNumpadBtn: Button
    private lateinit var qualityPillsContainer: LinearLayout
    private lateinit var languagePillsContainer: LinearLayout

    // Page 5: Account & Server Engine
    private lateinit var accountServerStatusText: TextView
    private lateinit var accountBinaryInfoText: TextView
    private lateinit var accountPortInput: EditText
    private lateinit var accountPublicToggleBtn: Button
    private lateinit var accountBootToggleBtn: Button
    private lateinit var accountServerLogText: TextView
    private lateinit var loginPhoneInput: EditText
    private lateinit var loginOtpInput: EditText
    private lateinit var loginStatusText: TextView

    // Custom Glass Overlays (Assign Channel # Modal, Import Binary Modal & Global Quick Number OSD)
    private lateinit var assignModalOverlay: FrameLayout
    private lateinit var assignModalTitle: TextView
    private lateinit var assignModalSubtitle: TextView
    private lateinit var assignModalDigitsText: TextView
    private var assignModalTargetChannel: NumberedChannel? = null
    private val assignModalBuffer = StringBuilder()

    private lateinit var importBinaryModalOverlay: FrameLayout
    private lateinit var importModalStatusText: TextView
    private lateinit var importModalPathText: TextView
    private lateinit var importModalFileListView: ListView
    private var importModalCurrentDir: File = File("/sdcard")
    private var importModalEntries: List<FileBrowserEntry> = emptyList()
    private var importModalAdapter: BaseAdapter? = null

    private data class FileBrowserEntry(
        val file: File,
        val displayTitle: String,
        val displaySubtitle: String,
        val isParentLink: Boolean = false,
        val isCandidateHighlight: Boolean = false
    )

    private lateinit var quickNumberOsdCard: LinearLayout
    private lateinit var quickNumberOsdDigits: TextView
    private lateinit var quickNumberOsdPreview: TextView

    // Data State
    private var allRawChannels: List<Channel> = emptyList()
    private var displayedChannels: List<NumberedChannel> = emptyList()
    private var dialerPreviewChannel: NumberedChannel? = null
    private val dialerBuffer = StringBuilder()
    private val quickOsdBuffer = StringBuilder()

    private var hasHandledColdStartLaunch = false
    private var isReceiverRegistered = false
    private var serverOnline = false
    private var lastServerErrorMessage: String? = null
    private val recentServerLogs = ArrayDeque<String>(14)

    private val commitQuickNumberRunnable = Runnable {
        commitQuickOsdNumber()
    }

    private val commitDialerAutoRunnable = Runnable {
        dialerPreviewChannel?.let { launchChannelPlayback(it) }
    }

    private val serverEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BinaryService.ACTION_BINARY_STARTED -> {
                    lastServerErrorMessage = null
                    val port = prefs.myPreferences.jtvGoServerPort
                    updateServerStatusUi(true, "Port $port")
                    refreshChannelsFromServer(triggerStartupLaunchIfReady = true)
                }
                BinaryService.ACTION_BINARY_STOPPED -> {
                    val err = intent.getStringExtra(BinaryService.EXTRA_ERROR_MESSAGE)
                    if (!err.isNullOrBlank()) {
                        lastServerErrorMessage = err
                        appendServerLogLine("ERROR: $err")
                        updateServerStatusUi(false, "Failed (See Server tab)")
                    } else {
                        updateServerStatusUi(false, "Stopped")
                    }
                }
                BinaryService.ACTION_BINARY_LOG -> {
                    val line = intent.getStringExtra(BinaryService.EXTRA_LOG_LINE)
                    if (!line.isNullOrBlank()) {
                        appendServerLogLine(line)
                    }
                }
            }
        }
    }

    private fun appendServerLogLine(line: String) {
        if (recentServerLogs.size >= 12) {
            recentServerLogs.removeFirst()
        }
        recentServerLogs.addLast(line.trim())
        if (::accountServerLogText.isInitialized) {
            accountServerLogText.text = recentServerLogs.joinToString("\n")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogCollector.init(this)
        LogCollector.log("[MainActivity] onCreate() started")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = SkySharedPref.getInstance(this)
        JTVConfigurationManager.getInstance(this).saveJTVConfiguration()

        // Initialize selected pill from saved preference
        selectedLiveCategoryPill = if (prefs.myPreferences.listMode == SkySharedPref.LIST_MODE_CUSTOM) {
            -1
        } else {
            prefs.myPreferences.filterCategoryId
        }

        buildModernTvUi()
        registerServerReceiver()

        // Immediately load cached channels from disk for <50ms UI readiness
        val cached = CustomChannelStore.loadCachedChannels(this)
        if (cached.isNotEmpty()) {
            allRawChannels = cached
            refreshAllPagesData()
        }

        // Open default page based on user's preferred mode (Mode 1: Live TV vs Mode 2: Keypad)
        val initialPage = if (prefs.myPreferences.uiMode == SkySharedPref.UI_MODE_NUMPAD) {
            PAGE_KEYPAD
        } else {
            PAGE_LIVE_TV
        }
        switchToPage(initialPage, requestContentFocus = true)

        bootstrapBinaryAndServer()
    }

    private fun registerServerReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BinaryService.ACTION_BINARY_STARTED)
                addAction(BinaryService.ACTION_BINARY_STOPPED)
                addAction(BinaryService.ACTION_BINARY_LOG)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(serverEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(serverEventReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    // =========================================================================
    // ROOT LAYOUT: LEFT SIDEBAR RAIL + DEDICATED FULL-SCREEN STAGE
    // =========================================================================
    private fun buildModernTvUi() {
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(BG_DEEP)
        }

        val mainHorizontal = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Sleek Left Navigation Rail
        val leftRail = buildLeftNavigationRail()
        mainHorizontal.addView(leftRail)

        // Subtle vertical divider
        val railDivider = View(this).apply {
            setBackgroundColor(BORDER_SUBTLE)
            layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        mainHorizontal.addView(railDivider)

        // 2. Right Content Stage (Holds the 5 dedicated full-screen pages)
        pageStage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        }

        pageLiveTvView = buildPageLiveTv()
        pageKeypadView = buildPageKeypad()
        pageMyListView = buildPageMyListStudio()
        pageStartupView = buildPageStartupSettings()
        pageAccountView = buildPageAccountAndServer()

        pageStage.addView(pageLiveTvView)
        pageStage.addView(pageKeypadView)
        pageStage.addView(pageMyListView)
        pageStage.addView(pageStartupView)
        pageStage.addView(pageAccountView)

        mainHorizontal.addView(pageStage)
        rootFrame.addView(mainHorizontal)

        // 3. Global Quick Number Entry OSD (Top-Right Glass Card when typing 0-9 on remote)
        buildQuickNumberOsd()

        // 4. Custom Glass Modal Overlay for Assigning Channel Numbers (Replaces ugly AlertDialog)
        buildAssignNumberModalOverlay()

        // 5. Custom Glass Modal Overlay for Importing jiotv_go Binary from File Manager / USB / Storage
        buildImportBinaryModalOverlay()

        setContentView(rootFrame)
    }

    private fun buildLeftNavigationRail(): LinearLayout {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_RAIL)
            setPadding(dp(12), dp(18), dp(12), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                dp(190),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Brand Header inside Rail
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), dp(18))
        }
        val brandTitle = TextView(this).apply {
            text = "JioTV Go"
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val brandSub = TextView(this).apply {
            text = "KITKAT TV EDITION"
            setTextColor(ACCENT_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, 0)
        }
        brandRow.addView(brandTitle)
        brandRow.addView(brandSub)
        rail.addView(brandRow)

        // 5 Dedicated Navigation Items
        val navItems = listOf(
            PAGE_LIVE_TV to "📺  Live TV",
            PAGE_KEYPAD to "🔢  Number Pad",
            PAGE_MY_LIST to "⭐  My List & #",
            PAGE_STARTUP to "🚀  Startup & TV",
            PAGE_ACCOUNT to "⚙  Server & Login"
        )

        navButtons.clear()
        for ((pageIdx, label) in navItems) {
            val btn = Button(this).apply {
                text = label
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(12), dp(10))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(46)
                ).apply {
                    bottomMargin = dp(8)
                }
                setOnClickListener {
                    switchToPage(pageIdx, requestContentFocus = true)
                }
            }
            navButtons.add(btn)
            rail.addView(btn)
        }

        // Spacer pushing server status pill to bottom of rail
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        rail.addView(spacer)

        // Bottom Minimal Engine Status Card in Sidebar
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(BG_CARD, BORDER_SUBTLE, dp(1), dp(10))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val statusHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        railStatusDot = TextView(this).apply {
            text = "● "
            setTextColor(ACCENT_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val engineLabel = TextView(this).apply {
            text = "STREAM ENGINE"
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
        }
        statusHeaderRow.addView(railStatusDot)
        statusHeaderRow.addView(engineLabel)

        railStatusLabel = TextView(this).apply {
            text = "Starting..."
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        statusCard.addView(statusHeaderRow)
        statusCard.addView(railStatusLabel)

        val quickImportRailBtn = Button(this).apply {
            text = "📂 Import Binary"
            isAllCaps = false
            setTextColor(createFocusableTextColors(TEXT_PRIMARY, Color.BLACK))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            background = createSurfaceButtonDrawable(radiusDp = 6)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(32)
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                showImportBinaryModal()
            }
        }
        statusCard.addView(quickImportRailBtn)
        rail.addView(statusCard)

        return rail
    }

    private fun switchToPage(pageIndex: Int, requestContentFocus: Boolean = false) {
        currentPage = pageIndex
        if (pageIndex == PAGE_LIVE_TV) {
            prefs.myPreferences.uiMode = SkySharedPref.UI_MODE_REMOTE
        } else if (pageIndex == PAGE_KEYPAD) {
            prefs.myPreferences.uiMode = SkySharedPref.UI_MODE_NUMPAD
        }

        pageLiveTvView.visibility = if (pageIndex == PAGE_LIVE_TV) View.VISIBLE else View.GONE
        pageKeypadView.visibility = if (pageIndex == PAGE_KEYPAD) View.VISIBLE else View.GONE
        pageMyListView.visibility = if (pageIndex == PAGE_MY_LIST) View.VISIBLE else View.GONE
        pageStartupView.visibility = if (pageIndex == PAGE_STARTUP) View.VISIBLE else View.GONE
        pageAccountView.visibility = if (pageIndex == PAGE_ACCOUNT) View.VISIBLE else View.GONE

        // Update Sidebar Rail Active Pill Styling
        for (i in navButtons.indices) {
            val btn = navButtons[i]
            val isSelected = (i == pageIndex)
            btn.background = createRailItemDrawable(isSelected)
            btn.setTextColor(createFocusableTextColors(normalColor = TEXT_PRIMARY, focusedColor = Color.BLACK))
        }

        when (pageIndex) {
            PAGE_LIVE_TV -> {
                rebuildDisplayedChannels()
                if (requestContentFocus) {
                    channelGridView.post {
                        channelGridView.requestFocus()
                        if (channelGridView.selectedItemPosition == AdapterView.INVALID_POSITION && displayedChannels.isNotEmpty()) {
                            channelGridView.setSelection(0)
                        }
                        gridAdapter?.notifyDataSetChanged()
                    }
                }
            }
            PAGE_KEYPAD -> {
                if (dialerBuffer.isEmpty()) {
                    val initialNum = prefs.myPreferences.lastChannelNumber.coerceAtLeast(1)
                    dialerBuffer.append(initialNum)
                }
                updateDialerPreview(autoLaunchCountdown = false)
                refreshDialerDirectoryList()
                if (requestContentFocus) {
                    dialerWatchBtn.post { dialerWatchBtn.requestFocus() }
                }
            }
            PAGE_MY_LIST -> {
                refreshMyListStudioPage()
            }
            PAGE_STARTUP -> {
                refreshStartupSettingsPage()
            }
            PAGE_ACCOUNT -> {
                refreshAccountServerPage()
            }
        }
    }

    // =========================================================================
    // PAGE 1: LIVE TV GUIDE (MODE 1 — TV REMOTE NAVIGATION)
    // =========================================================================
    private fun buildPageLiveTv(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top Header Row: Active Section Title + Channel Count Badge + Startup Badge + Search Toggle
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        liveTvTitleText = TextView(this).apply {
            text = "Live TV Channels"
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        }
        headerRow.addView(liveTvTitleText)

        liveTvCountBadge = TextView(this).apply {
            text = "0 Channels"
            setTextColor(ACCENT_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(BG_CARD_ELEVATED, BORDER_SUBTLE, dp(1), dp(14))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(12) }
        }
        headerRow.addView(liveTvCountBadge)

        val headerSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
        }
        headerRow.addView(headerSpacer)

        liveTvStartupBadge = TextView(this).apply {
            text = getStartupSummaryLabel()
            setTextColor(ACCENT_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(BG_CARD, BORDER_SUBTLE, dp(1), dp(8))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
        }
        headerRow.addView(liveTvStartupBadge)

        val searchToggleBtn = createModernButton("🔍 Search") {
            if (liveTvSearchBarContainer.visibility == View.VISIBLE) {
                liveTvSearchInput.setText("")
                liveTvSearchBarContainer.visibility = View.GONE
            } else {
                liveTvSearchBarContainer.visibility = View.VISIBLE
                liveTvSearchInput.requestFocus()
            }
        }
        headerRow.addView(searchToggleBtn)
        container.addView(headerRow)

        // Collapsible Clean Search Bar (Hidden by default to keep Live TV screen ultra-clean)
        liveTvSearchBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = roundedBg(BG_CARD, ACCENT_CYAN, dp(1), dp(10))
            setPadding(dp(14), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val searchIcon = TextView(this).apply {
            text = "🔍  "
            setTextColor(ACCENT_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        liveTvSearchInput = EditText(this).apply {
            hint = "Search by channel name or #number..."
            setHintTextColor(TEXT_MUTED)
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine(true)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    rebuildDisplayedChannels()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        val closeSearchBtn = createModernButton("✕ Clear") {
            liveTvSearchInput.setText("")
            liveTvSearchBarContainer.visibility = View.GONE
            channelGridView.requestFocus()
        }
        liveTvSearchBarContainer.addView(searchIcon)
        liveTvSearchBarContainer.addView(liveTvSearchInput)
        liveTvSearchBarContainer.addView(closeSearchBtn)
        container.addView(liveTvSearchBarContainer)

        // Modern Horizontal Category Pill Strip (Replaces ugly Spinners!)
        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        categoryPillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        categoryScroll.addView(categoryPillsRow)
        container.addView(categoryScroll)
        populateCategoryPills()

        // Main Grid Stage
        val gridStage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        channelGridView = GridView(this).apply {
            numColumns = 4
            horizontalSpacing = dp(14)
            verticalSpacing = dp(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            clipToPadding = false
            isFocusable = true
            isFocusableInTouchMode = false
            setDrawSelectorOnTop(true)
            selector = createGridSelectorDrawable()
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && selectedItemPosition == AdapterView.INVALID_POSITION && displayedChannels.isNotEmpty()) {
                    setSelection(0)
                }
                gridAdapter?.notifyDataSetChanged()
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    gridAdapter?.notifyDataSetChanged()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {
                    gridAdapter?.notifyDataSetChanged()
                }
            }
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                val ch = displayedChannels.getOrNull(position) ?: return@OnItemClickListener
                launchChannelPlayback(ch)
            }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
                val ch = displayedChannels.getOrNull(position) ?: return@OnItemLongClickListener true
                showAssignNumberModal(ch)
                true
            }
        }
        gridAdapter = ChannelGridAdapter()
        channelGridView.adapter = gridAdapter
        gridStage.addView(
            channelGridView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Clean Empty State Card when "★ My Custom List" is empty
        liveTvEmptyStateBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = roundedBg(BG_CARD, BORDER_SUBTLE, dp(1), dp(14))
            setPadding(dp(32), dp(32), dp(32), dp(32))
            layoutParams = FrameLayout.LayoutParams(
                dp(460),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        val emptyTitle = TextView(this).apply {
            text = "★ Your Custom Channel List is Empty"
            setTextColor(ACCENT_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val emptyDesc = TextView(this).apply {
            text = "Assign memorable channel numbers (1, 2, 3...) to your favorite channels so you can dial them anytime."
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }
        val emptyActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val openStudioBtn = createModernButton("⭐ Open My List Studio", isGold = true) {
            switchToPage(PAGE_MY_LIST, requestContentFocus = true)
        }
        val showAllBtn = createModernButton("Browse All Channels") {
            selectLiveCategoryPill(0)
        }
        emptyActionRow.addView(openStudioBtn)
        emptyActionRow.addView(showAllBtn)
        liveTvEmptyStateBox.addView(emptyTitle)
        liveTvEmptyStateBox.addView(emptyDesc)
        liveTvEmptyStateBox.addView(emptyActionRow)
        gridStage.addView(liveTvEmptyStateBox)

        container.addView(gridStage)

        // Subtle Footer Hint Bar
        val footerBar = TextView(this).apply {
            text = "OK: Watch Instantly   •   Hold OK / MENU: Assign Channel #   •   Press 0–9 on Remote: Direct Channel Jump"
            setTextColor(TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        container.addView(footerBar)

        return container
    }

    private fun populateCategoryPills() {
        categoryPillsRow.removeAllViews()

        // Pill 1: ★ My Custom List
        val customCount = CustomChannelStore.getCustomEntries(this).size
        val customPill = createCategoryPillButton(
            label = "★ My List ($customCount)",
            isSelected = (selectedLiveCategoryPill == -1),
            isGold = true
        ) {
            selectLiveCategoryPill(-1)
        }
        categoryPillsRow.addView(customPill)

        // Category Pills from CategoryMap
        for ((catId, catName) in CategoryMap) {
            val label = if (catId == 0) "All Channels" else catName
            val isSelected = (selectedLiveCategoryPill == catId && prefs.myPreferences.listMode != SkySharedPref.LIST_MODE_CUSTOM)
            val pill = createCategoryPillButton(
                label = label,
                isSelected = isSelected,
                isGold = false
            ) {
                selectLiveCategoryPill(catId)
            }
            categoryPillsRow.addView(pill)
        }
    }

    private fun selectLiveCategoryPill(catId: Int) {
        selectedLiveCategoryPill = catId
        if (catId == -1) {
            prefs.myPreferences.listMode = SkySharedPref.LIST_MODE_CUSTOM
        } else {
            prefs.myPreferences.listMode = SkySharedPref.LIST_MODE_ALL
            prefs.myPreferences.filterCategoryId = catId
        }
        populateCategoryPills()
        rebuildDisplayedChannels()
    }

    // =========================================================================
    // PAGE 2: NUMBER PAD DIALER (MODE 2 — DIRECT NUMBER ENTRY)
    // =========================================================================
    private fun buildPageKeypad(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Left Card: Digital Number Display + Live Channel Preview + Tactile Keypad
        val leftCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(BG_CARD, BORDER_SUBTLE, dp(1), dp(14))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1.05f
            ).apply { rightMargin = dp(16) }
        }

        // Top Preview Banner inside Left Card
        val previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(BG_INPUT, BORDER_SUBTLE, dp(1), dp(12))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        dialerDigitsDisplay = TextView(this).apply {
            text = "#1"
            setTextColor(ACCENT_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(86)
        }
        previewBox.addView(dialerDigitsDisplay)

        dialerPreviewLogo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = roundedBg(Color.WHITE, Color.TRANSPARENT, 0, dp(8))
            setPadding(dp(5), dp(5), dp(5), dp(5))
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                leftMargin = dp(10)
                rightMargin = dp(12)
            }
        }
        previewBox.addView(dialerPreviewLogo)

        val previewTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        dialerPreviewTitle = TextView(this).apply {
            text = "Type a Channel #"
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        dialerPreviewSubtitle = TextView(this).apply {
            text = "Press 0-9 on remote or tap keypad"
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        previewTextCol.addView(dialerPreviewTitle)
        previewTextCol.addView(dialerPreviewSubtitle)
        previewBox.addView(previewTextCol)

        dialerWatchBtn = createModernButton("▶ WATCH", isPrimary = true) {
            val num = dialerBuffer.toString().toIntOrNull()
            val target = dialerPreviewChannel ?: if (num != null) {
                CustomChannelStore.findChannelByNumber(
                    this,
                    num,
                    allRawChannels,
                    prefs.myPreferences.jtvGoServerPort
                )
            } else null

            if (target != null) {
                mainHandler.removeCallbacks(commitDialerAutoRunnable)
                launchChannelPlayback(target)
            } else {
                Toast.makeText(this, "No channel found at that number", Toast.LENGTH_SHORT).show()
            }
        }
        previewBox.addView(dialerWatchBtn)
        leftCard.addView(previewBox)

        // 3x4 Tactile Keypad Grid
        val keypadContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(10)
            }
        }

        val keyRows = arrayOf(
            arrayOf("1", "2", "3"),
            arrayOf("4", "5", "6"),
            arrayOf("7", "8", "9"),
            arrayOf("CLR", "0", "⌫")
        )

        for (row in keyRows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply { bottomMargin = dp(8) }
            }
            for (key in row) {
                val keyBtn = Button(this).apply {
                    text = key
                    isAllCaps = false
                    setTextColor(
                        createFocusableTextColors(
                            normalColor = if (key == "CLR" || key == "⌫") TEXT_SECONDARY else TEXT_PRIMARY,
                            focusedColor = Color.BLACK
                        )
                    )
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                    typeface = Typeface.DEFAULT_BOLD
                    background = createKeypadButtonDrawable()
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f
                    ).apply {
                        leftMargin = dp(4)
                        rightMargin = dp(4)
                    }
                    setOnClickListener {
                        when (key) {
                            "CLR" -> {
                                mainHandler.removeCallbacks(commitDialerAutoRunnable)
                                dialerBuffer.clear()
                                updateDialerPreview(autoLaunchCountdown = false)
                            }
                            "⌫" -> {
                                mainHandler.removeCallbacks(commitDialerAutoRunnable)
                                if (dialerBuffer.isNotEmpty()) {
                                    dialerBuffer.deleteCharAt(dialerBuffer.length - 1)
                                }
                                updateDialerPreview(autoLaunchCountdown = false)
                            }
                            else -> {
                                onDialerDigitPressed(key[0], autoLaunchAfterDelay = true)
                            }
                        }
                    }
                }
                rowLayout.addView(keyBtn)
            }
            keypadContainer.addView(rowLayout)
        }
        leftCard.addView(keypadContainer)

        // Quick Actions under Keypad
        val bottomActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val assignCurrentBtn = createModernButton("⭐ Assign # to Previewed") {
            val ch = dialerPreviewChannel
            if (ch != null) {
                showAssignNumberModal(ch)
            } else {
                switchToPage(PAGE_MY_LIST, requestContentFocus = true)
            }
        }
        val setStartupBtn = createModernButton("🚀 Set as Startup Channel") {
            val ch = dialerPreviewChannel
            if (ch != null) {
                prefs.myPreferences.apply {
                    startupLaunchMode = SkySharedPref.STARTUP_SPECIFIC_CHANNEL
                    startupChannelNumber = ch.assignedNumber
                    startupChannelId = ch.channelId
                    startupChannelName = ch.channelName
                }
                liveTvStartupBadge.text = getStartupSummaryLabel()
                Toast.makeText(
                    this,
                    "Startup Channel set to #${ch.assignedNumber} (${ch.channelName})",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        bottomActions.addView(assignCurrentBtn)
        bottomActions.addView(setStartupBtn)
        leftCard.addView(bottomActions)

        root.addView(leftCard)

        // Right Card: Speed-Dial Channel Number Directory
        val rightCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(BG_CARD, BORDER_SUBTLE, dp(1), dp(14))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                0.95f
            )
        }

        val dirTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        dialerDirectoryHeader = TextView(this).apply {
            text = "★ Speed-Dial Directory"
            setTextColor(ACCENT_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val customizeBtn = createModernButton("✎ Customize #") {
            switchToPage(PAGE_MY_LIST, requestContentFocus = true)
        }
        dirTopRow.addView(dialerDirectoryHeader)
        dirTopRow.addView(customizeBtn)
        rightCard.addView(dirTopRow)

        dialerDirectoryListView = ListView(this).apply {
            dividerHeight = dp(6)
            isFocusable = true
            isFocusableInTouchMode = false
            setDrawSelectorOnTop(true)
            selector = createGridSelectorDrawable()
            setOnFocusChangeListener { _, _ ->
                (adapter as? BaseAdapter)?.notifyDataSetChanged()
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    (adapter as? BaseAdapter)?.notifyDataSetChanged()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {
                    (adapter as? BaseAdapter)?.notifyDataSetChanged()
                }
            }
            onItemClickListener = AdapterView.OnItemClickListener { parent, _, position, _ ->
                val item = parent.adapter?.getItem(position) as? NumberedChannel
                if (item != null) {
                    launchChannelPlayback(item)
                }
            }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { parent, _, position, _ ->
                val item = parent.adapter?.getItem(position) as? NumberedChannel
                if (item != null) {
                    showAssignNumberModal(item)
                }
                true
            }
        }
        rightCard.addView(
            dialerDirectoryListView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(rightCard)
        return root
    }

    // =========================================================================
    // PAGE 3: MY CUSTOM LIST & CHANNEL NUMBER STUDIO (DEDICATED FULL-SCREEN PAGE)
    // =========================================================================
    private fun buildPageMyListStudio(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Studio Header Row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val studioTitle = TextView(this).apply {
            text = "⭐ Custom Channel Lineup & Number Studio"
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val studioSub = TextView(this).apply {
            text = "Build your personal channel list and assign memorable numbers (1..9999) for instant remote dialing"
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, 0)
        }
        titleCol.addView(studioTitle)
        titleCol.addView(studioSub)
        headerRow.addView(titleCol)

        studioModeToggleBtn = createModernButton("Default Guide: All Channels") {
            val isCurrentlyCustom = prefs.myPreferences.listMode == SkySharedPref.LIST_MODE_CUSTOM
            prefs.myPreferences.listMode = if (isCurrentlyCustom) {
                SkySharedPref.LIST_MODE_ALL
            } else {
                SkySharedPref.LIST_MODE_CUSTOM
            }
            selectedLiveCategoryPill = if (prefs.myPreferences.listMode == SkySharedPref.LIST_MODE_CUSTOM) -1 else 0
            populateCategoryPills()
            refreshMyListStudioPage()
        }
        val autoNumberBtn = createModernButton("1..N Auto-Number") {
            CustomChannelStore.renumberCustomListSequentially(this)
            refreshAllPagesData()
            Toast.makeText(this, "Renumbered custom channels 1..N", Toast.LENGTH_SHORT).show()
        }
        val clearAllBtn = createModernButton("Clear List") {
            CustomChannelStore.clearAllCustomChannels(this)
            refreshAllPagesData()
            Toast.makeText(this, "Cleared custom channel list", Toast.LENGTH_SHORT).show()
        }
        headerRow.addView(studioModeToggleBtn)
        headerRow.addView(autoNumberBtn)
        headerRow.addView(clearAllBtn)
        root.addView(headerRow)

        // Two-Column Studio Workspace
        val columnsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        // Left Pane: User's Custom Numbered Channels
        val leftPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(BG_CARD, BORDER_SUBTLE, dp(1), dp(14))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1.05f
            ).apply { rightMargin = dp(14) }
        }

        studioCustomHeader = TextView(this).apply {
            text = "★ Your Custom Channels (0)"
            setTextColor(ACCENT_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        leftPane.addView(studioCustomHeader)

        studioCustomListView = ListView(this).apply {
            dividerHeight = dp(6)
            isFocusable = true
            isFocusableInTouchMode = false
            setDrawSelectorOnTop(true)
            selector = createGridSelectorDrawable()
        }
        studioCustomAdapter = object : BaseAdapter() {
            override fun getCount(): Int = studioCustomItems.size
            override fun getItem(position: Int): Any = studioCustomItems[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val item = studioCustomItems[position]
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedBg(BG_INPUT, BORDER_SUBTLE, dp(1), dp(8))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }

                val numBtn = createModernButton("★ #${item.assignedNumber}", isGold = true) {
                    showAssignNumberModal(item)
                }
                val nameTv = TextView(this@MainActivity).apply {
                    text = item.channelName
                    setTextColor(TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(8)
                        rightMargin = dp(6)
                    }
                }
                val upBtn = createModernButton("▲") {
                    CustomChannelStore.moveCustomChannel(this@MainActivity, item.channelId, -1)
                    refreshAllPagesData()
                }
                val downBtn = createModernButton("▼") {
                    CustomChannelStore.moveCustomChannel(this@MainActivity, item.channelId, 1)
                    refreshAllPagesData()
                }
                val removeBtn = createModernButton("✕") {
                    CustomChannelStore.removeCustomChannel(this@MainActivity, item.channelId)
                    refreshAllPagesData()
                }

                row.addView(numBtn)
                row.addView(nameTv)
                row.addView(upBtn)
                row.addView(downBtn)
                row.addView(removeBtn)
                return row
            }
        }
        studioCustomListView.adapter = studioCustomAdapter
        leftPane.addView(
            studioCustomListView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        columnsRow.addView(leftPane)

        // Right Pane: All Channels Catalog (Click any channel to assign # & add)
        val rightPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(BG_CARD, BORDER_SUBTLE, dp(1), dp(14))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                0.95f
            )
        }

        val catalogHeader = TextView(this).apply {
            text = "All Channels Catalog (Select to Assign #)"
            setTextColor(ACCENT_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        rightPane.addView(catalogHeader)

        studioCatalogSearchInput = EditText(this).apply {
            hint = "🔍 Filter catalog by channel name..."
            setHintTextColor(TEXT_MUTED)
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setSingleLine(true)
            background = roundedBg(BG_INPUT, BORDER_SUBTLE, dp(1), dp(8))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterStudioCatalogList()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        rightPane.addView(studioCatalogSearchInput)

        studioCatalogListView = ListView(this).apply {
            dividerHeight = dp(4)
            isFocusable = true
            isFocusableInTouchMode = false
            setDrawSelectorOnTop(true)
            selector = createGridSelectorDrawable()
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                val ch = studioCatalogItems.getOrNull(position) ?: return@OnItemClickListener
                showAssignNumberModal(ch)
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    studioCatalogAdapter?.notifyDataSetChanged()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {
                    studioCatalogAdapter?.notifyDataSetChanged()
                }
            }
            setOnFocusChangeListener { _, _ ->
                studioCatalogAdapter?.notifyDataSetChanged()
            }
        }
        studioCatalogAdapter = object : BaseAdapter() {
            override fun getCount(): Int = studioCatalogItems.size
            override fun getItem(position: Int): Any = studioCatalogItems[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val item = studioCatalogItems[position]
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedBg(BG_INPUT, BORDER_SUBTLE, dp(1), dp(8))
                    setPadding(dp(12), dp(10), dp(12), dp(10))

                    val badge = TextView(this@MainActivity).apply {
                        id = 301
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                    }
                    val title = TextView(this@MainActivity).apply {
                        id = 302
                        setTextColor(TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            leftMargin = dp(10)
                        }
                    }
                    val addHint = TextView(this@MainActivity).apply {
                        id = 303
                        text = "+ Assign #"
                        setTextColor(ACCENT_CYAN)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    addView(badge)
                    addView(title)
                    addView(addHint)
                }

                val isSelectedRow = studioCatalogListView.hasFocus() && position == studioCatalogListView.selectedItemPosition
                row.background = if (isSelectedRow) {
                    roundedBg(BG_CARD_ELEVATED, Color.WHITE, dp(2), dp(8))
                } else {
                    roundedBg(BG_INPUT, BORDER_SUBTLE, dp(1), dp(8))
                }

                val badge = row.findViewById<TextView>(301)
                val title = row.findViewById<TextView>(302)
                val addHint = row.findViewById<TextView>(303)

                badge.text = if (item.isCustomAssigned) "★ #${item.assignedNumber}" else "#${item.assignedNumber}"
                badge.setTextColor(Color.WHITE)
                badge.background = roundedBg(
                    if (item.isCustomAssigned) ACCENT_GOLD else BG_CARD_ELEVATED,
                    if (item.isCustomAssigned) Color.TRANSPARENT else BORDER_SUBTLE,
                    if (item.isCustomAssigned) 0 else dp(1),
                    dp(6)
                )
                title.text = if (isSelectedRow) "▶ ${item.channelName}" else item.channelName
                addHint.text = if (item.isCustomAssigned) "✎ Edit #" else "+ Assign #"
                return row
            }
        }
        studioCatalogListView.adapter = studioCatalogAdapter
        rightPane.addView(
            studioCatalogListView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        columnsRow.addView(rightPane)

        root.addView(columnsRow)
        return root
    }

    // =========================================================================
    // PAGE 4: STARTUP CHANNEL & PLAYBACK PREFERENCES (DEDICATED FULL-SCREEN PAGE)
    // =========================================================================
    private fun buildPageStartupSettings(): View {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(24))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val pageTitle = TextView(this).apply {
            text = "🚀 Direct Channel Launch & Adaptive Playback"
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val pageSub = TextView(this).apply {
            text = "Configure how JioTV Go behaves when opened and tune adaptive video streaming for KitKat"
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(3), 0, dp(16))
        }
        container.addView(pageTitle)
        container.addView(pageSub)

        // Card 1: Direct Channel Launch When App Starts
        val startupCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(BG_CARD, BORDER_SUBTLE, dp(1), dp(14))
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        val card1Title = TextView(this).apply {
            text = "1. When App Launches"
            setTextColor(ACCENT_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }
        startupCard.addView(card1Title)

        val startupBtnsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        startupModeOffBtn = createModernButton("🏠 Open Home Guide") {
            prefs.myPreferences.startupLaunchMode = SkySharedPref.STARTUP_NONE
            refreshStartupSettingsPage()
            liveTvStartupBadge.text = getStartupSummaryLabel()
        }
        startupModeLastBtn = createModernButton("⏮ Resume Last Watched Channel") {
            prefs.myPreferences.startupLaunchMode = SkySharedPref.STARTUP_LAST_CHANNEL
            refreshStartupSettingsPage()
            liveTvStartupBadge.text = getStartupSummaryLabel()
        }
        startupModeSpecificBtn = createModernButton("🎯 Launch Specific Channel #") {
            prefs.myPreferences.startupLaunchMode = SkySharedPref.STARTUP_SPECIFIC_CHANNEL
            refreshStartupSettingsPage()
            liveTvStartupBadge.text = getStartupSummaryLabel()
        }
        startupBtnsRow.addView(startupModeOffBtn)
        startupBtnsRow.addView(startupModeLastBtn)
        startupBtnsRow.addView(startupModeSpecificBtn)
        startupCard.addView(startupBtnsRow)

        // Specific Startup Channel # Row
        val specificRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        startupSpecificInfoText = TextView(this).apply {
            text = "Target Startup Channel: #1"
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pickStartupNumBtn = createModernButton("✎ Choose Startup Channel #...") {
            val currentNum = prefs.myPreferences.startupChannelNumber.coerceAtLeast(1)
            val currentCh = CustomChannelStore.findChannelByNumber(
                this,
                currentNum,
                allRawChannels,
                prefs.myPreferences.jtvGoServerPort
            )
            if (currentCh != null) {
                showAssignNumberModal(currentCh, setAsStartupToo = true)
            } else {
                switchToPage(PAGE_KEYPAD, requestContentFocus = true)
            }
        }
        specificRow.addView(startupSpecificInfoText)
        specificRow.addView(pickStartupNumBtn)
        startupCard.addView(specificRow)

        // Default Home Tab (Mode 1 vs Mode 2)
        val defaultTabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val defaultTabLabel = TextView(this).apply {
            text = "Default Home Mode:"
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, dp(12), 0)
        }
        startupDefaultUiRemoteBtn = createModernButton("📺 Mode 1: TV Remote Grid") {
            prefs.myPreferences.uiMode = SkySharedPref.UI_MODE_REMOTE
            refreshStartupSettingsPage()
        }
        startupDefaultUiNumpadBtn = createModernButton("🔢 Mode 2: Number Pad") {
            prefs.myPreferences.uiMode = SkySharedPref.UI_MODE_NUMPAD
            refreshStartupSettingsPage()
        }
        defaultTabRow.addView(defaultTabLabel)
        defaultTabRow.addView(startupDefaultUiRemoteBtn)
        defaultTabRow.addView(startupDefaultUiNumpadBtn)
        startupCard.addView(defaultTabRow)

        container.addView(startupCard)

        // Card 2: Fast Adaptive Streaming & Language Filter
        val videoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(BG_CARD, BORDER_SUBTLE, dp(1), dp(14))
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val card2Title = TextView(this).apply {
            text = "2. Fast Adaptive Video Quality & Language Filter"
            setTextColor(ACCENT_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }
        videoCard.addView(card2Title)

        qualityPillsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        videoCard.addView(qualityPillsContainer)

        val langTitle = TextView(this).apply {
            text = "Channel Guide Language Filter:"
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(8))
        }
        videoCard.addView(langTitle)

        val langScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        languagePillsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        langScroll.addView(languagePillsContainer)
        videoCard.addView(langScroll)

        container.addView(videoCard)
        scroll.addView(container)
        return scroll
    }

    // =========================================================================
    // PAGE 5: ACCOUNT & LOCAL SERVER ENGINE (DEDICATED FULL-SCREEN PAGE)
    // =========================================================================
    private fun buildPageAccountAndServer(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Left Card: JioTV OTP Login
        val loginCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(BG_CARD, BORDER_SUBTLE, dp(1), dp(14))
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            ).apply { rightMargin = dp(16) }
        }

        val loginTitle = TextView(this).apply {
            text = "🔑 JioTV Account Login (OTP)"
            setTextColor(ACCENT_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val loginDesc = TextView(this).apply {
            text = "Sign in with your 10-digit Jio mobile number to unlock all live channels"
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(14))
        }
        loginCard.addView(loginTitle)
        loginCard.addView(loginDesc)

        loginPhoneInput = EditText(this).apply {
            hint = "10-digit Jio Mobile Number (without +91)"
            setHintTextColor(TEXT_MUTED)
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            background = roundedBg(BG_INPUT, BORDER_SUBTLE, dp(1), dp(8))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        loginCard.addView(loginPhoneInput)

        val sendOtpBtn = createModernButton("1. Send OTP to Mobile") {
            val port = prefs.myPreferences.jtvGoServerPort
            val rawPhone = loginPhoneInput.text.toString().trim().removePrefix("+91").trim()
            if (rawPhone.length < 10) {
                Toast.makeText(this, "Enter a valid 10-digit Jio number", Toast.LENGTH_SHORT).show()
                return@createModernButton
            }
            loginStatusText.text = "Sending OTP to +91$rawPhone..."
            uiScope.launch {
                val res = sendOtpRequest(port, "+91$rawPhone")
                loginStatusText.text = res
                loginOtpInput.requestFocus()
            }
        }
        loginCard.addView(sendOtpBtn)

        loginOtpInput = EditText(this).apply {
            hint = "Enter 6-digit OTP received via SMS"
            setHintTextColor(TEXT_MUTED)
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            background = roundedBg(BG_INPUT, BORDER_SUBTLE, dp(1), dp(8))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(10)
            }
        }
        loginCard.addView(loginOtpInput)

        val verifyOtpBtn = createModernButton("2. Verify OTP & Activate Channels", isPrimary = true) {
            val port = prefs.myPreferences.jtvGoServerPort
            val rawPhone = loginPhoneInput.text.toString().trim().removePrefix("+91").trim()
            val otp = loginOtpInput.text.toString().trim()
            if (rawPhone.length < 10 || otp.isEmpty()) {
                Toast.makeText(this, "Enter both mobile number and OTP", Toast.LENGTH_SHORT).show()
                return@createModernButton
            }
            loginStatusText.text = "Verifying OTP..."
            uiScope.launch {
                val (ok, msg) = verifyOtpRequest(port, "+91$rawPhone", otp)
                loginStatusText.text = msg
                if (ok) {
                    Toast.makeText(this@MainActivity, "Login successful! Refreshing channels...", Toast.LENGTH_SHORT).show()
                    refreshChannelsFromServer(triggerStartupLaunchIfReady = false)
                }
            }
        }
        loginCard.addView(verifyOtpBtn)

        loginStatusText = TextView(this).apply {
            text = "Status: Ready"
            setTextColor(ACCENT_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(12), 0, 0)
        }
        loginCard.addView(loginStatusText)
        root.addView(loginCard)

        // Right Card: Local Streaming Engine Control
        val serverCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(BG_CARD, BORDER_SUBTLE, dp(1), dp(14))
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        }

        val serverTitle = TextView(this).apply {
            text = "⚙ Local JioTV Go Streaming Engine"
            setTextColor(ACCENT_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        serverCard.addView(serverTitle)

        accountServerStatusText = TextView(this).apply {
            text = "● Server Status: Checking..."
            setTextColor(ACCENT_GREEN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(4))
        }
        accountBinaryInfoText = TextView(this).apply {
            text = ""
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(12))
        }
        serverCard.addView(accountServerStatusText)
        serverCard.addView(accountBinaryInfoText)

        accountPortInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.myPreferences.jtvGoServerPort.toString())
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine(true)
            background = roundedBg(BG_INPUT, BORDER_SUBTLE, dp(1), dp(8))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        serverCard.addView(accountPortInput)

        accountPublicToggleBtn = createModernButton("LAN Access (--public): OFF") {
            prefs.myPreferences.serveLocal = !prefs.myPreferences.serveLocal
            refreshAccountServerPage()
        }
        accountBootToggleBtn = createModernButton("Auto-Start on Device Boot: ON") {
            prefs.myPreferences.autoStartOnBoot = !prefs.myPreferences.autoStartOnBoot
            refreshAccountServerPage()
        }
        serverCard.addView(accountPublicToggleBtn)
        serverCard.addView(accountBootToggleBtn)

        val serverBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val restartBtn = createModernButton("↻ Save & Restart", isPrimary = true) {
            val newPort = accountPortInput.text.toString().toIntOrNull()?.coerceIn(1024, 65535) ?: 5350
            prefs.myPreferences.jtvGoServerPort = newPort
            startJioTvServerService(forceRestart = true)
            Toast.makeText(this, "Restarting server on port $newPort...", Toast.LENGTH_SHORT).show()
        }
        val updateBinBtn = createModernButton("⬇ Re-Download") {
            bootstrapBinaryAndServer(forceUpdate = true)
        }
        val stopBtn = createModernButton("■ Stop") {
            val stopIntent = Intent(this, BinaryService::class.java).apply {
                action = BinaryService.ACTION_STOP_BINARY
            }
            startService(stopIntent)
        }
        serverBtnRow.addView(restartBtn)
        serverBtnRow.addView(updateBinBtn)
        serverBtnRow.addView(stopBtn)
        serverCard.addView(serverBtnRow)

        val importBinRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val importBinBtn = createModernButton("📂 Import Binary from USB / Storage", isGold = true) {
            showImportBinaryModal()
        }
        val exportLogsBtn = createModernButton("💾 Save Logs to Downloads") {
            val savedPath = LogCollector.flushFullLogToDownloads(this)
            Toast.makeText(this, "✓ Logs written to: $savedPath", Toast.LENGTH_LONG).show()
            appendServerLogLine("[Logs] Saved to $savedPath")
        }
        importBinRow.addView(importBinBtn)
        importBinRow.addView(exportLogsBtn)
        serverCard.addView(importBinRow)

        val compatibleNames = BinaryUpdater.getCompatibleAssetNamesForDevice().take(2).joinToString(" or ")
        val importHintText = TextView(this).apply {
            text = "KitKat TV Tip: Import '$compatibleNames' (NOT jiotv_go-android-arm). Real-time logs auto-saved to: ${LogCollector.getPrimaryLogFilePath()}"
            setTextColor(TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(6), 0, dp(6))
        }
        serverCard.addView(importHintText)

        // Live Server Diagnostic Console Box
        val logLabel = TextView(this).apply {
            text = "Live Server Output / Diagnostics (Auto-writes to /sdcard/Download/jiotv_go_logs.txt):"
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(4))
        }
        serverCard.addView(logLabel)

        accountServerLogText = TextView(this).apply {
            text = if (recentServerLogs.isNotEmpty()) {
                recentServerLogs.joinToString("\n")
            } else {
                "Waiting for server logs..."
            }
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            background = roundedBg(BG_INPUT, BORDER_SUBTLE, dp(1), dp(8))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            maxLines = 8
            ellipsize = TextUtils.TruncateAt.START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        serverCard.addView(accountServerLogText)

        root.addView(serverCard)
        return root
    }

    // =========================================================================
    // CUSTOM GLASS OVERLAYS (ASSIGN CHANNEL # MODAL & GLOBAL QUICK NUMBER OSD)
    // =========================================================================
    private fun buildQuickNumberOsd() {
        quickNumberOsdCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            background = roundedBg(Color.parseColor("#F20F172A"), ACCENT_CYAN, dp(2), dp(14))
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        val osdHint = TextView(this).apply {
            text = "DIRECT CHANNEL JUMP"
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
        }
        quickNumberOsdDigits = TextView(this).apply {
            text = "#1"
            setTextColor(ACCENT_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            typeface = Typeface.DEFAULT_BOLD
        }
        quickNumberOsdPreview = TextView(this).apply {
            text = "Loading..."
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }

        quickNumberOsdCard.addView(osdHint)
        quickNumberOsdCard.addView(quickNumberOsdDigits)
        quickNumberOsdCard.addView(quickNumberOsdPreview)

        rootFrame.addView(
            quickNumberOsdCard,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(24)
                rightMargin = dp(24)
            }
        )
    }

    private fun buildAssignNumberModalOverlay() {
        assignModalOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#D9050811"))
            isClickable = true
            isFocusable = true
        }

        val modalCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedBg(BG_CARD, ACCENT_GOLD, dp(2), dp(16))
            setPadding(dp(26), dp(22), dp(26), dp(22))
            layoutParams = FrameLayout.LayoutParams(
                dp(440),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        assignModalTitle = TextView(this).apply {
            text = "★ Assign Custom Channel #"
            setTextColor(ACCENT_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
        }
        assignModalSubtitle = TextView(this).apply {
            text = "Type a number (1..9999) on your remote or tap below:"
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(12))
        }
        assignModalDigitsText = TextView(this).apply {
            text = "#1"
            setTextColor(ACCENT_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBg(BG_INPUT, BORDER_SUBTLE, dp(1), dp(10))
            setPadding(dp(24), dp(8), dp(24), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        modalCard.addView(assignModalTitle)
        modalCard.addView(assignModalSubtitle)
        modalCard.addView(assignModalDigitsText)

        // Compact 2-row digit strip (1-5, 6-0, Backspace)
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { bottomMargin = dp(6) }
        }
        for (d in listOf("1", "2", "3", "4", "5", "⌫")) {
            val b = Button(this).apply {
                text = d
                setTextColor(createFocusableTextColors(TEXT_PRIMARY, Color.BLACK))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                background = createKeypadButtonDrawable()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    leftMargin = dp(2)
                    rightMargin = dp(2)
                }
                setOnClickListener {
                    if (d == "⌫") {
                        if (assignModalBuffer.isNotEmpty()) {
                            assignModalBuffer.deleteCharAt(assignModalBuffer.length - 1)
                        }
                    } else if (assignModalBuffer.length < 4) {
                        assignModalBuffer.append(d)
                    }
                    assignModalDigitsText.text = if (assignModalBuffer.isEmpty()) "#-" else "#$assignModalBuffer"
                }
            }
            row1.addView(b)
        }
        modalCard.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { bottomMargin = dp(16) }
        }
        for (d in listOf("6", "7", "8", "9", "0", "CLR")) {
            val b = Button(this).apply {
                text = d
                setTextColor(createFocusableTextColors(TEXT_PRIMARY, Color.BLACK))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                background = createKeypadButtonDrawable()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    leftMargin = dp(2)
                    rightMargin = dp(2)
                }
                setOnClickListener {
                    if (d == "CLR") {
                        assignModalBuffer.clear()
                    } else if (assignModalBuffer.length < 4) {
                        assignModalBuffer.append(d)
                    }
                    assignModalDigitsText.text = if (assignModalBuffer.isEmpty()) "#-" else "#$assignModalBuffer"
                }
            }
            row2.addView(b)
        }
        modalCard.addView(row2)

        // Action Buttons Row
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val saveBtn = createModernButton("★ Save # to My List", isGold = true) {
            commitAssignModal(alsoSetStartup = false)
        }
        val saveStartupBtn = createModernButton("🚀 Save & Set Startup") {
            commitAssignModal(alsoSetStartup = true)
        }
        val cancelBtn = createModernButton("Cancel") {
            assignModalOverlay.visibility = View.GONE
        }
        actionRow.addView(saveBtn)
        actionRow.addView(saveStartupBtn)
        actionRow.addView(cancelBtn)
        modalCard.addView(actionRow)

        assignModalOverlay.addView(modalCard)
        rootFrame.addView(
            assignModalOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun showAssignNumberModal(channel: NumberedChannel, setAsStartupToo: Boolean = false) {
        assignModalTargetChannel = channel
        assignModalBuffer.clear()
        assignModalBuffer.append(channel.assignedNumber)
        assignModalTitle.text = if (setAsStartupToo) {
            "🚀 Set Startup Channel # for ${channel.channelName}"
        } else {
            "★ Assign # for ${channel.channelName}"
        }
        assignModalSubtitle.text = "Type a number (1..9999) on remote or tap digits below:"
        assignModalDigitsText.text = "#${channel.assignedNumber}"
        assignModalOverlay.visibility = View.VISIBLE
    }

    private fun commitAssignModal(alsoSetStartup: Boolean) {
        val ch = assignModalTargetChannel ?: return
        val desired = assignModalBuffer.toString().toIntOrNull()
        if (desired == null || desired !in 1..9999) {
            Toast.makeText(this, "Enter a valid number between 1 and 9999", Toast.LENGTH_SHORT).show()
            return
        }
        val assigned = CustomChannelStore.assignCustomNumber(this, ch, desired)
        if (alsoSetStartup) {
            prefs.myPreferences.apply {
                startupLaunchMode = SkySharedPref.STARTUP_SPECIFIC_CHANNEL
                startupChannelNumber = assigned
                startupChannelId = ch.channelId
                startupChannelName = ch.channelName
            }
        }
        assignModalOverlay. visibility = View.GONE
        populateCategoryPills()
        refreshAllPagesData()
        Toast.makeText(
            this,
            if (alsoSetStartup) {
                "${ch.channelName} is now #$assigned & set as Startup Channel!"
            } else {
                "${ch.channelName} saved as Channel #$assigned in My List!"
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    // =========================================================================
    // IMPORT BINARY FROM FILE MANAGER / USB / STORAGE MODAL (KITKAT TV READY)
    // =========================================================================
    private fun buildImportBinaryModalOverlay() {
        importBinaryModalOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#E6000000"))
            isClickable = true
            isFocusable = true
        }

        val modalCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(BG_CARD, Color.WHITE, dp(2), dp(16))
            setPadding(dp(22), dp(18), dp(22), dp(18))
            layoutParams = FrameLayout.LayoutParams(
                dp(680),
                dp(460),
                Gravity.CENTER
            )
        }

        // Modal Header Row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val modalTitle = TextView(this).apply {
            text = "📂 Import JioTV Go Binary from File Manager / USB"
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val expectedNames = BinaryUpdater.getCompatibleAssetNamesForDevice().take(2).joinToString(" or ")
        val deviceAbis = BinaryUpdater.getDeviceAbiList().joinToString()
        importModalStatusText = TextView(this).apply {
            text = "Compatible with this TV: $expectedNames (ABI: $deviceAbis) — Raw binary or .zip supported"
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, 0)
        }
        titleCol.addView(modalTitle)
        titleCol.addView(importModalStatusText)

        val closeBtn = createModernButton("✕ Close") {
            importBinaryModalOverlay.visibility = View.GONE
        }
        headerRow.addView(titleCol)
        headerRow.addView(closeBtn)
        modalCard.addView(headerRow)

        // Top Action Bar: External File Manager App + Auto-Scan + Quick Storage Shortcuts
        val actionScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(10)
            }
        }
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val externalPickerBtn = createModernButton("📱 Open External File Manager App", isGold = true) {
            launchExternalFileManagerPicker()
        }
        val autoScanBtn = createModernButton("🔍 Auto-Find Binary", isPrimary = true) {
            showAutoScannedBinariesInModal()
        }
        val downloadsBtn = createModernButton("📥 Downloads") {
            @Suppress("DEPRECATION")
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            browseDirectoryInImportModal(if (dl != null && dl.exists()) dl else File("/sdcard/Download"))
        }
        val sdcardBtn = createModernButton("💾 Internal Storage") {
            @Suppress("DEPRECATION")
            val sd = Environment.getExternalStorageDirectory()
            browseDirectoryInImportModal(if (sd != null && sd.exists()) sd else File("/sdcard"))
        }
        val usbMntBtn = createModernButton("🔌 USB / /storage") {
            val storageDir = File("/storage")
            val mntDir = File("/mnt")
            browseDirectoryInImportModal(if (storageDir.exists() && storageDir.canRead()) storageDir else mntDir)
        }

        actionBar.addView(externalPickerBtn)
        actionBar.addView(autoScanBtn)
        actionBar.addView(downloadsBtn)
        actionBar.addView(sdcardBtn)
        actionBar.addView(usbMntBtn)
        actionScroll.addView(actionBar)
        modalCard.addView(actionScroll)

        // Current Directory Path Strip
        importModalPathText = TextView(this).apply {
            text = "📁 Location: /sdcard/Download"
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(BG_INPUT, BORDER_SUBTLE, dp(1), dp(6))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        modalCard.addView(importModalPathText)

        // Built-in TV Remote D-Pad File Browser ListView
        importModalFileListView = ListView(this).apply {
            divider = null
            dividerHeight = dp(6)
            isFocusable = true
            isFocusableInTouchMode = false
            selector = roundedBg(Color.TRANSPARENT, Color.WHITE, dp(2), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setOnFocusChangeListener { _, _ -> importModalAdapter?.notifyDataSetChanged() }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    importModalAdapter?.notifyDataSetChanged()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {
                    importModalAdapter?.notifyDataSetChanged()
                }
            }
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                val entry = importModalEntries.getOrNull(position) ?: return@OnItemClickListener
                if (entry.file.isDirectory) {
                    browseDirectoryInImportModal(entry.file)
                } else {
                    performBinaryImportFromFile(entry.file)
                }
            }
        }

        importModalAdapter = object : BaseAdapter() {
            override fun getCount(): Int = importModalEntries.size
            override fun getItem(position: Int): Any = importModalEntries[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    val title = TextView(this@MainActivity).apply {
                        id = 601
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val sub = TextView(this@MainActivity).apply {
                        id = 602
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(12), 0, 0, 0)
                    }
                    addView(title)
                    addView(sub)
                }

                val item = importModalEntries[position]
                val isSelectedRow = importModalFileListView.hasFocus() &&
                    position == importModalFileListView.selectedItemPosition

                val titleTv = row.findViewById<TextView>(601)
                val subTv = row.findViewById<TextView>(602)

                if (isSelectedRow) {
                    row.background = roundedBg(Color.WHITE, Color.WHITE, dp(2), dp(8))
                    titleTv.text = "▶ ${item.displayTitle}"
                    titleTv.setTextColor(Color.BLACK)
                    subTv.text = item.displaySubtitle
                    subTv.setTextColor(Color.BLACK)
                } else {
                    val strokeColor = if (item.isCandidateHighlight) ACCENT_GOLD else BORDER_SUBTLE
                    val strokeW = if (item.isCandidateHighlight) dp(2) else dp(1)
                    row.background = roundedBg(BG_INPUT, strokeColor, strokeW, dp(8))
                    titleTv.text = item.displayTitle
                    titleTv.setTextColor(if (item.isCandidateHighlight) Color.WHITE else TEXT_PRIMARY)
                    subTv.text = item.displaySubtitle
                    subTv.setTextColor(if (item.isCandidateHighlight) ACCENT_GOLD else TEXT_SECONDARY)
                }
                return row
            }
        }
        importModalFileListView.adapter = importModalAdapter
        modalCard.addView(importModalFileListView)

        importBinaryModalOverlay.addView(modalCard)
        rootFrame.addView(
            importBinaryModalOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun showImportBinaryModal() {
        val expectedNames = BinaryUpdater.getCompatibleAssetNamesForDevice().take(2).joinToString(" or ")
        val deviceAbis = BinaryUpdater.getDeviceAbiList().joinToString()
        importModalStatusText.text = "Compatible with this TV: $expectedNames (ABI: $deviceAbis) — Select binary or .zip below"
        importModalStatusText.setTextColor(TEXT_SECONDARY)
        importBinaryModalOverlay.visibility = View.VISIBLE

        val candidates = BinaryUpdater.scanStorageForCandidateBinaries()
        if (candidates.isNotEmpty()) {
            showAutoScannedBinariesInModal()
        } else {
            @Suppress("DEPRECATION")
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val startDir = when {
                dl != null && dl.exists() && dl.canRead() -> dl
                File("/sdcard/Download").exists() -> File("/sdcard/Download")
                File("/sdcard").exists() -> File("/sdcard")
                else -> File("/storage")
            }
            browseDirectoryInImportModal(startDir)
        }

        importModalFileListView.post {
            importModalFileListView.requestFocus()
            if (importModalEntries.isNotEmpty()) {
                importModalFileListView.setSelection(0)
            }
        }
    }

    private fun showAutoScannedBinariesInModal() {
        val candidates = BinaryUpdater.scanStorageForCandidateBinaries()
        if (candidates.isEmpty()) {
            importModalPathText.text = "🔍 Auto-Scan: No 'jiotv_go*' files found in Downloads/USB — Browsing /sdcard/Download"
            @Suppress("DEPRECATION")
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            browseDirectoryInImportModal(if (dl != null && dl.exists()) dl else File("/sdcard"))
            return
        }

        importModalPathText.text = "🔍 Auto-Found ${candidates.size} Candidate Binary File(s) across Storage & USB:"
        importModalEntries = candidates.map { f ->
            val sizeMb = String.format("%.1f MB", f.length() / (1024.0 * 1024.0))
            FileBrowserEntry(
                file = f,
                displayTitle = "⚡ ${f.name}   (${f.parentFile?.absolutePath ?: ""})",
                displaySubtitle = "IMPORT ($sizeMb)",
                isParentLink = false,
                isCandidateHighlight = true
            )
        }
        importModalAdapter?.notifyDataSetChanged()
        importModalFileListView.post {
            importModalFileListView.requestFocus()
            if (importModalEntries.isNotEmpty()) {
                importModalFileListView.setSelection(0)
            }
        }
    }

    private fun browseDirectoryInImportModal(dir: File) {
        importModalCurrentDir = dir
        importModalPathText.text = "📁 Folder: ${dir.absolutePath}"

        val entries = mutableListOf<FileBrowserEntry>()
        val parent = dir.parentFile
        if (parent != null && dir.absolutePath != "/") {
            entries.add(
                FileBrowserEntry(
                    file = parent,
                    displayTitle = "⬆️  ..  (Parent Folder: ${parent.name.ifEmpty { "/" }})",
                    displaySubtitle = "OPEN FOLDER",
                    isParentLink = true
                )
            )
        }

        val rawFiles = try {
            dir.listFiles()?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val sorted = rawFiles.sortedWith(
            compareByDescending<File> { it.isDirectory }
                .thenByDescending {
                    val n = it.name.lowercase()
                    n.contains("jiotv_go") || n.contains("jiotv")
                }
                .thenBy { it.name.lowercase() }
        )

        for (f in sorted) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                entries.add(
                    FileBrowserEntry(
                        file = f,
                        displayTitle = "📁  ${f.name}/",
                        displaySubtitle = "FOLDER"
                    )
                )
            } else {
                val lower = f.name.lowercase()
                val isCandidate = (lower.contains("jiotv_go") || lower.contains("jiotv-go") || lower.contains("jiotvgo")) &&
                    !lower.endsWith(".apk")
                val sizeStr = if (f.length() >= 1024 * 1024) {
                    String.format("%.1f MB", f.length() / (1024.0 * 1024.0))
                } else {
                    "${f.length() / 1024} KB"
                }
                entries.add(
                    FileBrowserEntry(
                        file = f,
                        displayTitle = if (isCandidate) "⚡ ${f.name}" else "📄  ${f.name}",
                        displaySubtitle = if (isCandidate) "★ RECOMMENDED ($sizeStr)" else sizeStr,
                        isCandidateHighlight = isCandidate
                    )
                )
            }
        }

        importModalEntries = entries
        importModalAdapter?.notifyDataSetChanged()
    }

    private fun launchExternalFileManagerPicker() {
        val intents = listOf(
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            Intent("org.openintents.action.PICK_FILE")
        )

        for (candidate in intents) {
            try {
                startActivityForResult(
                    Intent.createChooser(candidate, "Select ${BinaryUpdater.getTargetBinaryAssetName()} Binary"),
                    REQ_CODE_PICK_BINARY_FILE
                )
                return
            } catch (_: Exception) {
            }
        }

        Toast.makeText(
            this,
            "No external File Manager app found on this TV. Use the built-in folder list below!",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE_PICK_BINARY_FILE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            importBinaryModalOverlay.visibility = View.VISIBLE
            importModalStatusText.text = "Importing selected binary from File Manager..."
            importModalStatusText.setTextColor(ACCENT_GOLD)

            uiScope.launch {
                val res = BinaryUpdater.importBinaryFromUri(
                    context = this@MainActivity,
                    uri = uri,
                    onProgress = { msg ->
                        mainHandler.post {
                            importModalStatusText.text = msg
                            updateServerStatusUi(false, msg)
                        }
                    }
                )
                handleBinaryImportResult(res)
            }
        }
    }

    private fun performBinaryImportFromFile(file: File) {
        importModalStatusText.text = "Importing ${file.name}..."
        importModalStatusText.setTextColor(ACCENT_GOLD)
        uiScope.launch {
            val res = BinaryUpdater.importBinaryFromFile(
                context = this@MainActivity,
                sourceFile = file,
                onProgress = { msg ->
                    mainHandler.post {
                        importModalStatusText.text = msg
                        updateServerStatusUi(false, msg)
                    }
                }
            )
            handleBinaryImportResult(res)
        }
    }

    private fun handleBinaryImportResult(res: Result<File>) {
        res.onSuccess { installedFile ->
            importBinaryModalOverlay.visibility = View.GONE
            val port = prefs.myPreferences.jtvGoServerPort
            lastServerErrorMessage = null
            LogCollector.log("[MainActivity] Binary imported successfully: ${installedFile.absolutePath} (${installedFile.length()} bytes). Starting server on port $port...")
            appendServerLogLine("[Import] Installed ${installedFile.name} (${installedFile.length() / 1024} KB)")
            updateServerStatusUi(false, "Starting ${installedFile.name} on port $port...")
            Toast.makeText(
                this,
                "✓ Binary imported (${installedFile.name})! Starting server...",
                Toast.LENGTH_LONG
            ).show()
            startJioTvServerService(forceRestart = true)
            refreshAccountServerPage()
        }.onFailure { err ->
            LogCollector.log("[MainActivity] Binary import failed: ${err.message}")
            appendServerLogLine("[Import Error] ${err.message}")
            importModalStatusText.text = "✕ Import Failed: ${err.message}"
            importModalStatusText.setTextColor(ACCENT_RED)
            Toast.makeText(this, "Import failed: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    // =========================================================================
    // DATA REFRESH & FILTERING ACROSS ALL 5 PAGES
    // =========================================================================
    private fun refreshAllPagesData() {
        rebuildDisplayedChannels()
        refreshDialerDirectoryList()
        if (currentPage == PAGE_KEYPAD) {
            updateDialerPreview(autoLaunchCountdown = false)
        } else if (currentPage == PAGE_MY_LIST) {
            refreshMyListStudioPage()
        } else if (currentPage == PAGE_STARTUP) {
            refreshStartupSettingsPage()
        } else if (currentPage == PAGE_ACCOUNT) {
            refreshAccountServerPage()
        }
    }

    private fun rebuildDisplayedChannels() {
        val port = prefs.myPreferences.jtvGoServerPort
        val isCustomMode = (selectedLiveCategoryPill == -1) ||
            (prefs.myPreferences.listMode == SkySharedPref.LIST_MODE_CUSTOM)

        val baseList = if (isCustomMode) {
            CustomChannelStore.getCustomNumberedChannels(this, allRawChannels, port)
        } else {
            CustomChannelStore.getAllNumberedChannels(this, allRawChannels, port)
        }

        val catId = if (isCustomMode) 0 else selectedLiveCategoryPill
        val langId = prefs.myPreferences.filterLanguageId
        val query = liveTvSearchInput.text?.toString()?.trim()?.lowercase() ?: ""

        val filtered = baseList.filter { ch ->
            val matchesCat = isCustomMode || catId == 0 || ch.categoryId == catId
            val matchesLang = isCustomMode || langId == 0 || ch.languageId == langId
            val matchesQuery = query.isEmpty() ||
                ch.channelName.lowercase().contains(query) ||
                ch.assignedNumber.toString() == query ||
                "#${ch.assignedNumber}" == query
            matchesCat && matchesLang && matchesQuery
        }

        displayedChannels = filtered
        gridAdapter?.notifyDataSetChanged()

        val sectionName = if (isCustomMode) {
            "★ My Custom Channels"
        } else {
            CategoryMap[catId] ?: "Live TV Channels"
        }
        liveTvTitleText.text = sectionName
        liveTvCountBadge.text = "${filtered.size} Channels"
        liveTvStartupBadge.text = getStartupSummaryLabel()

        if (isCustomMode && baseList.isEmpty()) {
            liveTvEmptyStateBox.visibility = View.VISIBLE
            channelGridView.visibility = View.GONE
        } else {
            liveTvEmptyStateBox.visibility = View.GONE
            channelGridView.visibility = View.VISIBLE
        }
    }

    private fun refreshMyListStudioPage() {
        val port = prefs.myPreferences.jtvGoServerPort
        studioCustomItems = CustomChannelStore.getCustomNumberedChannels(this, allRawChannels, port)
        studioCustomHeader.text = "★ Your Custom Channels (${studioCustomItems.size})"
        studioCustomAdapter?.notifyDataSetChanged()

        val isCustomDefault = prefs.myPreferences.listMode == SkySharedPref.LIST_MODE_CUSTOM
        studioModeToggleBtn.text = if (isCustomDefault) {
            "Default Guide: ★ My Custom List"
        } else {
            "Default Guide: All Channels"
        }

        filterStudioCatalogList()
    }

    private fun filterStudioCatalogList() {
        val port = prefs.myPreferences.jtvGoServerPort
        val allList = CustomChannelStore.getAllNumberedChannels(this, allRawChannels, port)
        val q = studioCatalogSearchInput.text?.toString()?.trim()?.lowercase() ?: ""
        studioCatalogItems = if (q.isEmpty()) {
            allList
        } else {
            allList.filter {
                it.channelName.lowercase().contains(q) || it.assignedNumber.toString() == q
            }
        }
        studioCatalogAdapter?.notifyDataSetChanged()
    }

    private fun refreshStartupSettingsPage() {
        val mode = prefs.myPreferences.startupLaunchMode
        styleTogglePill(startupModeOffBtn, mode == SkySharedPref.STARTUP_NONE)
        styleTogglePill(startupModeLastBtn, mode == SkySharedPref.STARTUP_LAST_CHANNEL)
        styleTogglePill(startupModeSpecificBtn, mode == SkySharedPref.STARTUP_SPECIFIC_CHANNEL, isGold = true)

        val targetNum = prefs.myPreferences.startupChannelNumber.coerceAtLeast(1)
        val matched = CustomChannelStore.findChannelByNumber(
            this,
            targetNum,
            allRawChannels,
            prefs.myPreferences.jtvGoServerPort
        )
        val targetName = matched?.channelName ?: prefs.myPreferences.startupChannelName ?: "Channel #$targetNum"
        startupSpecificInfoText.text = "Configured Startup Channel: #$targetNum ($targetName)"

        val isNumpadDefault = prefs.myPreferences.uiMode == SkySharedPref.UI_MODE_NUMPAD
        styleTogglePill(startupDefaultUiRemoteBtn, !isNumpadDefault)
        styleTogglePill(startupDefaultUiNumpadBtn, isNumpadDefault)

        // Rebuild Quality Pills
        qualityPillsContainer.removeAllViews()
        val currentQ = prefs.myPreferences.filterQX?.lowercase() ?: "auto"
        val qualities = listOf(
            "auto" to "⚡ Adaptive Auto (Fastest)",
            "high" to "High (HD)",
            "medium" to "Medium",
            "low" to "Low (Data Saver)"
        )
        for ((qCode, qLabel) in qualities) {
            val btn = createModernButton(qLabel, isPrimary = (currentQ == qCode)) {
                prefs.myPreferences.filterQX = qCode
                refreshStartupSettingsPage()
                rebuildDisplayedChannels()
            }
            qualityPillsContainer.addView(btn)
        }

        // Rebuild Language Pills
        languagePillsContainer.removeAllViews()
        val currentLang = prefs.myPreferences.filterLanguageId
        for ((langId, langName) in LanguageMap) {
            val btn = createCategoryPillButton(
                label = langName,
                isSelected = (currentLang == langId),
                isGold = false
            ) {
                prefs.myPreferences.filterLanguageId = langId
                refreshStartupSettingsPage()
                rebuildDisplayedChannels()
            }
            languagePillsContainer.addView(btn)
        }
    }

    private fun refreshAccountServerPage() {
        val port = prefs.myPreferences.jtvGoServerPort
        val online = serverOnline || BinaryService.isRunning
        val binName = prefs.myPreferences.jtvGoBinaryName ?: BinaryUpdater.getTargetBinaryAssetName()
        val binVer = prefs.myPreferences.jtvGoBinaryVersion ?: "latest"

        accountServerStatusText.text = when {
            online -> "● Server ONLINE — Listening on http://127.0.0.1:$port"
            !lastServerErrorMessage.isNullOrBlank() -> "● Server ERROR: $lastServerErrorMessage"
            else -> "● Server STOPPED (Port $port)"
        }
        accountServerStatusText.setTextColor(if (online) ACCENT_GREEN else ACCENT_GOLD)
        accountBinaryInfoText.text = "Binary: $binName ($binVer)  •  ABIs: ${BinaryUpdater.getDeviceAbiList().joinToString()}"

        if (::accountServerLogText.isInitialized && recentServerLogs.isNotEmpty()) {
            accountServerLogText.text = recentServerLogs.joinToString("\n")
        }

        val isPublic = !prefs.myPreferences.serveLocal
        accountPublicToggleBtn.text = "LAN Access (--public): ${if (isPublic) "ON" else "OFF"}"
        styleTogglePill(accountPublicToggleBtn, isPublic)

        val autoBoot = prefs.myPreferences.autoStartOnBoot
        accountBootToggleBtn.text = "Auto-Start on Device Boot: ${if (autoBoot) "ON" else "OFF"}"
        styleTogglePill(accountBootToggleBtn, autoBoot)

        // Probe actual TCP port on a background thread so we never trigger NetworkOnMainThreadException
        Thread({
            val liveListening = BinaryService.isPortListening(port, 150)
            if (liveListening != serverOnline) {
                mainHandler.post {
                    serverOnline = liveListening
                    railStatusDot.setTextColor(if (liveListening) ACCENT_GREEN else ACCENT_GOLD)
                    if (liveListening) {
                        railStatusLabel.text = "Online • Port $port"
                        accountServerStatusText.text = "● Server ONLINE — Listening on http://127.0.0.1:$port"
                        accountServerStatusText.setTextColor(ACCENT_GREEN)
                    }
                }
            }
        }, "AccountPage-PortCheck").start()
    }

    // =========================================================================
    // DIALER & QUICK NUMBER OSD LOGIC
    // =========================================================================
    private fun onDialerDigitPressed(digit: Char, autoLaunchAfterDelay: Boolean) {
        if (dialerBuffer.length >= 4) {
            dialerBuffer.clear()
        }
        dialerBuffer.append(digit)
        updateDialerPreview(autoLaunchCountdown = autoLaunchAfterDelay)
    }

    private fun updateDialerPreview(autoLaunchCountdown: Boolean) {
        mainHandler.removeCallbacks(commitDialerAutoRunnable)
        val numStr = dialerBuffer.toString()
        if (numStr.isEmpty()) {
            dialerDigitsDisplay.text = "#-"
            dialerPreviewChannel = null
            dialerPreviewTitle.text = "Type a Channel #"
            dialerPreviewSubtitle.text = "Press 0-9 on remote or tap keypad"
            dialerPreviewLogo.setImageBitmap(null)
            return
        }

        val num = numStr.toIntOrNull() ?: return
        dialerDigitsDisplay.text = "#$num"
        val port = prefs.myPreferences.jtvGoServerPort
        val matched = CustomChannelStore.findChannelByNumber(this, num, allRawChannels, port)
        dialerPreviewChannel = matched

        if (matched != null) {
            val tag = if (matched.isCustomAssigned) "★ Custom #$num" else "Channel #$num"
            dialerPreviewTitle.text = matched.channelName
            dialerPreviewSubtitle.text = if (autoLaunchCountdown) {
                "$tag • Auto-launching in 1.2s (or press OK)"
            } else {
                "$tag • Press WATCH or OK to launch"
            }
            LogoLoader.load(this, matched.logoUrl, dialerPreviewLogo)
            if (autoLaunchCountdown) {
                mainHandler.postDelayed(commitDialerAutoRunnable, 1200L)
            }
        } else {
            dialerPreviewTitle.text = "No channel at #$num"
            dialerPreviewSubtitle.text = "Use '⭐ My List & #' to assign #$num"
            dialerPreviewLogo.setImageBitmap(null)
        }
    }

    private fun refreshDialerDirectoryList() {
        val port = prefs.myPreferences.jtvGoServerPort
        val customList = CustomChannelStore.getCustomNumberedChannels(this, allRawChannels, port)
        val directoryItems = if (customList.isNotEmpty()) {
            dialerDirectoryHeader.text = "★ Your Custom Numbers (${customList.size})"
            customList
        } else {
            val allList = CustomChannelStore.getAllNumberedChannels(this, allRawChannels, port)
            dialerDirectoryHeader.text = "Channel Directory (${allList.size})"
            allList
        }

        dialerDirectoryListView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = directoryItems.size
            override fun getItem(position: Int): Any = directoryItems[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedBg(BG_INPUT, BORDER_SUBTLE, dp(1), dp(8))
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    val badge = TextView(this@MainActivity).apply {
                        id = 201
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        minWidth = dp(54)
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                    }
                    val title = TextView(this@MainActivity).apply {
                        id = 202
                        setTextColor(TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            leftMargin = dp(10)
                        }
                    }
                    addView(badge)
                    addView(title)
                }

                val item = directoryItems[position]
                val isSelectedRow = dialerDirectoryListView.hasFocus() && position == dialerDirectoryListView.selectedItemPosition
                row.background = if (isSelectedRow) {
                    roundedBg(BG_CARD_ELEVATED, Color.WHITE, dp(2), dp(8))
                } else {
                    roundedBg(BG_INPUT, BORDER_SUBTLE, dp(1), dp(8))
                }

                val badge = row.findViewById<TextView>(201)
                val title = row.findViewById<TextView>(202)

                badge.text = if (item.isCustomAssigned) "★ #${item.assignedNumber}" else "#${item.assignedNumber}"
                badge.setTextColor(Color.WHITE)
                badge.background = roundedBg(
                    if (item.isCustomAssigned) ACCENT_GOLD else BG_CARD_ELEVATED,
                    if (item.isCustomAssigned) Color.TRANSPARENT else BORDER_SUBTLE,
                    if (item.isCustomAssigned) 0 else dp(1),
                    dp(6)
                )
                title.text = if (isSelectedRow) "▶ ${item.channelName}" else item.channelName
                return row
            }
        }
    }

    private fun onQuickOsdDigitPressed(digit: Char) {
        mainHandler.removeCallbacks(commitQuickNumberRunnable)
        if (quickOsdBuffer.length >= 4) {
            quickOsdBuffer.clear()
        }
        quickOsdBuffer.append(digit)
        val num = quickOsdBuffer.toString().toIntOrNull() ?: return

        quickNumberOsdDigits.text = "#$num"
        val matched = CustomChannelStore.findChannelByNumber(
            this,
            num,
            allRawChannels,
            prefs.myPreferences.jtvGoServerPort
        )
        quickNumberOsdPreview.text = matched?.channelName ?: "Channel #$num not assigned"
        quickNumberOsdCard.visibility = View.VISIBLE

        mainHandler.postDelayed(commitQuickNumberRunnable, 1200L)
    }

    private fun commitQuickOsdNumber() {
        val num = quickOsdBuffer.toString().toIntOrNull()
        quickOsdBuffer.clear()
        quickNumberOsdCard.visibility = View.GONE
        if (num == null) return

        val matched = CustomChannelStore.findChannelByNumber(
            this,
            num,
            allRawChannels,
            prefs.myPreferences.jtvGoServerPort
        )
        if (matched != null) {
            launchChannelPlayback(matched)
        } else {
            Toast.makeText(this, "No channel found at #$num", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // BINARY & SERVER BOOTSTRAP + DIRECT STARTUP CHANNEL LAUNCH
    // =========================================================================
    private fun bootstrapBinaryAndServer(forceUpdate: Boolean = false) {
        val port = prefs.myPreferences.jtvGoServerPort

        Thread({
            if (!forceUpdate && BinaryService.isPortListening(port, 120)) {
                mainHandler.post {
                    updateServerStatusUi(true, "Online • Port $port")
                    refreshChannelsFromServer(triggerStartupLaunchIfReady = true)
                }
                return@Thread
            }

            uiScope.launch {
                updateServerStatusUi(false, "Checking binary...")
                val result = BinaryUpdater.ensureBinaryReady(
                    context = this@MainActivity,
                    forceUpdate = forceUpdate,
                    onProgress = { msg, _ ->
                        mainHandler.post { updateServerStatusUi(false, msg) }
                    }
                )

                result.onSuccess {
                    lastServerErrorMessage = null
                    updateServerStatusUi(false, "Starting port $port...")
                    startJioTvServerService()
                }.onFailure { err ->
                    appendServerLogLine("External binary unavailable (${err.message}); starting Embedded Engine...")
                    updateServerStatusUi(false, "Starting Embedded Engine on port $port...")
                    startJioTvServerService()
                }
            }
        }, "Bootstrap-Check").start()
    }

    private fun startJioTvServerService(forceRestart: Boolean = false) {
        val configManager = JTVConfigurationManager.getInstance(this)
        configManager.saveJTVConfiguration()

        val serviceIntent = Intent(this, BinaryService::class.java).apply {
            putExtra("arguments", emptyArray<String>())
            putExtra("force_restart", forceRestart)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun refreshChannelsFromServer(triggerStartupLaunchIfReady: Boolean) {
        val port = prefs.myPreferences.jtvGoServerPort
        if (triggerStartupLaunchIfReady && !hasHandledColdStartLaunch) {
            if (tryDirectStartupLaunch()) {
                hasHandledColdStartLaunch = true
            }
        }

        Thread({
            val fetched = CustomChannelStore.fetchChannelsFromServer(this, port)
            if (!fetched.isNullOrEmpty()) {
                mainHandler.post {
                    allRawChannels = fetched
                    populateCategoryPills()
                    refreshAllPagesData()
                    if (triggerStartupLaunchIfReady && !hasHandledColdStartLaunch) {
                        hasHandledColdStartLaunch = true
                        tryDirectStartupLaunch()
                    }
                }
            }
        }, "Fetch-Channels").start()
    }

    private fun tryDirectStartupLaunch(): Boolean {
        val mode = prefs.myPreferences.startupLaunchMode
        if (mode == SkySharedPref.STARTUP_NONE) return false

        val port = prefs.myPreferences.jtvGoServerPort
        when (mode) {
            SkySharedPref.STARTUP_LAST_CHANNEL -> {
                val lastId = prefs.myPreferences.lastChannelId
                if (!lastId.isNullOrBlank()) {
                    val lastNum = prefs.myPreferences.lastChannelNumber
                    val lastName = prefs.myPreferences.lastChannelName ?: "Channel #$lastNum"
                    val lastLogo = prefs.myPreferences.lastLogoUrl ?: ""
                    val streamUrl = CustomChannelStore.buildPlaybackUrl(
                        port,
                        lastId,
                        prefs.myPreferences.filterQX
                    )
                    val ch = NumberedChannel(
                        channelId = lastId,
                        assignedNumber = lastNum,
                        isCustomAssigned = true,
                        channelName = lastName,
                        logoUrl = lastLogo,
                        streamUrl = streamUrl,
                        categoryId = 0,
                        languageId = 0,
                        isHD = false
                    )
                    launchChannelPlayback(ch)
                    return true
                }
            }
            SkySharedPref.STARTUP_SPECIFIC_CHANNEL -> {
                val targetNum = prefs.myPreferences.startupChannelNumber
                val matched = CustomChannelStore.findChannelByNumber(this, targetNum, allRawChannels, port)
                if (matched != null) {
                    launchChannelPlayback(matched)
                    return true
                }
                val savedId = prefs.myPreferences.startupChannelId
                if (!savedId.isNullOrBlank()) {
                    val savedName = prefs.myPreferences.startupChannelName ?: "Channel #$targetNum"
                    val ch = NumberedChannel(
                        channelId = savedId,
                        assignedNumber = targetNum,
                        isCustomAssigned = true,
                        channelName = savedName,
                        logoUrl = "",
                        streamUrl = CustomChannelStore.buildPlaybackUrl(port, savedId, prefs.myPreferences.filterQX),
                        categoryId = 0,
                        languageId = 0,
                        isHD = false
                    )
                    launchChannelPlayback(ch)
                    return true
                }
            }
        }
        return false
    }

    private fun launchChannelPlayback(channel: NumberedChannel) {
        val useCustom = prefs.myPreferences.listMode == SkySharedPref.LIST_MODE_CUSTOM &&
            CustomChannelStore.getCustomEntries(this).isNotEmpty()
        val playIntent = PlayerActivity.createIntent(this, channel, useCustom)
        startActivity(playIntent)
    }

    // =========================================================================
    // REMOTE KEY HANDLING (Seamless 0-9 Direct Number Entry & Modal Keys)
    // =========================================================================
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            val digit = keyCodeToDigit(keyCode)

            // 0. If Import Binary Modal Overlay is open, handle Back key and block channel dialing
            if (importBinaryModalOverlay.visibility == View.VISIBLE) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    importBinaryModalOverlay.visibility = View.GONE
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            // 1. If Assign Channel # Modal Overlay is open, handle digits / Enter / Back directly
            if (assignModalOverlay.visibility == View.VISIBLE) {
                if (digit != null) {
                    if (assignModalBuffer.length >= 4) assignModalBuffer.clear()
                    assignModalBuffer.append(digit)
                    assignModalDigitsText.text = "#$assignModalBuffer"
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_DEL) {
                    if (assignModalBuffer.isNotEmpty()) {
                        assignModalBuffer.deleteCharAt(assignModalBuffer.length - 1)
                    }
                    assignModalDigitsText.text = if (assignModalBuffer.isEmpty()) "#-" else "#$assignModalBuffer"
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    assignModalOverlay.visibility = View.GONE
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            // 2. If user isn't typing in an EditText, pressing 0-9 dials a channel number immediately!
            val isTypingText = (liveTvSearchInput.hasFocus() ||
                studioCatalogSearchInput.hasFocus() ||
                loginPhoneInput.hasFocus() ||
                loginOtpInput.hasFocus() ||
                accountPortInput.hasFocus())

            if (digit != null && !isTypingText) {
                if (currentPage == PAGE_KEYPAD) {
                    onDialerDigitPressed(digit, autoLaunchAfterDelay = true)
                } else {
                    onQuickOsdDigitPressed(digit)
                }
                return true
            }

            if (quickOsdBuffer.isNotEmpty() &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                mainHandler.removeCallbacks(commitQuickNumberRunnable)
                commitQuickOsdNumber()
                return true
            }

            if (quickOsdBuffer.isNotEmpty() &&
                (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_BACK)
            ) {
                mainHandler.removeCallbacks(commitQuickNumberRunnable)
                quickOsdBuffer.clear()
                quickNumberOsdCard.visibility = View.GONE
                return true
            }

            // 3. Pressing MENU on a focused grid channel opens Assign Channel # Modal
            if (keyCode == KeyEvent.KEYCODE_MENU && currentPage == PAGE_LIVE_TV && channelGridView.hasFocus()) {
                val pos = channelGridView.selectedItemPosition
                val ch = displayedChannels.getOrNull(pos)
                if (ch != null) {
                    showAssignNumberModal(ch)
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

    // =========================================================================
    // JIOTV OTP LOGIN HTTP CALLS
    // =========================================================================
    private suspend fun sendOtpRequest(port: Int, phoneWithPrefix: String): String = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().put("number", phoneWithPrefix).toString()
            val body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), json)
            val req = Request.Builder()
                .url("http://127.0.0.1:$port/login/sendOTP")
                .post(body)
                .build()
            Tls12SocketFactory.apiHttpClient.newCall(req).execute().use { resp ->
                val respBody = resp.body()?.string() ?: ""
                if (resp.isSuccessful) {
                    "✓ OTP sent to $phoneWithPrefix! Enter OTP below."
                } else {
                    "Send OTP response (${resp.code()}): $respBody"
                }
            }
        } catch (e: Exception) {
            "Failed to contact local server on port $port: ${e.message}"
        }
    }

    private suspend fun verifyOtpRequest(port: Int, phoneWithPrefix: String, otp: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject()
                    .put("number", phoneWithPrefix)
                    .put("otp", otp)
                    .toString()
                val body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), json)
                val req = Request.Builder()
                    .url("http://127.0.0.1:$port/login/verifyOTP")
                    .post(body)
                    .build()
                Tls12SocketFactory.apiHttpClient.newCall(req).execute().use { resp ->
                    val respBody = resp.body()?.string() ?: ""
                    if (resp.isSuccessful) {
                        true to "✓ Login successful! Channels unlocked."
                    } else {
                        false to "Verify failed (${resp.code()}): $respBody"
                    }
                }
            } catch (e: Exception) {
                false to "Error verifying OTP: ${e.message}"
            }
        }

    // =========================================================================
    // MODERN CHANNEL CARD GRID ADAPTER (WITH CRISP YOUTUBE WHITE FOCUS TILE)
    // =========================================================================
    private inner class ChannelGridAdapter : BaseAdapter() {
        override fun getCount(): Int = displayedChannels.size
        override fun getItem(position: Int): Any = displayedChannels[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: ViewHolder
            val card: LinearLayout

            if (convertView == null) {
                card = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundedBg(BG_CARD, BORDER_SUBTLE, dp(1), dp(12))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }

                val topBadgeRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val numBadge = TextView(this@MainActivity).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                }

                val spacer = View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
                }

                val hdBadge = TextView(this@MainActivity).apply {
                    text = "HD"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    typeface = Typeface.DEFAULT_BOLD
                    background = roundedBg(BG_CARD_ELEVATED, BORDER_SUBTLE, dp(1), dp(4))
                    setPadding(dp(5), dp(1), dp(5), dp(1))
                }

                topBadgeRow.addView(numBadge)
                topBadgeRow.addView(spacer)
                topBadgeRow.addView(hdBadge)

                // Clean elevated logo well
                val logoImg = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(58)
                    ).apply {
                        topMargin = dp(8)
                        bottomMargin = dp(8)
                    }
                }

                val nameTv = TextView(this@MainActivity).apply {
                    setTextColor(TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(6), dp(3), dp(6), dp(3))
                }

                card.addView(topBadgeRow)
                card.addView(logoImg)
                card.addView(nameTv)

                holder = ViewHolder(numBadge, hdBadge, logoImg, nameTv)
                card.tag = holder
            } else {
                card = convertView as LinearLayout
                holder = card.tag as ViewHolder
            }

            val item = displayedChannels[position]
            val isFocusedTile = channelGridView.hasFocus() && position == channelGridView.selectedItemPosition

            if (isFocusedTile) {
                // Unmistakable YouTube TV Focused Tile:
                // Elevated surface + thick 3dp Pure White border + Solid White bottom title bar with Black text
                card.background = roundedBg(BG_CARD_ELEVATED, Color.WHITE, dp(3), dp(12))
                holder.numBadge.text = if (item.isCustomAssigned) "★ #${item.assignedNumber}" else "#${item.assignedNumber}"
                holder.numBadge.setTextColor(Color.WHITE)
                holder.numBadge.background = roundedBg(ACCENT_GOLD, Color.WHITE, dp(1), dp(6))

                holder.nameTv.text = "▶ ${item.channelName}"
                holder.nameTv.setTextColor(Color.BLACK)
                holder.nameTv.background = roundedBg(Color.WHITE, Color.TRANSPARENT, 0, dp(6))
            } else {
                // Normal Unfocused Tile: Deep Black card (#161616) with subtle border
                card.background = roundedBg(BG_CARD, BORDER_SUBTLE, dp(1), dp(12))
                holder.numBadge.text = if (item.isCustomAssigned) "★ #${item.assignedNumber}" else "#${item.assignedNumber}"
                if (item.isCustomAssigned) {
                    holder.numBadge.setTextColor(Color.WHITE)
                    holder.numBadge.background = roundedBg(ACCENT_GOLD, Color.TRANSPARENT, 0, dp(6))
                } else {
                    holder.numBadge.setTextColor(Color.WHITE)
                    holder.numBadge.background = roundedBg(BG_CARD_ELEVATED, BORDER_SUBTLE, dp(1), dp(6))
                }

                holder.nameTv.text = item.channelName
                holder.nameTv.setTextColor(TEXT_PRIMARY)
                holder.nameTv.background = null
            }

            holder.hdBadge.visibility = if (item.isHD) View.VISIBLE else View.INVISIBLE
            LogoLoader.load(this@MainActivity, item.logoUrl, holder.logoImg)

            return card
        }

        private inner class ViewHolder(
            val numBadge: TextView,
            val hdBadge: TextView,
            val logoImg: ImageView,
            val nameTv: TextView
        )
    }

    // =========================================================================
    // MODERN TV UI STYLING HELPERS (YOUTUBE DEEP BLACK & CRISP WHITE THEME)
    // =========================================================================
    private fun updateServerStatusUi(online: Boolean, message: String) {
        serverOnline = online
        railStatusDot.setTextColor(if (online) ACCENT_GREEN else ACCENT_GOLD)
        railStatusLabel.text = if (online) "Online • $message" else message
        if (currentPage == PAGE_ACCOUNT) {
            refreshAccountServerPage()
        }
    }

    private fun getStartupSummaryLabel(): String {
        return when (prefs.myPreferences.startupLaunchMode) {
            SkySharedPref.STARTUP_LAST_CHANNEL -> "🚀 Startup: Resume Last"
            SkySharedPref.STARTUP_SPECIFIC_CHANNEL -> "🚀 Startup: Ch #${prefs.myPreferences.startupChannelNumber}"
            else -> "🚀 Startup: Guide"
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

    private fun createModernButton(
        label: String,
        isPrimary: Boolean = false,
        isGold: Boolean = false,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            val normalTextColor = when {
                isGold -> Color.WHITE
                isPrimary -> Color.BLACK
                else -> TEXT_PRIMARY
            }
            setTextColor(createFocusableTextColors(normalTextColor, Color.BLACK))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = when {
                isGold -> createGoldButtonDrawable()
                isPrimary -> createPrimaryButtonDrawable()
                else -> createSurfaceButtonDrawable()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
            ).apply {
                rightMargin = dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createCategoryPillButton(
        label: String,
        isSelected: Boolean,
        isGold: Boolean,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            val normalColor = when {
                isSelected -> Color.WHITE
                isGold -> Color.WHITE
                else -> TEXT_SECONDARY
            }
            setTextColor(createFocusableTextColors(normalColor, Color.BLACK))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = when {
                isSelected -> createActivePillDrawable(radiusDp = 18, isGold = isGold)
                else -> createSurfaceButtonDrawable(radiusDp = 18)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply {
                rightMargin = dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun styleTogglePill(btn: Button, active: Boolean, isGold: Boolean = false) {
        val normalColor = if (active) Color.WHITE else TEXT_SECONDARY
        btn.setTextColor(createFocusableTextColors(normalColor, Color.BLACK))
        btn.background = if (active) {
            createActivePillDrawable(radiusDp = 8, isGold = isGold)
        } else {
            createSurfaceButtonDrawable(radiusDp = 8)
        }
    }

    private fun createActivePillDrawable(radiusDp: Int = 18, isGold: Boolean = false): StateListDrawable {
        // Focused: Solid Pure White (#FFFFFF)
        val focused = roundedBg(Color.WHITE, Color.WHITE, dp(2), dp(radiusDp))
        // Unfocused but Active: Elevated Dark (#272727) with YouTube Red (#FF0033) or White border
        val normal = if (isGold) {
            roundedBg(ACCENT_GOLD, Color.WHITE, dp(1), dp(radiusDp))
        } else {
            roundedBg(BG_CARD_ELEVATED, Color.WHITE, dp(2), dp(radiusDp))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun createRailItemDrawable(isSelected: Boolean): StateListDrawable {
        // Focused with Remote: Solid Pure White (#FFFFFF) pill (text turns Black via ColorStateList)
        val focused = roundedBg(Color.WHITE, Color.WHITE, dp(2), dp(10))
        // Active Page (when remote is inside page content): Dark elevated pill with YouTube Red accent border
        val normal = if (isSelected) {
            roundedBg(BG_CARD_ELEVATED, ACCENT_GOLD, dp(2), dp(10))
        } else {
            roundedBg(Color.TRANSPARENT, Color.TRANSPARENT, 0, dp(10))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun createSurfaceButtonDrawable(radiusDp: Int = 8): StateListDrawable {
        val focused = roundedBg(Color.WHITE, Color.WHITE, dp(2), dp(radiusDp))
        val normal = roundedBg(BG_CARD_ELEVATED, BORDER_SUBTLE, dp(1), dp(radiusDp))
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun createPrimaryButtonDrawable(radiusDp: Int = 8): StateListDrawable {
        val focused = roundedBg(Color.WHITE, ACCENT_GOLD, dp(2), dp(radiusDp))
        val normal = roundedBg(Color.parseColor("#E5E5E5"), Color.TRANSPARENT, 0, dp(radiusDp))
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun createGoldButtonDrawable(radiusDp: Int = 8): StateListDrawable {
        val focused = roundedBg(Color.WHITE, ACCENT_GOLD, dp(2), dp(radiusDp))
        val normal = roundedBg(ACCENT_GOLD, Color.TRANSPARENT, 0, dp(radiusDp))
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun createKeypadButtonDrawable(): StateListDrawable {
        val focused = roundedBg(Color.WHITE, Color.WHITE, dp(2), dp(10))
        val normal = roundedBg(BG_CARD_ELEVATED, BORDER_SUBTLE, dp(1), dp(10))
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun createGridSelectorDrawable(): StateListDrawable {
        // Transparent fill with crisp 3dp pure white border drawn on top of the selected tile
        val focused = roundedBg(Color.TRANSPARENT, Color.WHITE, dp(3), dp(12))
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_selected), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), roundedBg(Color.TRANSPARENT, Color.TRANSPARENT, 0, dp(12)))
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
        populateCategoryPills()
        refreshAllPagesData()

        val port = prefs.myPreferences.jtvGoServerPort
        Thread({
            val online = BinaryService.isPortListening(port, 100)
            mainHandler.post {
                if (online) {
                    updateServerStatusUi(true, "Port $port")
                    if (allRawChannels.isEmpty()) {
                        refreshChannelsFromServer(triggerStartupLaunchIfReady = false)
                    }
                }
            }
        }, "Resume-PortCheck").start()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        uiScope.cancel()
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(serverEventReceiver)
            } catch (_: Exception) {
            }
            isReceiverRegistered = false
        }
        super.onDestroy()
    }
}
