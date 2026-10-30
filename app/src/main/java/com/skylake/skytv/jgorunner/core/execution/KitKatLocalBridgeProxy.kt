package com.skylake.skytv.jgorunner.core.execution

import android.content.Context
import android.net.Proxy
import android.os.Build
import android.util.Log
import com.skylake.skytv.jgorunner.utils.LogCollector
import com.skylake.skytv.jgorunner.utils.Tls12SocketFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket

/**
 * Local HTTP CONNECT & HTTP proxy running on 127.0.0.1 for the `jiotv_go` native process on Android 4.4 KitKat.
 *
 * Why this is essential on Android 4.4 KitKat:
 * 1. `jiotv_go-linux-arm` (`CGO_ENABLED=0` static binary) uses Go's pure-Go DNS resolver, which reads `/etc/resolv.conf`.
 *    Android 4.4 KitKat does NOT have `/etc/resolv.conf`, causing Go's DNS resolver to fall back to `127.0.0.1:53`
 *    and fail all DNS lookups (`dial tcp: lookup ... on 127.0.0.1:53: connection refused`).
 * 2. When `jiotv_go` is configured with `proxy = "http://127.0.0.1:<proxyPort>"`, `fasthttp` sends `CONNECT host:port HTTP/1.1`
 *    to this local Java proxy. Java resolves the hostname using Android's native `netd` DNS resolver (`InetAddress.getByName`),
 *    completely bypassing `/etc/resolv.conf`!
 */
object KitKatLocalBridgeProxy {
    private const val TAG = "JTVGo::BridgeProxy"

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var listeningPort: Int = 0

    private val executor = Executors.newCachedThreadPool()

    @Synchronized
    fun ensureStarted(): Int {
        val existing = serverSocket
        if (existing != null && !existing.isClosed && listeningPort > 0) {
            return listeningPort
        }

        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("127.0.0.1", 0), 64)
            serverSocket = ss
            listeningPort = ss.localPort
            Log.i(TAG, "KitKat Local Bridge Proxy started on 127.0.0.1:$listeningPort")
            LogCollector.log("[BridgeProxy] Started local DNS/TCP bridge on 127.0.0.1:$listeningPort")

            Thread({
                while (!ss.isClosed) {
                    try {
                        val client = ss.accept()
                        executor.execute { handleClient(client) }
                    } catch (_: Exception) {
                        if (ss.isClosed) break
                    }
                }
            }, "KitKat-BridgeProxy-Accept").apply {
                isDaemon = true
                start()
            }

            listeningPort
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start KitKatLocalBridgeProxy", e)
            0
        }
    }

    private fun handleClient(clientSocket: Socket) {
        var remoteSocket: Socket? = null
        try {
            clientSocket.soTimeout = 20_000
            clientSocket.tcpNoDelay = true
            val clientIn = BufferedInputStream(clientSocket.getInputStream(), 8192)
            val clientOut = BufferedOutputStream(clientSocket.getOutputStream(), 8192)

            val headerBytes = readHttpHeaderBytes(clientIn)
            if (headerBytes.isEmpty()) return

            val headerStr = String(headerBytes, Charsets.ISO_8859_1)
            val lines = headerStr.split("\r\n")
            val requestLine = lines.firstOrNull() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val target = parts[1]

            if (method == "CONNECT") {
                val hostPort = target.substringAfter("://", target)
                val host = hostPort.substringBefore(':').trim()
                val port = hostPort.substringAfter(':', "443").toIntOrNull() ?: 443

                remoteSocket = Socket()
                remoteSocket.tcpNoDelay = true
                remoteSocket.connect(InetSocketAddress(host, port), 10_000)

                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                clientOut.flush()

                // Bidirectional raw TCP tunnel
                val remoteIn = remoteSocket.getInputStream()
                val remoteOut = remoteSocket.getOutputStream()

                val upstreamThread = Thread({
                    pipeStream(clientIn, remoteOut)
                    try {
                        remoteSocket.shutdownOutput()
                    } catch (_: Exception) {
                    }
                }, "Proxy-Up-$host")
                upstreamThread.isDaemon = true
                upstreamThread.start()

                pipeStream(remoteIn, clientOut)
                upstreamThread.join(2000)
            } else {
                // Standard HTTP proxy request (e.g., GET http://host:port/path HTTP/1.1)
                var host = ""
                var port = 80
                var path = target

                if (target.startsWith("http://", ignoreCase = true)) {
                    val withoutScheme = target.substring(7)
                    val slashIdx = withoutScheme.indexOf('/')
                    val hostPort = if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme
                    path = if (slashIdx >= 0) withoutScheme.substring(slashIdx) else "/"
                    host = hostPort.substringBefore(':')
                    port = hostPort.substringAfter(':', "80").toIntOrNull() ?: 80
                } else {
                    for (line in lines.drop(1)) {
                        if (line.startsWith("Host:", ignoreCase = true)) {
                            val hp = line.substring(5).trim()
                            host = hp.substringBefore(':')
                            port = hp.substringAfter(':', "80").toIntOrNull() ?: 80
                            break
                        }
                    }
                }

                if (host.isBlank()) return

                remoteSocket = Socket()
                remoteSocket.tcpNoDelay = true
                remoteSocket.connect(InetSocketAddress(host, port), 10_000)

                val remoteOut = BufferedOutputStream(remoteSocket.getOutputStream(), 8192)
                val rewrittenReq = buildString {
                    append("$method $path ${parts.getOrElse(2) { "HTTP/1.1" }}\r\n")
                    for (line in lines.drop(1)) {
                        if (line.isEmpty()) continue
                        if (line.startsWith("Proxy-", ignoreCase = true)) continue
                        append(line).append("\r\n")
                    }
                    append("\r\n")
                }
                remoteOut.write(rewrittenReq.toByteArray(Charsets.ISO_8859_1))
                remoteOut.flush()

                val upstreamThread = Thread({
                    pipeStream(clientIn, remoteOut)
                }, "Proxy-HttpUp-$host")
                upstreamThread.isDaemon = true
                upstreamThread.start()

                pipeStream(remoteSocket.getInputStream(), clientOut)
            }
        } catch (e: Exception) {
            Log.d(TAG, "BridgeProxy connection closed/error: ${e.message}")
        } finally {
            try {
                remoteSocket?.close()
            } catch (_: Exception) {
            }
            try {
                clientSocket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readHttpHeaderBytes(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(512)
        var state = 0 // matching \r\n\r\n
        while (out.size() < 16_384) {
            val b = input.read()
            if (b == -1) break
            out.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
        }
        return out.toByteArray()
    }

    private fun pipeStream(input: InputStream, output: OutputStream) {
        try {
            val buf = ByteArray(16 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                output.write(buf, 0, read)
                output.flush()
            }
        } catch (_: Exception) {
        }
    }
}
