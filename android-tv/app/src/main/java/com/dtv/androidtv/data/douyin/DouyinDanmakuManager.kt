package com.dtv.androidtv.data.douyin

import com.dtv.androidtv.data.danmaku.DanmakuSession
import com.dtv.androidtv.data.model.DanmakuEvent
import com.dtv.androidtv.data.model.DanmakuKind
import com.dtv.androidtv.data.model.DanmakuMessage
import com.dtv.androidtv.data.model.DanmakuMode
import com.dtv.androidtv.data.model.LivePlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.math.min

/** Credentials for Douyin danmaku WSS (Go `wsCredentials`). */
data class DouyinDanmakuCredentials(
    val webRid: String,
    /** Internal 19-digit room_id used on the WS query. */
    val roomId: String,
    val userUniqueId: String,
    val cookie: String,
)

/**
 * Douyin danmaku session — one live room at a time (mirrors Go DanmakuManager
 * + Douyu Android [DanmakuSession] semantics).
 */
class DouyinDanmakuManager(
    private val externalScope: CoroutineScope? = null,
    private val client: DouyinClient = DouyinClient(),
) : DanmakuSession {
    private val ownJob = SupervisorJob()
    private val scope = externalScope ?: CoroutineScope(ownJob + Dispatchers.IO)

    private val mutex = Mutex()
    private var sessionJob: Job? = null
    private var runningRoom: String = ""
    private val activeWs = AtomicReference<WebSocket?>(null)

    private val seq = AtomicLong(0)

    private val _events = MutableSharedFlow<DanmakuEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<DanmakuEvent> = _events.asSharedFlow()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DIAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override suspend fun start(roomId: String) {
        val trimmed = roomId.trim()
        if (trimmed.isEmpty()) return
        mutex.withLock {
            sessionJob?.cancel()
            closeActiveWs()
            sessionJob?.join()
            runningRoom = trimmed
            sessionJob = scope.launch {
                runSession(trimmed)
            }
        }
    }

    override suspend fun stop(roomId: String) {
        val trimmed = roomId.trim()
        mutex.withLock {
            if (runningRoom.isEmpty()) return
            if (trimmed.isNotEmpty() && runningRoom != trimmed) return
            val job = sessionJob
            sessionJob = null
            runningRoom = ""
            job?.cancel()
            closeActiveWs()
            job?.cancelAndJoin()
        }
    }

    override fun runningRoom(): String = runningRoom

    override fun cancelAll() {
        sessionJob?.cancel()
        sessionJob = null
        runningRoom = ""
        closeActiveWs()
        ownJob.cancel()
    }

    fun release() = cancelAll()

    private suspend fun runSession(roomId: String) {
        var backoffMs = 1_000L
        while (scope.isActive) {
            val started = System.currentTimeMillis()
            try {
                connectAndRead(roomId)
            } catch (e: Exception) {
                if (!scope.isActive) return
                logW("room=$roomId disconnected: ${e.message}; retry in ${backoffMs}ms")
            }
            if (!scope.isActive) return
            if (System.currentTimeMillis() - started >= 10_000L) {
                backoffMs = 1_000L
            }
            delay(backoffMs)
            backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
        }
    }

    private suspend fun connectAndRead(roomId: String) = withContext(Dispatchers.IO) {
        val creds = client.resolveDanmakuCredentials(roomId)
        val query = DouyinWsSign.buildWsQuery("", "", creds.userUniqueId, creds.roomId)

        var last: Exception? = null
        for (host in WS_HOSTS) {
            val base = "wss://$host/$query"
            val sig = try {
                DouyinWsSign.signature(base)
            } catch (e: Exception) {
                // Fallback: dial with empty signature once Rhino/sign.js fails.
                logW("sign.js failed (${e.message}); dialing with empty signature")
                DouyinWsSign.signatureOrEmpty(base)
            }
            val target = "$base&signature=$sig"
            logI("try host=$host room=${creds.roomId} uid=${creds.userUniqueId} sigLen=${sig.length}")

            try {
                readLoop(target, creds, roomId)
                return@withContext
            } catch (e: Exception) {
                last = e
                logW("host $host failed: ${e.message}")
            }
        }
        throw last ?: IllegalStateException("没有可用的抖音弹幕域名")
    }

    private suspend fun readLoop(target: String, creds: DouyinDanmakuCredentials, emitRoomId: String) {
        val frames = Channel<ByteArray>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Throwable?>()

        val request = Request.Builder()
            .url(target)
            .header("User-Agent", DouyinConstants.DESKTOP_UA)
            .header("Referer", DouyinConstants.URL_LIVE_HOME)
            .header("Origin", "https://live.douyin.com")
            .apply {
                if (creds.cookie.isNotEmpty()) header("Cookie", creds.cookie)
            }
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                frames.trySend(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                frames.trySend(text.toByteArray(Charsets.UTF_8))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(null)
                frames.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened.isCompleted) opened.completeExceptionally(t)
                closed.complete(t)
                frames.close(t)
            }
        }

        val ws = http.newWebSocket(request, listener)
        activeWs.set(ws)
        try {
            opened.await()
            logI("connected room=$emitRoomId internal=${creds.roomId}")

            val heartbeat = scope.launch {
                val hb = DouyinDanmakuPb.PushFrame(payloadType = "hb").encode()
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    if (!ws.send(ByteString.of(*hb))) return@launch
                }
            }
            try {
                while (scope.isActive) {
                    val data = frames.receiveCatching().getOrNull() ?: break
                    handleFrame(ws, emitRoomId, data)
                }
                val err = closed.await()
                if (err != null) throw err
            } finally {
                heartbeat.cancel()
            }
        } finally {
            activeWs.compareAndSet(ws, null)
            runCatching { ws.close(1000, "bye") }
            runCatching { ws.cancel() }
            frames.close()
        }
    }

    private fun handleFrame(ws: WebSocket, roomId: String, data: ByteArray) {
        val frame = runCatching { DouyinDanmakuPb.decodePushFrame(data) }.getOrElse {
            debugLog("frame decode failed: ${it.message}")
            return
        }
        debugLog("frame type=${frame.payloadType} payload=${frame.payload.size}")
        if (frame.payloadType != "msg" || frame.payload.isEmpty()) return

        val plain = gunzip(frame.payload) ?: return
        val resp = runCatching { DouyinDanmakuPb.decodeResponse(plain) }.getOrElse {
            debugLog("response decode failed: ${it.message}")
            return
        }
        debugLog("messages=${resp.messages.size} needAck=${resp.needAck}")

        if (resp.needAck && resp.internalExt.isNotEmpty()) {
            val ack = DouyinDanmakuPb.PushFrame(
                logId = frame.logId,
                payloadType = "ack",
                payload = resp.internalExt.toByteArray(Charsets.UTF_8),
            ).encode()
            if (!ws.send(ByteString.of(*ack))) {
                logW("room=$roomId ACK send failed")
            } else {
                debugLog("ACK sent")
            }
        }

        for (msg in resp.messages) {
            if (msg.method != "WebcastChatMessage") continue
            val chat = runCatching { DouyinDanmakuPb.decodeChatMessage(msg.payload) }.getOrNull()
                ?: continue
            val content = chat.content.trim()
            if (content.isEmpty()) continue
            val nick = chat.nickname.ifBlank { "匿名" }
            val at = System.currentTimeMillis()
            emit(
                roomId,
                DanmakuMessage(
                    id = nextId(roomId, at),
                    nickname = nick,
                    content = content,
                    level = chat.userLevel.toInt(),
                    color = "#FFFFFF",
                    kind = DanmakuKind.CHAT,
                    at = at,
                    mode = DanmakuMode.SCROLL,
                ),
            )
        }
    }

    private fun gunzip(payload: ByteArray): ByteArray? =
        runCatching {
            GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
        }.onFailure {
            debugLog("gzip failed: ${it.message}")
        }.getOrNull()

    private fun closeActiveWs() {
        activeWs.getAndSet(null)?.let { ws ->
            runCatching { ws.close(1000, "stop") }
            runCatching { ws.cancel() }
        }
    }

    private fun emit(roomId: String, message: DanmakuMessage) {
        _events.tryEmit(
            DanmakuEvent(
                roomId = roomId,
                platform = LivePlatform.DOUYIN.id,
                message = message,
            ),
        )
    }

    private fun nextId(roomId: String, at: Long): String =
        "douyin:$roomId:$at:${seq.incrementAndGet()}"

    companion object {
        private const val TAG = "DouyinDanmaku"

        private val WS_HOSTS = listOf(
            "webcast3-ws-web-lq.douyin.com",
            "webcast5-ws-web-lf.douyin.com",
            "webcast5-ws-web-hl.douyin.com",
            "webcast3-ws-web-hl.douyin.com",
            "webcast3-ws-web-lf.douyin.com",
        )

        private const val HEARTBEAT_MS = 5_000L
        private const val DIAL_TIMEOUT_MS = 15_000L
        private const val MAX_BACKOFF_MS = 30_000L

        private val debugEnabled: Boolean =
            System.getenv("DTV_DANMAKU_DEBUG")?.isNotBlank() == true ||
                System.getProperty("dtv.danmakuDebug")?.isNotBlank() == true

        private fun debugLog(msg: String) {
            if (debugEnabled) logI("[debug] $msg")
        }

        /** JVM unit tests lack android.util.Log; println + reflective Log. */
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
    }
}
