package com.skylake.skytv.jgorunner.core.server

import android.content.Context
import android.util.Base64
import android.util.Log
import com.skylake.skytv.jgorunner.utils.LogCollector
import com.skylake.skytv.jgorunner.utils.Tls12SocketFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure Kotlin/Java in-process implementation of the `jiotv_go` HTTP streaming server.
 *
 * Runs directly inside the Android process on `127.0.0.1:<port>` (or `0.0.0.0:<port>` when public)
 * using Conscrypt TLS 1.2/1.3 via [Tls12SocketFactory].
 *
 * Why this exists:
 * On Android 4.4 KitKat devices, containers, or kernels where external Go ELF binaries fail to launch
 * (e.g., `SIGSYS` exit code 31 from `seccomp-bpf` blocking raw Linux ARM syscalls, or old Bionic
 * linker incompatibilities), [EmbeddedJioTvServer] provides 100% of the `jiotv_go` endpoints
 * (`/channels`, `/live/:id`, `/live/:quality/:id`, `/render.m3u8`, `/render.ts`, `/render.key`,
 * `/login/sendOTP`, `/login/verifyOTP`, `/logout`, `/jtvimage/:file`, `/playlist.m3u`) with
 * zero external binary dependencies and < 20ms startup latency.
 */
object EmbeddedJioTvServer {
    private const val TAG = "JTVGo::EmbeddedServer"

    private const val CHANNELS_API_URL =
        "https://jiotvapi.cdn.jio.com/apis/v3.1/getMobileChannelList/get/?langId=6&os=android&devicetype=phone&usertype=JIO&version=315&langId=6"
    private const val GET_STREAM_URL =
        "https://jiotvapi.media.jio.com/playback/apis/v1.1/geturl?langId=6"
    private const val OTP_SEND_URL =
        "https://jiotvapi.media.jio.com/userservice/apis/v1/loginotp/send"
    private const val OTP_VERIFY_URL =
        "https://jiotvapi.media.jio.com/userservice/apis/v1/loginotp/verify"
    private const val REFRESH_TOKEN_URL =
        "https://auth.media.jio.com/tokenservice/apis/v1/refreshtoken?langId=6"
    private const val REFRESH_SSO_URL =
        "https://tv.media.jio.com/apis/v2.0/loginotp/refresh?langId=6"
    private const val CATCHUP_LOGO_BASE_URL =
        "https://jiotv.catchup.cdn.jio.com/dare_images/images/"

    private const val USER_AGENT_OKHTTP = "okhttp/4.2.2"
    private const val USER_AGENT_PLAYTV =
        "plaYtv/7.1.8 (Linux;Android 8.1.0) ExoPlayerLib/2.11.7"
    private const val APP_KEY = "NzNiMDhlYzQyNjJm"
    private const val DEVICE_ID = "3c6d6b5702fa09bd"
    private const val VERSION_CODE = "315"

    private val running = AtomicBoolean(false)
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var activePort: Int = -1
    @Volatile
    private var appContext: Context? = null

    private val workerPool = Executors.newCachedThreadPool { r ->
        Thread(r, "JioTVGo-EmbeddedHTTP").apply { isDaemon = true }
    }

    // Per-channel cached __hdnea__ token (updated on /live and Set-Cookie rotation)
    private val channelHdneaMap = ConcurrentHashMap<String, String>()

    // Cached raw JSON from JioTV getMobileChannelList API
    @Volatile
    private var cachedChannelsJson: String? = null
    @Volatile
    private var cachedChannelsTimestampMs: Long = 0L

    data class JioTvCredentials(
        val ssoToken: String,
        val crm: String,
        val uniqueId: String,
        val accessToken: String,
        val refreshToken: String
    ) {
        fun isValid(): Boolean =
            (accessToken.isNotBlank() || ssoToken.isNotBlank()) && crm.isNotBlank()
    }

    @Volatile
    private var credentials: JioTvCredentials? = null
    private val tokenRefreshLock = Any()
    @Volatile
    private var lastAccessTokenRefreshMs: Long = 0L
    @Volatile
    private var lastSsoTokenRefreshMs: Long = 0L

    fun isRunning(): Boolean = running.get() && serverSocket?.isClosed == false

    @Synchronized
    fun start(
        context: Context,
        port: Int,
        isPublic: Boolean = false,
        onLog: ((String) -> Unit)? = null
    ): Boolean {
        val appCtx = context.applicationContext
        appContext = appCtx
        loadCredentialsFromDisk(appCtx)

        if (isRunning() && activePort == port) {
            LogCollector.log("[EmbeddedServer] Already running on port $port")
            return true
        }
        stop()

        return try {
            val bindAddr = if (isPublic) InetAddress.getByName("0.0.0.0") else InetAddress.getByName("127.0.0.1")
            val sock = ServerSocket(port, 64, bindAddr).apply {
                reuseAddress = true
            }
            serverSocket = sock
            activePort = port
            running.set(true)

            val msg = "Embedded JioTV Go Engine listening on ${bindAddr.hostAddress}:$port (LoggedIn=${credentials?.isValid() == true})"
            Log.i(TAG, msg)
            LogCollector.log("[EmbeddedServer] $msg")
            onLog?.invoke("[EmbeddedServer] $msg")

            Thread({
                while (running.get() && !sock.isClosed) {
                    try {
                        val client = sock.accept()
                        workerPool.execute {
                            handleClientSocket(client, onLog)
                        }
                    } catch (e: Exception) {
                        if (running.get()) {
                            LogCollector.log("[EmbeddedServer] Accept error: ${e.message}")
                        }
                    }
                }
            }, "JioTVGo-EmbeddedAccept-$port").apply { isDaemon = true }.start()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start EmbeddedJioTvServer on port $port", e)
            LogCollector.log("[EmbeddedServer] Failed to bind port $port: ${e.javaClass.simpleName}: ${e.message}")
            running.set(false)
            false
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        activePort = -1
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    // =========================================================================
    // CREDENTIALS PERSISTENCE (Compatible with both JSON and store_v4.toml)
    // =========================================================================
    private fun getCredentialsJsonFile(context: Context): File =
        File(context.filesDir, "jiotv_credentials.json")

    private fun getStoreTomlFile(context: Context): File =
        File(context.filesDir, "store_v4.toml")

    @Synchronized
    fun loadCredentialsFromDisk(context: Context): JioTvCredentials? {
        try {
            val jsonFile = getCredentialsJsonFile(context)
            if (jsonFile.exists() && jsonFile.length() > 10) {
                val obj = JSONObject(jsonFile.readText(Charsets.UTF_8))
                val creds = JioTvCredentials(
                    ssoToken = obj.optString("ssoToken", ""),
                    crm = obj.optString("crm", ""),
                    uniqueId = obj.optString("uniqueId", ""),
                    accessToken = obj.optString("accessToken", ""),
                    refreshToken = obj.optString("refreshToken", "")
                )
                if (creds.isValid()) {
                    credentials = creds
                    return creds
                }
            }
        } catch (e: Exception) {
            LogCollector.log("[EmbeddedServer] Failed reading jiotv_credentials.json: ${e.message}")
        }

        // Also check store_v4.toml (written by jiotv_go binary if previously logged in)
        val candidates = listOf(
            getStoreTomlFile(context),
            File(File(context.filesDir, "jiotv_go"), "store_v4.toml")
        )
        for (tomlFile in candidates) {
            if (tomlFile.exists() && tomlFile.length() > 10) {
                try {
                    var sso = ""
                    var crm = ""
                    var unique = ""
                    var access = ""
                    var refresh = ""
                    tomlFile.readLines(Charsets.UTF_8).forEach { rawLine ->
                        val line = rawLine.trim()
                        if (line.contains("=")) {
                            val key = line.substringBefore("=").trim()
                            val value = line.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
                            when (key) {
                                "ssoToken" -> sso = value
                                "crm" -> crm = value
                                "uniqueId" -> unique = value
                                "accessToken" -> access = value
                                "refreshToken" -> refresh = value
                            }
                        }
                    }
                    val creds = JioTvCredentials(sso, crm, unique, access, refresh)
                    if (creds.isValid()) {
                        credentials = creds
                        saveCredentialsToDisk(context, creds)
                        return creds
                    }
                } catch (e: Exception) {
                    LogCollector.log("[EmbeddedServer] Failed reading ${tomlFile.name}: ${e.message}")
                }
            }
        }
        return credentials
    }

    @Synchronized
    private fun saveCredentialsToDisk(context: Context, creds: JioTvCredentials) {
        credentials = creds
        try {
            val json = JSONObject().apply {
                put("ssoToken", creds.ssoToken)
                put("crm", creds.crm)
                put("uniqueId", creds.uniqueId)
                put("accessToken", creds.accessToken)
                put("refreshToken", creds.refreshToken)
            }
            getCredentialsJsonFile(context).writeText(json.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            LogCollector.log("[EmbeddedServer] Error writing jiotv_credentials.json: ${e.message}")
        }

        try {
            val tomlContent = buildString {
                appendLine("ssoToken = \"${creds.ssoToken}\"")
                appendLine("crm = \"${creds.crm}\"")
                appendLine("uniqueId = \"${creds.uniqueId}\"")
                appendLine("accessToken = \"${creds.accessToken}\"")
                appendLine("refreshToken = \"${creds.refreshToken}\"")
            }
            getStoreTomlFile(context).writeText(tomlContent, Charsets.UTF_8)
            val subDir = File(context.filesDir, "jiotv_go")
            if (!subDir.exists()) subDir.mkdirs()
            File(subDir, "store_v4.toml").writeText(tomlContent, Charsets.UTF_8)
        } catch (e: Exception) {
            LogCollector.log("[EmbeddedServer] Error writing store_v4.toml: ${e.message}")
        }
    }

    @Synchronized
    private fun clearCredentials(context: Context) {
        credentials = null
        try {
            getCredentialsJsonFile(context).delete()
            getStoreTomlFile(context).delete()
            File(File(context.filesDir, "jiotv_go"), "store_v4.toml").delete()
        } catch (_: Exception) {
        }
    }

    // =========================================================================
    // HTTP REQUEST DISPATCHER
    // =========================================================================
    private fun handleClientSocket(socket: Socket, onLog: ((String) -> Unit)?) {
        socket.use { client ->
            try {
                client.soTimeout = 15_000
                client.tcpNoDelay = true
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())

                val requestLine = readAsciiLine(input) ?: return
                val parts = requestLine.trim().split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.US)
                val fullTarget = parts[1]

                val headers = HashMap<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val k = line.substring(0, colon).trim().lowercase(Locale.US)
                        val v = line.substring(colon + 1).trim()
                        headers[k] = v
                    }
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyBytes = if (contentLength > 0 && contentLength <= 2 * 1024 * 1024) {
                    val buf = ByteArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val n = input.read(buf, readTotal, contentLength - readTotal)
                        if (n <= 0) break
                        readTotal += n
                    }
                    if (readTotal == contentLength) buf else buf.copyOf(readTotal)
                } else {
                    ByteArray(0)
                }

                val qIndex = fullTarget.indexOf('?')
                val rawPath = if (qIndex >= 0) fullTarget.substring(0, qIndex) else fullTarget
                val rawQuery = if (qIndex >= 0) fullTarget.substring(qIndex + 1) else ""
                val queryParams = parseQueryParams(rawQuery)
                val hostHeader = headers["host"] ?: "127.0.0.1:$activePort"

                when {
                    rawPath == "/" || rawPath == "/status" -> {
                        val isLogged = credentials?.isValid() == true
                        val resp = JSONObject().apply {
                            put("status", "running")
                            put("engine", "EmbeddedJioTvServer")
                            put("logged_in", isLogged)
                        }.toString()
                        sendResponse(output, 200, "OK", "application/json; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
                    }

                    rawPath == "/login/sendOTP" && method == "POST" -> {
                        handleSendOtp(bodyBytes, headers["content-type"], output, onLog)
                    }

                    rawPath == "/login/verifyOTP" && method == "POST" -> {
                        handleVerifyOtp(bodyBytes, headers["content-type"], output, onLog)
                    }

                    rawPath == "/logout" || rawPath == "/login/logout" -> {
                        appContext?.let { clearCredentials(it) }
                        val resp = """{"status":"success","message":"Logged out"}"""
                        sendResponse(output, 200, "OK", "application/json; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
                    }

                    rawPath == "/channels" -> {
                        handleChannelsRequest(hostHeader, queryParams, output)
                    }

                    rawPath == "/playlist.m3u" -> {
                        val m3uParams = HashMap(queryParams).apply { put("type", "m3u") }
                        handleChannelsRequest(hostHeader, m3uParams, output)
                    }

                    rawPath.startsWith("/live/") -> {
                        handleLiveRequest(rawPath, output, onLog)
                    }

                    rawPath == "/render.m3u8" -> {
                        handleRenderM3u8(queryParams, output)
                    }

                    rawPath == "/render.ts" -> {
                        handleRenderTs(queryParams, headers, output)
                    }

                    rawPath == "/render.key" -> {
                        handleRenderKey(queryParams, output)
                    }

                    rawPath.startsWith("/jtvimage/") -> {
                        val fileName = rawPath.removePrefix("/jtvimage/").trimStart('/')
                        handleJtvImage(fileName, output)
                    }

                    else -> {
                        val msg = """{"error":"Not Found","path":"$rawPath"}"""
                        sendResponse(output, 404, "Not Found", "application/json; charset=utf-8", msg.toByteArray(Charsets.UTF_8))
                    }
                }
            } catch (e: Exception) {
                // Socket closed by ExoPlayer or client disconnect during segment streaming is normal
            }
        }
    }

    // =========================================================================
    // 1. OTP LOGIN ENDPOINTS (/login/sendOTP & /login/verifyOTP)
    // =========================================================================
    private fun handleSendOtp(
        bodyBytes: ByteArray,
        contentType: String?,
        output: OutputStream,
        onLog: ((String) -> Unit)?
    ) {
        try {
            val bodyStr = String(bodyBytes, Charsets.UTF_8).trim()
            val rawNumber = if (contentType?.contains("json", ignoreCase = true) == true || bodyStr.startsWith("{")) {
                JSONObject(bodyStr).optString("number", "")
            } else {
                parseQueryParams(bodyStr)["number"] ?: ""
            }.trim()

            if (rawNumber.isBlank()) {
                val err = """{"status":false,"message":"Phone number is required"}"""
                sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8", err.toByteArray(Charsets.UTF_8))
                return
            }

            val normalizedNumber = when {
                rawNumber.startsWith("+91") -> rawNumber
                rawNumber.length == 10 && rawNumber.all { it.isDigit() } -> "+91$rawNumber"
                else -> "+91${rawNumber.filter { it.isDigit() }.takeLast(10)}"
            }
            val b64Number = Base64.encodeToString(normalizedNumber.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val payload = JSONObject().put("number", b64Number).toString()

            LogCollector.log("[EmbeddedServer] Sending OTP request for $normalizedNumber")
            onLog?.invoke("[OTP] Sending OTP to $normalizedNumber...")

            val conn = Tls12SocketFactory.openConnection(URL(OTP_SEND_URL), connectTimeoutMs = 12_000, readTimeoutMs = 12_000)
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("appname", "RJIL_JioTV")
                conn.setRequestProperty("os", "android")
                conn.setRequestProperty("devicetype", "phone")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", USER_AGENT_OKHTTP)

                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val respBody = readStreamSafe(if (code in 200..299) conn.inputStream else conn.errorStream)
                LogCollector.log("[EmbeddedServer] sendOTP HTTP $code: ${respBody.toString(Charsets.UTF_8).take(200)}")

                if (code == 204 || code == 200) {
                    val okJson = JSONObject().apply {
                        put("status", true)
                        put("message", "OTP sent successfully")
                    }.toString()
                    sendResponse(output, 200, "OK", "application/json; charset=utf-8", okJson.toByteArray(Charsets.UTF_8))
                } else {
                    val failJson = JSONObject().apply {
                        put("status", false)
                        put("message", "JioTV OTP API returned HTTP $code: ${respBody.toString(Charsets.UTF_8)}")
                    }.toString()
                    sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8", failJson.toByteArray(Charsets.UTF_8))
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            LogCollector.log("[EmbeddedServer] sendOTP error: ${e.javaClass.simpleName}: ${e.message}")
            val errJson = JSONObject().apply {
                put("status", false)
                put("message", "Error sending OTP: ${e.message}")
            }.toString()
            sendResponse(output, 500, "Internal Server Error", "application/json; charset=utf-8", errJson.toByteArray(Charsets.UTF_8))
        }
    }

    private fun handleVerifyOtp(
        bodyBytes: ByteArray,
        contentType: String?,
        output: OutputStream,
        onLog: ((String) -> Unit)?
    ) {
        try {
            val bodyStr = String(bodyBytes, Charsets.UTF_8).trim()
            val rawNumber: String
            val otp: String
            if (contentType?.contains("json", ignoreCase = true) == true || bodyStr.startsWith("{")) {
                val obj = JSONObject(bodyStr)
                rawNumber = obj.optString("number", "").trim()
                otp = obj.optString("otp", "").trim()
            } else {
                val form = parseQueryParams(bodyStr)
                rawNumber = (form["number"] ?: "").trim()
                otp = (form["otp"] ?: "").trim()
            }

            if (rawNumber.isBlank() || otp.isBlank()) {
                val err = """{"status":"failed","message":"Both phone number and OTP are required"}"""
                sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8", err.toByteArray(Charsets.UTF_8))
                return
            }

            val normalizedNumber = when {
                rawNumber.startsWith("+91") -> rawNumber
                rawNumber.length == 10 && rawNumber.all { it.isDigit() } -> "+91$rawNumber"
                else -> "+91${rawNumber.filter { it.isDigit() }.takeLast(10)}"
            }
            val b64Number = Base64.encodeToString(normalizedNumber.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            val payloadObj = JSONObject().apply {
                put("number", b64Number)
                put("otp", otp)
                put("deviceInfo", JSONObject().apply {
                    put("consumptionDeviceName", "RMX1945")
                    put("info", JSONObject().apply {
                        put("type", "android")
                        put("platform", JSONObject().put("name", "RMX1945"))
                        put("androidId", DEVICE_ID)
                    })
                })
            }

            LogCollector.log("[EmbeddedServer] Verifying OTP for $normalizedNumber")
            val conn = Tls12SocketFactory.openConnection(URL(OTP_VERIFY_URL), connectTimeoutMs = 12_000, readTimeoutMs = 12_000)
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("appname", "RJIL_JioTV")
                conn.setRequestProperty("os", "android")
                conn.setRequestProperty("devicetype", "phone")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", USER_AGENT_OKHTTP)

                conn.outputStream.use { it.write(payloadObj.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val respStr = readStreamSafe(if (code in 200..299) conn.inputStream else conn.errorStream).toString(Charsets.UTF_8)
                LogCollector.log("[EmbeddedServer] verifyOTP HTTP $code")

                if (code == 200) {
                    val respJson = JSONObject(respStr)
                    val ssoToken = respJson.optString("ssoToken", "")
                    val accessToken = respJson.optString("authToken", "")
                    val refreshToken = respJson.optString("refreshToken", "")
                    val userObj = respJson.optJSONObject("sessionAttributes")?.optJSONObject("user")
                    val subscriberId = userObj?.optString("subscriberId", "") ?: ""
                    val uniqueId = userObj?.optString("unique", "") ?: ""

                    val newCreds = JioTvCredentials(
                        ssoToken = ssoToken,
                        crm = subscriberId,
                        uniqueId = uniqueId,
                        accessToken = accessToken,
                        refreshToken = refreshToken
                    )
                    if (newCreds.isValid()) {
                        appContext?.let { saveCredentialsToDisk(it, newCreds) }
                        lastAccessTokenRefreshMs = System.currentTimeMillis()
                        lastSsoTokenRefreshMs = System.currentTimeMillis()
                        LogCollector.log("[EmbeddedServer] OTP Login succeeded! Saved credentials (crm=$subscriberId)")
                        onLog?.invoke("[OTP] Login successful! Credentials saved.")
                        val okResp = JSONObject().apply {
                            put("status", "success")
                            put("message", "Logged in successfully")
                        }.toString()
                        sendResponse(output, 200, "OK", "application/json; charset=utf-8", okResp.toByteArray(Charsets.UTF_8))
                        return
                    }
                }

                val failResp = JSONObject().apply {
                    put("status", "failed")
                    put("message", "OTP verification failed (HTTP $code): $respStr")
                }.toString()
                sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8", failResp.toByteArray(Charsets.UTF_8))
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            LogCollector.log("[EmbeddedServer] verifyOTP error: ${e.javaClass.simpleName}: ${e.message}")
            val errResp = JSONObject().apply {
                put("status", "failed")
                put("message", "OTP verification error: ${e.message}")
            }.toString()
            sendResponse(output, 500, "Internal Server Error", "application/json; charset=utf-8", errResp.toByteArray(Charsets.UTF_8))
        }
    }

    // =========================================================================
    // 2. TOKEN REFRESH (EnsureFreshTokens, LoginRefreshAccessToken, LoginRefreshSSOToken)
    // =========================================================================
    private fun ensureFreshTokens() {
        val ctx = appContext ?: return
        val creds = credentials ?: loadCredentialsFromDisk(ctx) ?: return
        if (!creds.isValid()) return

        synchronized(tokenRefreshLock) {
            val current = credentials ?: return
            if (current.accessToken.isNotBlank() && isJwtExpiringSoon(current.accessToken, 10 * 60)) {
                LogCollector.log("[EmbeddedServer] AccessToken expiring soon; refreshing...")
                refreshAccessTokenLocked()
            }
            val updated = credentials ?: return
            if (updated.ssoToken.isNotBlank() && isJwtExpiringSoon(updated.ssoToken, 15 * 60)) {
                LogCollector.log("[EmbeddedServer] SsoToken expiring soon; refreshing...")
                refreshSsoTokenLocked()
            }
        }
    }

    private fun isJwtExpiringSoon(jwt: String, windowSeconds: Long): Boolean {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return false
            val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val payloadJson = JSONObject(String(payloadBytes, Charsets.UTF_8))
            val expSec = payloadJson.optLong("exp", 0L)
            if (expSec <= 0L) return false
            val nowSec = System.currentTimeMillis() / 1000L
            nowSec + windowSeconds >= expSec
        } catch (_: Exception) {
            false
        }
    }

    private fun refreshAccessTokenLocked(): Boolean {
        val current = credentials ?: return false
        if (current.refreshToken.isBlank()) return false

        return try {
            val payload = JSONObject().apply {
                put("appName", "RJIL_JioTV")
                put("deviceType", "phone")
                put("os", "android")
                put("refreshToken", current.refreshToken)
            }.toString()

            val conn = Tls12SocketFactory.openConnection(URL(REFRESH_TOKEN_URL), connectTimeoutMs = 10_000, readTimeoutMs = 10_000)
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("accesstoken", current.accessToken)
                conn.setRequestProperty("uniqueId", current.uniqueId)
                conn.setRequestProperty("devicetype", "phone")
                conn.setRequestProperty("os", "android")
                conn.setRequestProperty("User-Agent", USER_AGENT_OKHTTP)
                conn.setRequestProperty("versionCode", VERSION_CODE)
                conn.setRequestProperty("Content-Type", "application/json")

                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val respStr = readStreamSafe(if (code in 200..299) conn.inputStream else conn.errorStream).toString(Charsets.UTF_8)
                if (code == 200) {
                    val obj = JSONObject(respStr)
                    val newAuth = obj.optString("authToken", "")
                    if (newAuth.isNotBlank()) {
                        val updated = current.copy(accessToken = newAuth)
                        appContext?.let { saveCredentialsToDisk(it, updated) }
                        lastAccessTokenRefreshMs = System.currentTimeMillis()
                        LogCollector.log("[EmbeddedServer] Refreshed AccessToken successfully")
                        return true
                    }
                }
                LogCollector.log("[EmbeddedServer] Refresh AccessToken HTTP $code: ${respStr.take(160)}")
                false
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            LogCollector.log("[EmbeddedServer] Refresh AccessToken error: ${e.message}")
            false
        }
    }

    private fun refreshSsoTokenLocked(): Boolean {
        val current = credentials ?: return false
        if (current.ssoToken.isBlank()) return false

        return try {
            val conn = Tls12SocketFactory.openConnection(URL(REFRESH_SSO_URL), connectTimeoutMs = 10_000, readTimeoutMs = 10_000)
            try {
                conn.requestMethod = "GET"
                conn.setRequestProperty("devicetype", "phone")
                conn.setRequestProperty("os", "android")
                conn.setRequestProperty("appkey", APP_KEY)
                conn.setRequestProperty("uniqueid", current.uniqueId)
                conn.setRequestProperty("ssotoken", current.ssoToken)
                conn.setRequestProperty("User-Agent", USER_AGENT_OKHTTP)
                conn.setRequestProperty("versionCode", VERSION_CODE)

                val code = conn.responseCode
                val respStr = readStreamSafe(if (code in 200..299) conn.inputStream else conn.errorStream).toString(Charsets.UTF_8)
                if (code == 200) {
                    val obj = JSONObject(respStr)
                    val newSso = obj.optString("ssoToken", "")
                    if (newSso.isNotBlank()) {
                        val updated = current.copy(ssoToken = newSso)
                        appContext?.let { saveCredentialsToDisk(it, updated) }
                        lastSsoTokenRefreshMs = System.currentTimeMillis()
                        LogCollector.log("[EmbeddedServer] Refreshed SsoToken successfully")
                        return true
                    }
                }
                false
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            LogCollector.log("[EmbeddedServer] Refresh SsoToken error: ${e.message}")
            false
        }
    }

    // =========================================================================
    // 3. CHANNELS CATALOG & M3U PLAYLIST (/channels & /playlist.m3u)
    // =========================================================================
    private fun handleChannelsRequest(
        hostHeader: String,
        queryParams: Map<String, String>,
        output: OutputStream
    ) {
        try {
            val rawJson = fetchOrGetCachedChannelsJson()
            if (rawJson.isNullOrBlank()) {
                val err = """{"code":500,"message":"Failed to fetch JioTV channels"}"""
                sendResponse(output, 500, "Internal Server Error", "application/json; charset=utf-8", err.toByteArray(Charsets.UTF_8))
                return
            }

            val rootObj = JSONObject(rawJson)
            val resultArr = rootObj.optJSONArray("result") ?: JSONArray()
            val filterLang = queryParams["lang"]?.toIntOrNull()
            val filterCategory = queryParams["type"]?.toIntOrNull()
            val isM3u = queryParams["type"]?.equals("m3u", ignoreCase = true) == true

            if (isM3u) {
                val sb = StringBuilder(resultArr.length() * 140)
                sb.append("#EXTM3U\n")
                for (i in 0 until resultArr.length()) {
                    val ch = resultArr.optJSONObject(i) ?: continue
                    val id = ch.optString("channel_id", "")
                    val name = ch.optString("channel_name", "Channel $id")
                    val logo = ch.optString("logoUrl", "")
                    val logoFull = if (logo.startsWith("http")) logo else "http://$hostHeader/jtvimage/$logo"
                    val liveUrl = "http://$hostHeader/live/$id.m3u8"
                    sb.append("#EXTINF:-1 tvg-id=\"$id\" tvg-name=\"$name\" tvg-logo=\"$logoFull\",$name\n")
                    sb.append(liveUrl).append("\n")
                }
                sendResponse(
                    output,
                    200,
                    "OK",
                    "application/vnd.apple.mpegurl; charset=utf-8",
                    sb.toString().toByteArray(Charsets.UTF_8)
                )
                return
            }

            val outArr = JSONArray()
            for (i in 0 until resultArr.length()) {
                val ch = resultArr.optJSONObject(i) ?: continue
                if (filterLang != null && ch.optInt("channelLanguageId", -1) != filterLang) continue
                if (filterCategory != null && ch.optInt("channelCategoryId", -1) != filterCategory) continue
                val id = ch.optString("channel_id", "")
                val copy = JSONObject(ch.toString())
                copy.put("channel_url", "http://$hostHeader/live/$id")
                outArr.put(copy)
            }

            val outRoot = JSONObject().apply {
                put("code", rootObj.optInt("code", 200))
                put("message", rootObj.optString("message", "success"))
                put("result", outArr)
            }

            sendResponse(
                output,
                200,
                "OK",
                "application/json; charset=utf-8",
                outRoot.toString().toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            LogCollector.log("[EmbeddedServer] /channels error: ${e.message}")
            val err = """{"code":500,"message":"${e.message}"}"""
            sendResponse(output, 500, "Internal Server Error", "application/json; charset=utf-8", err.toByteArray(Charsets.UTF_8))
        }
    }

    private fun fetchOrGetCachedChannelsJson(): String? {
        val now = System.currentTimeMillis()
        val mem = cachedChannelsJson
        if (!mem.isNullOrBlank() && (now - cachedChannelsTimestampMs) < 30 * 60 * 1000L) {
            return mem
        }

        val ctx = appContext
        val cacheFile = if (ctx != null) File(ctx.filesDir, "embedded_channels_cache.json") else null

        try {
            val conn = Tls12SocketFactory.openConnection(URL(CHANNELS_API_URL), connectTimeoutMs = 10_000, readTimeoutMs = 15_000)
            try {
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", USER_AGENT_OKHTTP)
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("devicetype", "phone")
                conn.setRequestProperty("os", "android")
                conn.setRequestProperty("appkey", APP_KEY)
                conn.setRequestProperty("lbcookie", "1")
                conn.setRequestProperty("usertype", "JIO")

                val code = conn.responseCode
                if (code == 200) {
                    val jsonStr = readStreamSafe(conn.inputStream).toString(Charsets.UTF_8)
                    if (jsonStr.contains("\"result\"")) {
                        cachedChannelsJson = jsonStr
                        cachedChannelsTimestampMs = now
                        try {
                            cacheFile?.writeText(jsonStr, Charsets.UTF_8)
                        } catch (_: Exception) {
                        }
                        LogCollector.log("[EmbeddedServer] Fetched channels catalog from JioTV CDN (${jsonStr.length} bytes)")
                        return jsonStr
                    }
                } else {
                    LogCollector.log("[EmbeddedServer] JioTV channels API returned HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            LogCollector.log("[EmbeddedServer] Channels CDN fetch failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        if (cacheFile != null && cacheFile.exists() && cacheFile.length() > 100) {
            return try {
                val diskJson = cacheFile.readText(Charsets.UTF_8)
                cachedChannelsJson = diskJson
                cachedChannelsTimestampMs = now
                diskJson
            } catch (_: Exception) {
                null
            }
        }
        return mem
    }

    // =========================================================================
    // 4. LIVE CHANNEL STREAM RESOLUTION (/live/:id & /live/:quality/:id)
    // =========================================================================
    private fun handleLiveRequest(
        rawPath: String,
        output: OutputStream,
        onLog: ((String) -> Unit)?
    ) {
        try {
            val trimmed = rawPath.removePrefix("/live/").trim('/')
            val segments = trimmed.split("/")
            val quality: String
            val rawChannelId: String
            if (segments.size >= 2) {
                quality = segments[0]
                rawChannelId = segments[1]
            } else {
                quality = "auto"
                rawChannelId = segments.firstOrNull() ?: ""
            }
            val channelId = rawChannelId.removeSuffix(".m3u8")

            if (channelId.isBlank()) {
                val msg = """{"error":"Missing channel ID"}"""
                sendResponse(output, 400, "Bad Request", "application/json; charset=utf-8", msg.toByteArray(Charsets.UTF_8))
                return
            }

            ensureFreshTokens()
            val creds = credentials ?: appContext?.let { loadCredentialsFromDisk(it) }
            if (creds == null || !creds.isValid()) {
                val msg = "Not logged in to JioTV. Please open Account & Server tab and login with OTP."
                LogCollector.log("[EmbeddedServer] /live/$channelId rejected: Not logged in")
                onLog?.invoke("[Auth] Please login with your Jio number + OTP in Account & Server tab.")
                sendResponse(output, 403, "Forbidden", "text/plain; charset=utf-8", msg.toByteArray(Charsets.UTF_8))
                return
            }

            val liveObj = fetchLiveStreamObjectWithRetry(channelId)
            if (liveObj == null) {
                val msg = "Failed to obtain live stream URL for channel $channelId"
                sendResponse(output, 502, "Bad Gateway", "text/plain; charset=utf-8", msg.toByteArray(Charsets.UTF_8))
                return
            }

            val selectedUrl = selectBestLiveHlsUrl(liveObj, quality)
            if (selectedUrl.isBlank()) {
                val msg = "Empty stream URL returned by JioTV for channel $channelId"
                sendResponse(output, 502, "Bad Gateway", "text/plain; charset=utf-8", msg.toByteArray(Charsets.UTF_8))
                return
            }

            // Cache __hdnea__ token from query string if present
            extractQueryParamFromUrl(selectedUrl, "hdnea")?.takeIf { it.isNotBlank() }?.let { hdnea ->
                channelHdneaMap[channelId] = hdnea
            }

            val encodedAuth = encodeUrlBase64(selectedUrl)
            val redirectTarget = "/render.m3u8?auth=${URLEncoder.encode(encodedAuth, "UTF-8")}&channel_key_id=${URLEncoder.encode(channelId, "UTF-8")}&q=${URLEncoder.encode(quality, "UTF-8")}"

            sendRedirect(output, redirectTarget)
        } catch (e: Exception) {
            LogCollector.log("[EmbeddedServer] /live error: ${e.javaClass.simpleName}: ${e.message}")
            sendResponse(output, 500, "Internal Server Error", "text/plain; charset=utf-8", "Error: ${e.message}".toByteArray(Charsets.UTF_8))
        }
    }

    private fun fetchLiveStreamObjectWithRetry(channelId: String): JSONObject? {
        val firstAttempt = requestGetUrlApi(channelId)
        if (firstAttempt.first == 200 && firstAttempt.second != null) {
            return firstAttempt.second
        }

        // On 401/403/419, refresh tokens and retry once
        LogCollector.log("[EmbeddedServer] geturl returned HTTP ${firstAttempt.first} for channel $channelId; attempting token refresh...")
        val refreshed = synchronized(tokenRefreshLock) {
            refreshAccessTokenLocked() || refreshSsoTokenLocked()
        }
        if (refreshed) {
            val secondAttempt = requestGetUrlApi(channelId)
            if (secondAttempt.first == 200 && secondAttempt.second != null) {
                return secondAttempt.second
            }
        }
        return null
    }

    private fun requestGetUrlApi(channelId: String): Pair<Int, JSONObject?> {
        val creds = credentials ?: return Pair(401, null)
        return try {
            val now = Date()
            val beginFmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val srnoFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            }
            val formBody = "channel_id=${URLEncoder.encode(channelId, "UTF-8")}&stream_type=Seek&begin=${beginFmt.format(now)}&srno=${srnoFmt.format(now)}"

            val conn = Tls12SocketFactory.openConnection(URL(GET_STREAM_URL), connectTimeoutMs = 10_000, readTimeoutMs = 10_000)
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                conn.setRequestProperty("accesstoken", creds.accessToken)
                conn.setRequestProperty("appkey", APP_KEY)
                conn.setRequestProperty("channel_id", channelId)
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("crmid", creds.crm)
                conn.setRequestProperty("deviceId", DEVICE_ID)
                conn.setRequestProperty("devicetype", "phone")
                conn.setRequestProperty("isott", "true")
                conn.setRequestProperty("languageId", "6")
                conn.setRequestProperty("lbcookie", "1")
                conn.setRequestProperty("os", "android")
                conn.setRequestProperty("osVersion", "8.1.0")
                conn.setRequestProperty("srno", "230203144000")
                conn.setRequestProperty("ssotoken", creds.ssoToken)
                conn.setRequestProperty("subscriberId", creds.crm)
                conn.setRequestProperty("uniqueId", creds.uniqueId)
                conn.setRequestProperty("User-Agent", USER_AGENT_OKHTTP)
                conn.setRequestProperty("usergroup", "tvYR7NSNn7rymo3F")
                conn.setRequestProperty("versionCode", VERSION_CODE)

                conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val respStr = readStreamSafe(if (code in 200..299) conn.inputStream else conn.errorStream).toString(Charsets.UTF_8)
                if (code == 200) {
                    val obj = JSONObject(respStr)
                    val apiCode = obj.optInt("code", 200)
                    if (apiCode == 200 || obj.optString("result", "").isNotBlank()) {
                        return Pair(200, obj)
                    }
                    return Pair(apiCode, null)
                }
                LogCollector.log("[EmbeddedServer] geturl HTTP $code for ch=$channelId: ${respStr.take(160)}")
                Pair(code, null)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            LogCollector.log("[EmbeddedServer] requestGetUrlApi error: ${e.message}")
            Pair(500, null)
        }
    }

    private fun selectBestLiveHlsUrl(liveObj: JSONObject, quality: String): String {
        val bitrates = liveObj.optJSONObject("bitrates")
        val resultUrl = liveObj.optString("result", "")
        val autoUrl = bitrates?.optString("auto", "") ?: ""
        val highUrl = bitrates?.optString("high", "") ?: ""
        val mediumUrl = bitrates?.optString("medium", "") ?: ""
        val lowUrl = bitrates?.optString("low", "") ?: ""

        val chosen = when (quality.lowercase(Locale.US)) {
            "high", "hd" -> highUrl.ifBlank { autoUrl }.ifBlank { resultUrl }
            "medium", "med", "sd" -> mediumUrl.ifBlank { autoUrl }.ifBlank { resultUrl }
            "low" -> lowUrl.ifBlank { mediumUrl }.ifBlank { autoUrl }.ifBlank { resultUrl }
            else -> autoUrl.ifBlank { resultUrl }.ifBlank { highUrl }.ifBlank { mediumUrl }.ifBlank { lowUrl }
        }

        if (resultUrl.isBlank() || chosen.isBlank()) return chosen.ifBlank { resultUrl }

        // Ensure query params (like hdnea) from resultUrl are preserved on bitrate URLs
        return try {
            val chosenHasQuery = chosen.contains("?")
            val resultQuery = resultUrl.substringAfter('?', "")
            if (!chosenHasQuery && resultQuery.isNotBlank()) {
                "$chosen?$resultQuery"
            } else {
                chosen
            }
        } catch (_: Exception) {
            chosen
        }
    }

    // =========================================================================
    // 5. HLS MANIFEST REWRITER (/render.m3u8)
    // =========================================================================
    private fun handleRenderM3u8(
        queryParams: Map<String, String>,
        output: OutputStream
    ) {
        val auth = queryParams["auth"] ?: ""
        val channelId = queryParams["channel_key_id"] ?: ""
        val quality = queryParams["q"] ?: "auto"

        if (auth.isBlank()) {
            sendResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", "Missing auth param".toByteArray(Charsets.UTF_8))
            return
        }

        var decodedUrl = decodeUrlBase64(auth)
        if (decodedUrl.isBlank()) {
            sendResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", "Invalid auth param".toByteArray(Charsets.UTF_8))
            return
        }

        // Attach latest cached hdnea token
        val cachedHdnea = channelHdneaMap[channelId]
        if (!cachedHdnea.isNullOrBlank()) {
            decodedUrl = replaceOrAddQueryParam(decodedUrl, "hdnea", cachedHdnea)
        }

        var fetchResult = fetchUpstreamM3u8(decodedUrl, channelId)
        if (fetchResult.first in listOf(401, 403, 404) && channelId.isNotBlank()) {
            // Refresh channel token and retry once
            val freshObj = fetchLiveStreamObjectWithRetry(channelId)
            if (freshObj != null) {
                val freshUrl = selectBestLiveHlsUrl(freshObj, quality)
                extractQueryParamFromUrl(freshUrl, "hdnea")?.takeIf { it.isNotBlank() }?.let { newHdnea ->
                    channelHdneaMap[channelId] = newHdnea
                    decodedUrl = replaceOrAddQueryParam(decodedUrl, "hdnea", newHdnea)
                }
                val retryTarget = if (decodedUrl.contains("master", ignoreCase = true) || decodedUrl.contains("index", ignoreCase = true)) {
                    freshUrl
                } else {
                    decodedUrl
                }
                fetchResult = fetchUpstreamM3u8(retryTarget, channelId)
                decodedUrl = retryTarget
            }
        }

        val statusCode = fetchResult.first
        val m3u8Bytes = fetchResult.second
        if (statusCode != 200 || m3u8Bytes == null || m3u8Bytes.isEmpty()) {
            sendResponse(output, if (statusCode in 400..599) statusCode else 502, "Bad Gateway", "text/plain; charset=utf-8", "Upstream m3u8 HTTP $statusCode".toByteArray(Charsets.UTF_8))
            return
        }

        val rewritten = rewriteM3u8Manifest(
            baseUrl = decodedUrl,
            manifestContent = String(m3u8Bytes, Charsets.UTF_8),
            channelId = channelId
        )

        sendResponse(
            output,
            200,
            "OK",
            "application/vnd.apple.mpegurl; charset=utf-8",
            rewritten.toByteArray(Charsets.UTF_8)
        )
    }

    private fun fetchUpstreamM3u8(targetUrl: String, channelId: String): Pair<Int, ByteArray?> {
        return try {
            val conn = Tls12SocketFactory.openConnection(URL(targetUrl), connectTimeoutMs = 8_000, readTimeoutMs = 8_000)
            try {
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", USER_AGENT_PLAYTV)
                val hdnea = channelHdneaMap[channelId] ?: extractQueryParamFromUrl(targetUrl, "hdnea")
                if (!hdnea.isNullOrBlank()) {
                    conn.setRequestProperty("Cookie", "__hdnea__=$hdnea")
                }

                val code = conn.responseCode
                captureHdneaFromSetCookie(conn, channelId)
                if (code == 200) {
                    val bytes = readStreamSafe(conn.inputStream)
                    Pair(200, bytes)
                } else {
                    Pair(code, null)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            LogCollector.log("[EmbeddedServer] fetchUpstreamM3u8 error: ${e.message}")
            Pair(502, null)
        }
    }

    /**
     * Rewrites variant `.m3u8`, `.ts`, `.aac`, and `#EXT-X-KEY` `.key`/`.pkey` URLs in an HLS manifest
     * so all child requests route through `/render.m3u8`, `/render.ts`, and `/render.key`.
     */
    private fun rewriteM3u8Manifest(
        baseUrl: String,
        manifestContent: String,
        channelId: String
    ): String {
        val baseUri = try {
            URI(baseUrl)
        } catch (_: Exception) {
            null
        }
        val baseQuery = baseUrl.substringAfter('?', "")
        val encodedChannelId = URLEncoder.encode(channelId, "UTF-8")

        val keyUriRegex = Regex("""URI="([^"]+)"""")
        val sb = StringBuilder(manifestContent.length + 1024)

        for (rawLine in manifestContent.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                sb.append("\n")
                continue
            }

            if (line.startsWith("#")) {
                if (line.contains("URI=\"")) {
                    val replacedTag = keyUriRegex.replace(line) { matchResult ->
                        val rawUri = matchResult.groupValues[1]
                        val absKeyUrl = resolveRelativeStreamUrl(baseUri, baseUrl, baseQuery, rawUri, appendBaseQuery = false)
                        val encodedAuth = URLEncoder.encode(encodeUrlBase64(absKeyUrl), "UTF-8")
                        if (rawUri.contains(".m3u8", ignoreCase = true)) {
                            """URI="/render.m3u8?auth=$encodedAuth&channel_key_id=$encodedChannelId""""
                        } else {
                            """URI="/render.key?auth=$encodedAuth&channel_key_id=$encodedChannelId""""
                        }
                    }
                    sb.append(replacedTag).append("\n")
                } else {
                    sb.append(line).append("\n")
                }
                continue
            }

            // Non-comment line -> variant .m3u8 or .ts/.aac media segment URI
            val lower = line.lowercase(Locale.US)
            val isM3u8 = lower.contains(".m3u8")
            val absStreamUrl = resolveRelativeStreamUrl(
                baseUri = baseUri,
                baseUrl = baseUrl,
                baseQuery = baseQuery,
                targetUri = line,
                appendBaseQuery = isM3u8
            )
            val encodedAuth = URLEncoder.encode(encodeUrlBase64(absStreamUrl), "UTF-8")
            if (isM3u8) {
                sb.append("/render.m3u8?auth=").append(encodedAuth)
                    .append("&channel_key_id=").append(encodedChannelId).append("\n")
            } else {
                sb.append("/render.ts?auth=").append(encodedAuth)
                    .append("&channel_key_id=").append(encodedChannelId).append("\n")
            }
        }
        return sb.toString()
    }

    private fun resolveRelativeStreamUrl(
        baseUri: URI?,
        baseUrl: String,
        baseQuery: String,
        targetUri: String,
        appendBaseQuery: Boolean
    ): String {
        val resolved = if (targetUri.startsWith("http://", ignoreCase = true) || targetUri.startsWith("https://", ignoreCase = true)) {
            targetUri
        } else if (baseUri != null) {
            try {
                baseUri.resolve(targetUri).toString()
            } catch (_: Exception) {
                val baseDir = baseUrl.substringBefore('?').substringBeforeLast('/')
                "$baseDir/${targetUri.trimStart('/')}"
            }
        } else {
            val baseDir = baseUrl.substringBefore('?').substringBeforeLast('/')
            "$baseDir/${targetUri.trimStart('/')}"
        }

        if (appendBaseQuery && baseQuery.isNotBlank() && !resolved.contains("?")) {
            return "$resolved?$baseQuery"
        }
        return resolved
    }

    // =========================================================================
    // 6. MEDIA SEGMENT STREAMING (/render.ts) & DRM KEY PROXY (/render.key)
    // =========================================================================
    private fun handleRenderTs(
        queryParams: Map<String, String>,
        clientHeaders: Map<String, String>,
        output: OutputStream
    ) {
        val auth = queryParams["auth"] ?: ""
        val channelId = queryParams["channel_key_id"] ?: ""
        val targetUrl = decodeUrlBase64(auth)
        if (targetUrl.isBlank()) {
            sendResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", "Invalid auth".toByteArray(Charsets.UTF_8))
            return
        }

        val conn = Tls12SocketFactory.openConnection(URL(targetUrl), connectTimeoutMs = 8_000, readTimeoutMs = 10_000)
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT_PLAYTV)
            val hdnea = channelHdneaMap[channelId] ?: extractQueryParamFromUrl(targetUrl, "hdnea")
            if (!hdnea.isNullOrBlank()) {
                conn.setRequestProperty("Cookie", "__hdnea__=$hdnea")
            }
            clientHeaders["range"]?.let { conn.setRequestProperty("Range", it) }

            val code = conn.responseCode
            captureHdneaFromSetCookie(conn, channelId)
            val contentType = conn.contentType ?: "video/MP2T"
            val contentLength = conn.contentLength

            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) {
                sendResponse(output, 502, "Bad Gateway", "text/plain", ByteArray(0))
                return
            }

            // Stream directly with minimal latency
            val headerBuilder = StringBuilder(256)
            headerBuilder.append("HTTP/1.1 ").append(code).append(" OK\r\n")
            headerBuilder.append("Content-Type: ").append(contentType).append("\r\n")
            if (contentLength >= 0) {
                headerBuilder.append("Content-Length: ").append(contentLength).append("\r\n")
            }
            headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
            headerBuilder.append("Connection: close\r\n\r\n")
            output.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))

            val buf = ByteArray(16 * 1024)
            stream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
            }
            output.flush()
        } finally {
            conn.disconnect()
        }
    }

    private fun handleRenderKey(
        queryParams: Map<String, String>,
        output: OutputStream
    ) {
        val auth = queryParams["auth"] ?: ""
        val channelId = queryParams["channel_key_id"] ?: ""
        val targetUrl = decodeUrlBase64(auth)
        if (targetUrl.isBlank()) {
            sendResponse(output, 400, "Bad Request", "text/plain; charset=utf-8", "Invalid auth".toByteArray(Charsets.UTF_8))
            return
        }

        val creds = credentials ?: appContext?.let { loadCredentialsFromDisk(it) }
        val conn = Tls12SocketFactory.openConnection(URL(targetUrl), connectTimeoutMs = 8_000, readTimeoutMs = 8_000)
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT_PLAYTV)
            conn.setRequestProperty("appkey", APP_KEY)
            conn.setRequestProperty("devicetype", "phone")
            conn.setRequestProperty("os", "android")
            conn.setRequestProperty("osVersion", "8.1.0")
            conn.setRequestProperty("deviceId", DEVICE_ID)
            conn.setRequestProperty("isott", "true")
            conn.setRequestProperty("languageId", "6")
            conn.setRequestProperty("lbcookie", "1")
            conn.setRequestProperty("srno", "230203144000")
            conn.setRequestProperty("usergroup", "tvYR7NSNn7rymo3F")
            conn.setRequestProperty("versionCode", VERSION_CODE)
            if (channelId.isNotBlank()) {
                conn.setRequestProperty("channelId", channelId)
                conn.setRequestProperty("channel_id", channelId)
            }
            if (creds != null) {
                conn.setRequestProperty("accesstoken", creds.accessToken)
                conn.setRequestProperty("ssotoken", creds.ssoToken)
                conn.setRequestProperty("crmid", creds.crm)
                conn.setRequestProperty("subscriberId", creds.crm)
                conn.setRequestProperty("uniqueId", creds.uniqueId)
            }
            val hdnea = channelHdneaMap[channelId] ?: extractQueryParamFromUrl(targetUrl, "hdnea")
            if (!hdnea.isNullOrBlank()) {
                conn.setRequestProperty("Cookie", "__hdnea__=$hdnea")
            }

            val code = conn.responseCode
            captureHdneaFromSetCookie(conn, channelId)
            val keyBytes = readStreamSafe(if (code in 200..299) conn.inputStream else conn.errorStream)
            sendResponse(output, code, if (code == 200) "OK" else "Error", "application/octet-stream", keyBytes)
        } finally {
            conn.disconnect()
        }
    }

    // =========================================================================
    // 7. CHANNEL LOGO PROXY (/jtvimage/:file)
    // =========================================================================
    private fun handleJtvImage(fileName: String, output: OutputStream) {
        if (fileName.isBlank()) {
            sendResponse(output, 404, "Not Found", "text/plain", ByteArray(0))
            return
        }
        val logoUrl = CATCHUP_LOGO_BASE_URL + fileName
        val conn = Tls12SocketFactory.openConnection(URL(logoUrl), connectTimeoutMs = 6_000, readTimeoutMs = 8_000)
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT_OKHTTP)
            val code = conn.responseCode
            if (code == 200) {
                val contentType = conn.contentType ?: "image/png"
                val imgBytes = readStreamSafe(conn.inputStream)
                sendResponse(output, 200, "OK", contentType, imgBytes)
            } else {
                sendResponse(output, code, "Not Found", "text/plain", ByteArray(0))
            }
        } finally {
            conn.disconnect()
        }
    }

    // =========================================================================
    // HTTP & URL UTILITIES
    // =========================================================================
    private fun captureHdneaFromSetCookie(conn: HttpURLConnection, channelId: String) {
        if (channelId.isBlank()) return
        try {
            val fields = conn.headerFields ?: return
            for ((k, values) in fields) {
                if (k != null && k.equals("Set-Cookie", ignoreCase = true)) {
                    for (cookie in values) {
                        if (cookie.contains("__hdnea__=")) {
                            val token = cookie.substringAfter("__hdnea__=").substringBefore(";").trim()
                            if (token.isNotBlank()) {
                                channelHdneaMap[channelId] = token
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun encodeUrlBase64(url: String): String =
        Base64.encodeToString(url.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)

    private fun decodeUrlBase64(encoded: String): String {
        return try {
            val bytes = Base64.decode(encoded.trim(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            try {
                val bytes = Base64.decode(encoded.trim(), Base64.DEFAULT)
                String(bytes, Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun extractQueryParamFromUrl(url: String, paramName: String): String? {
        val q = url.substringAfter('?', "")
        if (q.isBlank()) return null
        for (pair in q.split("&")) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = pair.substring(0, eq)
                if (k == paramName) {
                    return pair.substring(eq + 1)
                }
            }
        }
        return null
    }

    private fun replaceOrAddQueryParam(url: String, key: String, value: String): String {
        val base = url.substringBefore('?')
        val query = url.substringAfter('?', "")
        if (query.isBlank()) {
            return "$base?$key=$value"
        }
        val parts = query.split("&").toMutableList()
        var replaced = false
        for (i in parts.indices) {
            if (parts[i].startsWith("$key=")) {
                parts[i] = "$key=$value"
                replaced = true
            }
        }
        if (!replaced) {
            parts.add("$key=$value")
        }
        return "$base?${parts.joinToString("&")}"
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in rawQuery.split("&")) {
            if (pair.isBlank()) continue
            val eq = pair.indexOf('=')
            if (eq >= 0) {
                val k = try {
                    URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                } catch (_: Exception) {
                    pair.substring(0, eq)
                }
                val v = try {
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                } catch (_: Exception) {
                    pair.substring(eq + 1)
                }
                map[k] = v
            } else {
                map[pair] = ""
            }
        }
        return map
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder(128)
        var readAny = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (readAny) sb.toString() else null
            }
            readAny = true
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                sb.append(b.toChar())
            }
        }
        return sb.toString()
    }

    private fun readStreamSafe(stream: InputStream?): ByteArray {
        if (stream == null) return ByteArray(0)
        return try {
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            stream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    baos.write(buf, 0, n)
                }
            }
            baos.toByteArray()
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray
    ) {
        val header = buildString {
            append("HTTP/1.1 ").append(statusCode).append(' ').append(statusText).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) {
            output.write(body)
        }
        output.flush()
    }

    private fun sendRedirect(output: OutputStream, location: String) {
        val header = buildString {
            append("HTTP/1.1 302 Found\r\n")
            append("Location: ").append(location).append("\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.flush()
    }
}
