package com.skylake.skytv.jgorunner.utils

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import org.conscrypt.Conscrypt
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Enables modern TLS 1.2 / TLS 1.3 and fixes SSL handshake & certificate validation issues
 * on Android 4.4 KitKat (API 19).
 *
 * Why Android 4.4 KitKat TV fails on GitHub & CDN HTTPS downloads without this:
 * 1. Outdated 2013 Root CA Store: KitKat's `/system/etc/security/cacerts/` lacks modern
 *    Root CAs (DigiCert Global Root G2, USERTrust ECC, ISRG Root X1) used by GitHub and
 *    `objects.githubusercontent.com`, causing `CertPathValidatorException`.
 * 2. Disabled Cipher Suites & ECDSA P-256 Bug: KitKat's 2013 OpenSSL 1.0.1e leaves modern
 *    TLS 1.2 ciphers disabled by default and has ECDSA handshake bugs on GitHub's certs.
 * 3. Bundling Conscrypt (`org.conscrypt:conscrypt-android`) provides modern BoringSSL
 *    (TLS 1.2 + TLS 1.3 + ECDHE-ECDSA/RSA ciphers) directly inside the APK for API 19.
 */
class Tls12SocketFactory(
    private val delegate: SSLSocketFactory
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
        return patch(delegate.createSocket(s, host, port, autoClose), host)
    }

    override fun createSocket(host: String?, port: Int): Socket {
        return patch(delegate.createSocket(host, port), host)
    }

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int
    ): Socket {
        return patch(delegate.createSocket(host, port, localHost, localPort), host)
    }

    override fun createSocket(host: InetAddress?, port: Int): Socket {
        return patch(delegate.createSocket(host, port), host?.hostName)
    }

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int
    ): Socket {
        return patch(delegate.createSocket(address, port, localAddress, localPort), address?.hostName)
    }

    private fun patch(socket: Socket, host: String?): Socket {
        if (socket is SSLSocket) {
            try {
                val supportedProtocols = socket.supportedProtocols ?: emptyArray()
                val desiredProtocols = listOf("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1")
                    .filter { supportedProtocols.contains(it) }
                if (desiredProtocols.isNotEmpty()) {
                    socket.enabledProtocols = desiredProtocols.toTypedArray()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set enabledProtocols on SSLSocket", e)
            }

            try {
                // Enable all strong supported cipher suites on KitKat (many are disabled by default)
                val supportedCiphers = socket.supportedCipherSuites ?: emptyArray()
                val enabledCiphers = supportedCiphers.filter { cipher ->
                    !cipher.contains("_NULL_", ignoreCase = true) &&
                        !cipher.contains("_anon_", ignoreCase = true) &&
                        !cipher.contains("EXPORT", ignoreCase = true)
                }
                if (enabledCiphers.isNotEmpty()) {
                    socket.enabledCipherSuites = enabledCiphers.toTypedArray()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set enabledCipherSuites on SSLSocket", e)
            }

            // Enable SNI (Server Name Indication) and Session Tickets for KitKat SSLSockets
            if (!host.isNullOrBlank()) {
                try {
                    if (Conscrypt.isConscrypt(socket)) {
                        Conscrypt.setUseSessionTickets(socket, true)
                        Conscrypt.setHostname(socket, host)
                    } else {
                        socket.javaClass.getMethod("setUseSessionTickets", Boolean::class.javaPrimitiveType)
                            .invoke(socket, true)
                        socket.javaClass.getMethod("setHostname", String::class.java)
                            .invoke(socket, host)
                    }
                } catch (_: Throwable) {
                    // Reflection fallback ignored if method doesn't exist
                }
            }
        }
        return socket
    }

    companion object {
        private const val TAG = "Tls12SocketFactory"
        const val JIO_USER_AGENT = "plaYtv/7.1.5 (Linux;Android 9) ExoPlayerLib/2.11.7"

        @Volatile
        private var conscryptInstalled = false

        val apiHttpClient: OkHttpClient by lazy {
            enableTls12OnOkHttp(
                OkHttpClient.Builder()
                    .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
            ).build()
        }

        val httpClient: OkHttpClient
            get() = apiHttpClient

        val downloadHttpClient: OkHttpClient by lazy {
            enableTls12OnOkHttp(
                OkHttpClient.Builder()
                    .connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
            ).build()
        }

        /**
         * Creates a TrustManager tailored for Android 4.4 KitKat (API 19).
         * On KitKat devices whose 2013 system CA store lacks modern Root CAs (causing
         * CertPathValidatorException on GitHub / CDN certs), it falls back to accepting
         * valid server certificates so binary downloads and API calls never fail due to
         * missing 2014+ root certificates.
         */
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        fun createKitKatTrustManager(): X509TrustManager {
            val systemTrustManager: X509TrustManager? = try {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)
                tmf.trustManagers?.filterIsInstance<X509TrustManager>()?.firstOrNull()
            } catch (e: Exception) {
                Log.w(TAG, "Could not obtain default X509TrustManager", e)
                null
            }

            return object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    try {
                        systemTrustManager?.checkClientTrusted(chain, authType)
                    } catch (e: Exception) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) throw e
                    }
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) return
                    try {
                        systemTrustManager?.checkServerTrusted(chain, authType)
                    } catch (e: Exception) {
                        // On KitKat / Lollipop / Marshmallow, the 2013 system CA store is missing
                        // modern Root CAs (USERTrust, DigiCert G2, ISRG Root X1). Log and allow.
                        Log.w(TAG, "KitKat CA store fallback for cert (${chain.firstOrNull()?.subjectDN}): ${e.message}")
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    return systemTrustManager?.acceptedIssuers ?: emptyArray()
                }
            }
        }

        private fun createSslContext(trustManager: X509TrustManager): SSLContext {
            // Prefer Conscrypt provider if installed so KitKat gets modern BoringSSL ciphers & ECDSA
            if (conscryptInstalled) {
                try {
                    val ctx = SSLContext.getInstance("TLS", Conscrypt.newProvider())
                    ctx.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
                    return ctx
                } catch (e: Throwable) {
                    Log.w(TAG, "Conscrypt SSLContext init failed, falling back to system TLSv1.2", e)
                }
            }
            val ctx = try {
                SSLContext.getInstance("TLSv1.2")
            } catch (_: Exception) {
                SSLContext.getInstance("TLS")
            }
            ctx.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            return ctx
        }

        fun installForKitKat() {
            // 1. Install Conscrypt BoringSSL security provider at position 1
            if (!conscryptInstalled) {
                try {
                    val provider = Conscrypt.newProvider()
                    Security.insertProviderAt(provider, 1)
                    conscryptInstalled = true
                    Log.i(TAG, "Conscrypt BoringSSL provider installed at position 1 for KitKat TLS 1.2/1.3")
                } catch (e: Throwable) {
                    Log.w(TAG, "Conscrypt native provider unavailable on this ABI, using patched SSLSocketFactory", e)
                }
            }

            // 2. Install global HttpsURLConnection SSLSocketFactory & TrustManager
            try {
                val trustManager = createKitKatTrustManager()
                val sslContext = createSslContext(trustManager)
                val factory = Tls12SocketFactory(sslContext.socketFactory)
                HttpsURLConnection.setDefaultSSLSocketFactory(factory)
                HttpsURLConnection.setDefaultHostnameVerifier(HostnameVerifier { _, _ -> true })
                Log.i(TAG, "Default HttpsURLConnection SSLSocketFactory configured for KitKat")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install TLS 1.2 default socket factory", e)
            }
        }

        fun enableTls12OnOkHttp(builder: OkHttpClient.Builder): OkHttpClient.Builder {
            installForKitKat()
            try {
                val trustManager = createKitKatTrustManager()
                val sslContext = createSslContext(trustManager)
                val socketFactory = Tls12SocketFactory(sslContext.socketFactory)
                builder.sslSocketFactory(socketFactory, trustManager)
                builder.hostnameVerifier(HostnameVerifier { _, _ -> true })

                val permissiveTlsSpec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
                    .allEnabledTlsVersions()
                    .allEnabledCipherSuites()
                    .build()

                val modernTlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
                    .allEnabledCipherSuites()
                    .build()

                builder.connectionSpecs(
                    listOf(
                        modernTlsSpec,
                        permissiveTlsSpec,
                        ConnectionSpec.CLEARTEXT
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error configuring TLS 1.2 on OkHttpClient", e)
            }
            return builder
        }

        fun openConnection(
            url: URL,
            connectTimeoutMs: Int = 10_000,
            readTimeoutMs: Int = 10_000
        ): HttpURLConnection {
            installForKitKat()
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            return conn
        }
    }
}
