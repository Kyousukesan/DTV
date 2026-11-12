package com.dtv.androidtv.data.bilibili

import com.dtv.androidtv.data.danmaku.DanmakuSession
import com.dtv.androidtv.data.douyu.JsonUtil
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
import kotlinx.coroutines.coroutineScope
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlin.math.min

/**
 * Bilibili live danmaku session — one room at a time (desktop rule).
 *
 * Bootstrap: [BilibiliClient.roomInit] → real id, then WBI [BilibiliClient.danmuInfo]
 * for token + hosts. Transport: OkHttp WSS `wss://{host}/sub`, protover=2 (zlib).
 */
class BilibiliDanmakuManager(
    private val externalScope: CoroutineScope? = null,
    private val client: BilibiliClient = BilibiliClient(),
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
        val trimmed = BilibiliClient.normalizeRoomId(roomId)
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
        val trimmed = roomId.trim().let {
            if (it.isEmpty()) it else BilibiliClient.normalizeRoomId(it)
        }
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
                connectOnce(roomId)
            } catch (e: CancellationException) {
                throw e
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

    private suspend fun connectOnce(roomId: String) = withContext(Dispatchers.IO) {
        val init = client.roomInit(roomId)
        val realId = init.realId.ifBlank { roomId }
        if (init.liveStatus != 1L && init.liveStatus != 2L) {
            logI("room=$roomId live_status=${init.liveStatus}, still connecting")
        }

        val danmu = client.danmuInfo(realId)
        if (danmu.token.isEmpty() || danmu.hosts.isEmpty()) {
            throw IllegalStateException("没有可用的弹幕服务器")
        }

        var last: Exception? = null
        for (host in danmu.hosts) {
            if (!isActive) return@withContext
            val url = "wss://$host/sub"
            try {
                connectAndRead(roomId, realId, danmu.token, url)
                return@withContext
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                logW("host=$host failed: ${e.message}")
            }
        }
        throw last ?: IllegalStateException("所有弹幕服务器均连接失败")
    }

    private suspend fun connectAndRead(
        roomId: String,
        realId: String,
        token: String,
        url: String,
    ) = coroutineScope {
        val incoming = Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val opened = CompletableDeferred<Unit>()
        val failureRef = AtomicReference<Throwable?>(null)

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BilibiliConstants.DESKTOP_UA)
            .header("Origin", BilibiliConstants.ORIGIN)
            .header("Referer", BilibiliConstants.REFERER)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                incoming.trySend(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.trySend(text.toByteArray(Charsets.UTF_8))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                incoming.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                incoming.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failureRef.compareAndSet(null, t)
                if (!opened.isCompleted) opened.completeExceptionally(t)
                incoming.close(t)
            }
        }

        val ws = wsClient.newWebSocket(request, listener)
        activeSocket.set(ws)
        try {
            opened.await()
            val authJson = JSONObject()
                .put("uid", 0)
                .put("roomid", realId.toLongOrNull() ?: realId)
                .put("protover", BilibiliDanmakuProtocol.PROTOVER)
                .put("platform", "web")
                .put("type", 2)
                .put("key", token)
                .toString()
            val authPacket = BilibiliDanmakuProtocol.encodePacket(
                BilibiliDanmakuProtocol.OP_AUTH,
                authJson.toByteArray(Charsets.UTF_8),
            )
            if (!ws.send(authPacket.toByteString())) {
                throw IllegalStateException("发送 auth 失败")
            }
            logI("connected room=$roomId realId=$realId url=$url")

            val heartbeat = launch {
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    val hb = BilibiliDanmakuProtocol.encodePacket(
                        BilibiliDanmakuProtocol.OP_HEARTBEAT,
                        "{}".toByteArray(Charsets.UTF_8),
                    )
                    if (!ws.send(hb.toByteString())) return@launch
                }
            }
            try {
                while (isActive) {
                    try {
                        handleFrame(roomId, incoming.receive())
                    } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                        throw failureRef.get() ?: IllegalStateException("弹幕连接已关闭", e)
                    }
                }
            } finally {
                heartbeat.cancel()
            }
        } finally {
            activeSocket.compareAndSet(ws, null)
            runCatching { ws.close(1000, "bye") }
            incoming.close()
        }
    }

    private fun closeActiveSocket() {
        activeSocket.getAndSet(null)?.let { runCatching { it.close(1000, "stop") } }
    }

    private fun handleFrame(roomId: String, data: ByteArray) {
        val packets = try {
            BilibiliDanmakuProtocol.decodePackets(data)
        } catch (e: Exception) {
            logW("parse frame failed: ${e.message}")
            return
        }
        for (p in packets) {
            handlePacket(roomId, p)
        }
    }

    private fun handlePacket(roomId: String, packet: BilibiliDanmakuProtocol.Packet) {
        when (packet.op) {
            BilibiliDanmakuProtocol.OP_AUTH_REPLY -> {
                val code = runCatching {
                    JSONObject(String(packet.body, Charsets.UTF_8)).optInt("code")
                }.getOrDefault(0)
                if (code != 0) {
                    logW("room=$roomId auth rejected code=$code")
                } else {
                    logI("room=$roomId auth ok")
                }
            }
            BilibiliDanmakuProtocol.OP_HEARTBEAT_REPLY -> Unit
            BilibiliDanmakuProtocol.OP_NOTIFICATION -> handleCommand(roomId, packet.body)
        }
    }

    private fun handleCommand(roomId: String, body: ByteArray) {
        val text = String(body, Charsets.UTF_8)
        val env = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (env.optString("cmd")) {
            "DANMU_MSG" -> handleDanmuMsg(roomId, env.optJSONArray("info"))
            "SEND_GIFT" -> handleGift(roomId, env.optJSONObject("data"))
            "INTERACT_WORD" -> handleInteract(roomId, env.optJSONObject("data"))
        }
    }

    private fun handleDanmuMsg(roomId: String, info: JSONArray?) {
        if (info == null || info.length() < 3) return
        val content = info.optString(1).trim()
        if (content.isEmpty()) return

        var nickname = "匿名"
        val user = info.optJSONArray(2)
        if (user != null && user.length() > 1) {
            val name = user.optString(1).trim()
            if (name.isNotEmpty()) nickname = name
        }

        var color = ""
        val meta = info.optJSONArray(0)
        if (meta != null && meta.length() > 3) {
            color = BilibiliDanmakuProtocol.colorHex(JsonUtil.asLong(meta.opt(3)))
        }

        var level = 0
        if (info.length() > 3) {
            val medal = info.optJSONArray(3)
            if (medal != null && medal.length() > 0) {
                level = JsonUtil.asLong(medal.opt(0)).toInt()
            }
        }

        val at = System.currentTimeMillis()
        emit(
            roomId,
            DanmakuMessage(
                id = nextId(roomId, at),
                nickname = nickname,
                content = content,
                level = level,
                color = color,
                kind = DanmakuKind.CHAT,
                at = at,
                mode = DanmakuMode.SCROLL,
            ),
        )
    }

    private fun handleGift(roomId: String, data: JSONObject?) {
        if (data == null) return
        val giftName = data.optString("giftName").trim()
        if (giftName.isEmpty()) return
        var action = data.optString("action").trim()
        if (action.isEmpty()) action = "赠送"
        val num = JsonUtil.asString(data.opt("num")).ifBlank { "1" }
        val at = System.currentTimeMillis()
        emit(
            roomId,
            DanmakuMessage(
                id = nextId(roomId, at),
                nickname = data.optString("uname"),
                content = "[礼物] $action $giftName×$num",
                kind = DanmakuKind.GIFT,
                at = at,
                mode = DanmakuMode.SCROLL,
            ),
        )
    }

    private fun handleInteract(roomId: String, data: JSONObject?) {
        if (data == null) return
        val uname = data.optString("uname").trim()
        if (uname.isEmpty()) return
        val at = System.currentTimeMillis()
        emit(
            roomId,
            DanmakuMessage(
                id = nextId(roomId, at),
                nickname = uname,
                content = "进入直播间",
                kind = DanmakuKind.ENTER,
                at = at,
                mode = DanmakuMode.BOTTOM,
            ),
        )
    }

    private fun emit(roomId: String, message: DanmakuMessage) {
        _events.tryEmit(
            DanmakuEvent(
                roomId = roomId,
                platform = LivePlatform.BILIBILI.id,
                message = message,
            ),
        )
    }

    private fun nextId(roomId: String, at: Long): String =
        "bilibili:$roomId:$at:${seq.incrementAndGet()}"

    companion object {
        private const val TAG = "BilibiliDanmaku"
        private const val HEARTBEAT_MS = 30_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val STABLE_CONNECTION_MS = 10_000L

        private val wsClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WS: no idle read timeout
            .writeTimeout(30, TimeUnit.SECONDS)
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
