package com.dtv.androidtv.data.huya

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
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * Huya danmaku session — one room at a time (desktop rule).
 *
 * Bootstrap: [HuyaClient.resolveDanmakuChannelId] → TARS register on
 * [HuyaDanmakuProtocol.WS_URL]. Heartbeat is the captured OnUserHeartBeat blob.
 * Only nested type 1400 chat is emitted (gift/enter ignored, matching Go).
 *
 * OkHttp note: send the register payload inside [WebSocketListener.onOpen] (not
 * after a deferred await). Dial with a bare URL — Origin/Referer/UA headers make
 * Huya accept the socket but starve the chat topic stream.
 */
class HuyaDanmakuManager(
    private val externalScope: CoroutineScope? = null,
    private val client: HuyaClient = HuyaClient(),
) : DanmakuSession {
    private val ownJob = SupervisorJob()
    private val scope = externalScope ?: CoroutineScope(ownJob + Dispatchers.IO)

    private val mutex = Mutex()
    private var sessionJob: Job? = null
    private var runningRoom: String = ""
    private val activeSocket = AtomicReference<WebSocket?>(null)

    private val seq = AtomicLong(0)

    private val _events = MutableSharedFlow<DanmakuEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<DanmakuEvent> = _events.asSharedFlow()

    override suspend fun start(roomId: String) {
        val trimmed = roomId.trim()
        if (trimmed.isEmpty()) return
        mutex.withLock {
            sessionJob?.cancel()
            closeActiveSocket()
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
            closeActiveSocket()
            job?.cancelAndJoin()
        }
    }

    override fun runningRoom(): String = runningRoom

    override fun cancelAll() {
        sessionJob?.cancel()
        sessionJob = null
        runningRoom = ""
        closeActiveSocket()
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
            if (System.currentTimeMillis() - started >= STABLE_CONNECTION_MS) {
                backoffMs = 1_000L
            }
            delay(backoffMs)
            backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
        }
    }

    private suspend fun connectAndRead(roomId: String) = withContext(Dispatchers.IO) {
        val channelId = client.resolveDanmakuChannelId(roomId)
        val reg = HuyaDanmakuProtocol.buildRegPayload(channelId)
        logI("room=$roomId subscribe channelId=$channelId")

        val finished = CompletableDeferred<Throwable?>()
        val request = Request.Builder()
            .url(HuyaDanmakuProtocol.WS_URL)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Register must be the first client write (same timing as live probe).
                if (!webSocket.send(reg.toByteString())) {
                    finished.complete(IllegalStateException("发送注册包失败"))
                    return
                }
                logI("connected room=$roomId channelId=$channelId")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleFrame(roomId, bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleFrame(roomId, text.toByteArray(Charsets.UTF_8))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finished.complete(null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finished.complete(t)
            }
        }

        val ws = wsClient.newWebSocket(request, listener)
        activeSocket.set(ws)
        val heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                if (!ws.send(HuyaDanmakuProtocol.HEARTBEAT_BYTES.toByteString())) return@launch
            }
        }
        try {
            val err = finished.await()
            if (err != null) throw err
        } finally {
            heartbeat.cancel()
            activeSocket.compareAndSet(ws, null)
            runCatching { ws.close(1000, "bye") }
        }
    }

    private fun handleFrame(roomId: String, data: ByteArray) {
        val chat = HuyaDanmakuProtocol.decodeChat(data) ?: return
        val at = System.currentTimeMillis()
        emit(
            roomId,
            DanmakuMessage(
                id = nextId(roomId, at),
                nickname = chat.nickname,
                content = chat.content,
                level = 0,
                color = chat.color,
                kind = DanmakuKind.CHAT,
                at = at,
                mode = DanmakuMode.SCROLL,
            ),
        )
    }

    private fun closeActiveSocket() {
        activeSocket.getAndSet(null)?.let { runCatching { it.close(1000, "stop") } }
    }

    private fun emit(roomId: String, message: DanmakuMessage) {
        _events.tryEmit(
            DanmakuEvent(
                roomId = roomId,
                platform = LivePlatform.HUYA.id,
                message = message,
            ),
        )
    }

    private fun nextId(roomId: String, at: Long): String =
        "huya:$roomId:$at:${seq.incrementAndGet()}"

    companion object {
        private const val TAG = "HuyaDanmaku"
        private const val HEARTBEAT_MS = 20_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val STABLE_CONNECTION_MS = 10_000L

        private val wsClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .proxy(java.net.Proxy.NO_PROXY)
            .build()

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
