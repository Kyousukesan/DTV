package com.dtv.androidtv.data.douyu

import com.dtv.androidtv.data.danmaku.DanmakuSession
import com.dtv.androidtv.data.model.DanmakuEvent
import com.dtv.androidtv.data.model.DanmakuKind
import com.dtv.androidtv.data.model.DanmakuMessage
import com.dtv.androidtv.data.model.DanmakuMode
import com.dtv.androidtv.data.model.LivePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
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
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * Douyu danmaku session manager — one live room at a time (desktop rule).
 *
 * Emits [events] for chat / enter; caller filters for the overlay.
 */
class DouyuDanmakuManager(
    private val externalScope: CoroutineScope? = null,
) : DanmakuSession {
    private val ownJob = SupervisorJob()
    private val scope = externalScope ?: CoroutineScope(ownJob + Dispatchers.IO)

    private val mutex = Mutex()
    private var sessionJob: Job? = null
    private var runningRoom: String = ""
    private val activeSocket = AtomicReference<Socket?>(null)

    private val seq = AtomicLong(0)

    private val _events = MutableSharedFlow<DanmakuEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<DanmakuEvent> = _events.asSharedFlow()

    /** Start listening; stops any previous room first. */
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

    /**
     * Stop. Non-empty [roomId] only stops that room (avoids racing a newer Start).
     */
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

    /** Non-suspend teardown for ViewModel.onCleared. */
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
            if (System.currentTimeMillis() - started >= 10_000L) {
                backoffMs = 1_000L
            }
            delay(backoffMs)
            backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
        }
    }

    private suspend fun connectAndRead(roomId: String) = withContext(Dispatchers.IO) {
        val socket = dial()
        activeSocket.set(socket)
        try {
            socket.soTimeout = READ_TIMEOUT_MS.toInt()
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            writeStt(output, "type@=loginreq/roomid@=$roomId/")
            writeStt(output, "type@=joingroup/rid@=$roomId/gid@=1/")
            logI("connected room=$roomId remote=${socket.remoteSocketAddress}")

            coroutineScope {
                val heartbeat = launch {
                    while (isActive) {
                        delay(HEARTBEAT_MS)
                        try {
                            writeStt(output, "type@=mrkl/")
                        } catch (_: Exception) {
                            return@launch
                        }
                    }
                }
                try {
                    while (isActive) {
                        val packet = DouyuDanmakuProtocol.readPacket(input)
                        handlePacket(roomId, output, packet)
                    }
                } finally {
                    heartbeat.cancel()
                }
            }
        } finally {
            activeSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
        }
    }

    private fun closeActiveSocket() {
        activeSocket.getAndSet(null)?.let { runCatching { it.close() } }
    }

    private fun dial(): Socket {
        var last: Exception? = null
        for (endpoint in ENDPOINTS) {
            try {
                val (host, port) = endpoint
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), DIAL_TIMEOUT_MS.toInt())
                return socket
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("没有可用的斗鱼弹幕服务器")
    }

    private fun handlePacket(roomId: String, output: OutputStream, packet: ByteArray) {
        val fields = DouyuDanmakuProtocol.parseStt(DouyuDanmakuProtocol.packetBody(packet))
        when (fields["type"]) {
            "chatmsg" -> {
                val at = System.currentTimeMillis()
                val txt = fields["txt"].orEmpty()
                if (txt.isBlank()) return
                emit(
                    roomId,
                    DanmakuMessage(
                        id = nextId(roomId, at),
                        nickname = fields["nn"].orEmpty().ifBlank { "未知用户" },
                        content = txt,
                        level = fields["level"]?.toIntOrNull() ?: 0,
                        color = DouyuDanmakuProtocol.colorFromDouyuCol(fields["col"]),
                        kind = DanmakuKind.CHAT,
                        at = at,
                        mode = DanmakuMode.SCROLL,
                    ),
                )
            }
            "uenter" -> {
                val at = System.currentTimeMillis()
                emit(
                    roomId,
                    DanmakuMessage(
                        id = nextId(roomId, at),
                        nickname = fields["nn"].orEmpty().ifBlank { "未知用户" },
                        content = fields["str"].orEmpty().ifBlank { "进入直播间" },
                        level = fields["level"]?.toIntOrNull() ?: 0,
                        kind = DanmakuKind.ENTER,
                        at = at,
                        // Enter notices sit at the bottom briefly (desktop panel style).
                        mode = DanmakuMode.BOTTOM,
                    ),
                )
            }
            "pingreq" -> writeStt(output, "type@=mrkl/")
        }
    }

    private fun emit(roomId: String, message: DanmakuMessage) {
        _events.tryEmit(
            DanmakuEvent(
                roomId = roomId,
                platform = LivePlatform.DOUYU.id,
                message = message,
            ),
        )
    }

    private fun nextId(roomId: String, at: Long): String =
        "douyu:$roomId:$at:${seq.incrementAndGet()}"

    private fun writeStt(output: OutputStream, msg: String) {
        output.write(DouyuDanmakuProtocol.encodeStt(msg))
        output.flush()
    }

    companion object {
        private const val TAG = "DouyuDanmaku"
        private val ENDPOINTS = listOf(
            "danmuproxy.douyu.com" to 8601,
            "danmuproxy.douyu.com" to 8602,
            "danmuproxy.douyu.com" to 8603,
        )
        private const val HEARTBEAT_MS = 45_000L
        private const val READ_TIMEOUT_MS = 90_000L
        private const val DIAL_TIMEOUT_MS = 10_000L
        private const val MAX_BACKOFF_MS = 30_000L

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
