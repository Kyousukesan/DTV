package com.dtv.androidtv.player

import com.dtv.androidtv.data.douyu.DouyuClient
import com.dtv.androidtv.data.douyu.DouyuConstants
import com.dtv.androidtv.data.net.HttpJsonClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Localhost FLV splice relay mirroring desktop `StreamRelayService`.
 *
 * Media3 plays `http://127.0.0.1:<port>/stream?id=…&t=…`; the relay re-signs
 * Douyu URLs and splices FLV tags so the player never sees the ~300s CDN cut.
 */
class DouyuFlvRelayServer(
    private val resolveStreamUrl: (roomId: String, quality: String, line: String) -> String,
) {
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "douyu-flv-relay").apply { isDaemon = true }
    }
    private val sessions = ConcurrentHashMap<String, RelaySession>()
    private val token = AtomicReference(UUID.randomUUID().toString().replace("-", ""))
    private val baseUrl = AtomicReference<String?>(null)
    private val lastStats = AtomicReference<FlvRelayStats?>(null)

    private val upstreamHttp: OkHttpClient = HttpJsonClient.defaultClient().newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    data class Prepared(
        val localUrl: String,
        val sessionId: String,
    )

    /** Register (or reuse) a Douyu room session and return the local play URL. */
    fun prepare(roomId: String, quality: String, line: String): Prepared {
        ensureStarted()
        val id = sessionId("douyu", roomId, quality, line)
        sessions[id] = RelaySession(
            id = id,
            roomId = roomId.trim(),
            quality = quality.trim(),
            line = line.trim(),
            createdAt = System.currentTimeMillis(),
        )
        pruneSessions()
        val url = "${baseUrl.get()}/stream?id=$id&t=${token.get()}"
        return Prepared(localUrl = url, sessionId = id)
    }

    fun lastRelayStats(): FlvRelayStats? = lastStats.get()

    fun shutdown() {
        synchronized(lock) {
            runCatching { serverSocket?.close() }
            serverSocket = null
            baseUrl.set(null)
            acceptThread = null
            sessions.clear()
        }
    }

    private fun ensureStarted() {
        synchronized(lock) {
            if (serverSocket != null && baseUrl.get() != null) return
            val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            baseUrl.set("http://127.0.0.1:${ss.localPort}")
            acceptThread = Thread({
                try {
                    while (!ss.isClosed) {
                        val socket = try {
                            ss.accept()
                        } catch (_: SocketException) {
                            break
                        }
                        executor.execute { handleClient(socket) }
                    }
                } catch (_: Exception) {
                    // server stopped
                }
            }, "douyu-flv-accept").apply {
                isDaemon = true
                start()
            }
            logI("FLV relay listening on ${baseUrl.get()}")
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 0
        try {
            val input = socket.getInputStream().buffered()
            val requestLine = readLine(input) ?: return
            // Drain headers
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2 || parts[0] != "GET") {
                writeResponse(socket, 405, "text/plain", "method not allowed")
                return
            }
            val pathQuery = parts[1]
            if (!pathQuery.startsWith("/stream")) {
                writeResponse(socket, 404, "text/plain", "not found")
                return
            }
            val query = pathQuery.substringAfter('?', "")
            val params = query.split('&').mapNotNull {
                val idx = it.indexOf('=')
                if (idx <= 0) null else it.substring(0, idx) to it.substring(idx + 1)
            }.toMap()
            if (params["t"] != token.get()) {
                writeResponse(socket, 403, "text/plain", "forbidden")
                return
            }
            val session = sessions[params["id"].orEmpty()]
            if (session == null) {
                writeResponse(socket, 404, "text/plain", "unknown relay session")
                return
            }
            session.lastAccess = System.currentTimeMillis()

            val firstUrl = try {
                resolveStreamUrl(session.roomId, session.quality, session.line)
            } catch (e: Exception) {
                writeResponse(socket, 502, "text/plain", e.message ?: "resolve failed")
                return
            }
            val firstBody = try {
                openUpstream(firstUrl, session.roomId)
            } catch (e: Exception) {
                writeResponse(socket, 502, "text/plain", e.message ?: "upstream failed")
                return
            }

            val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/x-flv\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "\r\n").toByteArray(Charsets.US_ASCII),
            )
            out.flush()

            val cancel = AtomicBoolean(false)
            val stats = FlvRelayStats()
            lastStats.set(stats)
            val opener = UpstreamOpener {
                val url = resolveStreamUrl(session.roomId, session.quality, session.line)
                openUpstream(url, session.roomId)
            }
            try {
                relayFlv(
                    first = firstBody,
                    open = opener,
                    output = out,
                    cancel = cancel,
                    renew = true,
                    stats = stats,
                    onSwitch = { elapsed, err ->
                        if (err != null) {
                            logW("relay renew failed room=${session.roomId} segment=${elapsed}ms: $err")
                        } else {
                            logI(
                                "relay spliced room=${session.roomId} segment=${elapsed}ms " +
                                    "switches=${stats.segmentSwitches.get()}",
                            )
                        }
                    },
                )
            } catch (e: Exception) {
                logW("relay client ended: ${e.message}")
            } finally {
                cancel.set(true)
                runCatching { firstBody.close() }
                runCatching { out.close() }
                runCatching { socket.close() }
            }
        } catch (e: Exception) {
            runCatching { socket.close() }
            logW("relay handle error: ${e.message}")
        }
    }

    private fun openUpstream(target: String, roomId: String): InputStream {
        val patched = DouyuClient.applyExpirePatch(target)
        val req = Request.Builder()
            .url(patched)
            .get()
            .header("User-Agent", DouyuConstants.DESKTOP_UA)
            .header("Accept", "video/x-flv,application/octet-stream,*/*")
            .header("Range", "bytes=0-")
            .header("Connection", "keep-alive")
            .header("Referer", DouyuClient.refererFor(roomId))
            .build()
        val resp = upstreamHttp.newCall(req).execute()
        if (!resp.isSuccessful) {
            val detail = resp.body?.string()?.take(200).orEmpty()
            resp.close()
            throw IOException("upstream HTTP ${resp.code}: $detail")
        }
        return resp.body?.byteStream() ?: throw IOException("empty upstream body")
    }

    private fun pruneSessions() {
        val now = System.currentTimeMillis()
        val idle = 30 * 60_000L
        sessions.entries.removeIf { now - it.value.lastAccess > idle }
        if (sessions.size <= 64) return
        val oldest = sessions.minByOrNull { it.value.lastAccess }?.key
        if (oldest != null) sessions.remove(oldest)
    }

    private data class RelaySession(
        val id: String,
        val roomId: String,
        val quality: String,
        val line: String,
        val createdAt: Long,
        @Volatile var lastAccess: Long = createdAt,
    )

    companion object {
        private const val TAG = "DouyuFlvRelay"

        private fun logI(msg: String) {
            println("I/$TAG: $msg")
            runCatching {
                Class.forName("android.util.Log")
                    .getMethod("i", String::class.java, String::class.java)
                    .invoke(null, TAG, msg)
            }
        }

        private fun logW(msg: String) {
            System.err.println("W/$TAG: $msg")
            runCatching {
                Class.forName("android.util.Log")
                    .getMethod("w", String::class.java, String::class.java)
                    .invoke(null, TAG, msg)
            }
        }

        fun sessionId(platform: String, roomId: String, quality: String, line: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest("$platform|$roomId|$quality|$line".toByteArray())
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

        private fun readLine(input: java.io.InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
            }
            return sb.toString()
        }

        private fun writeResponse(socket: Socket, code: Int, type: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            val out = socket.getOutputStream()
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
            socket.close()
        }
    }
}
