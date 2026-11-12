package com.dtv.androidtv.player

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Kotlin port of Go `internal/services/flv_relay.go`.
 *
 * Douyu CDN FLV URLs hard-cut at ~300s. Prefetch a freshly signed upstream before
 * expiry and splice on FLV tag / keyframe boundaries so Media3 sees one continuous
 * progressive FLV body (no MediaItem hard restart every few minutes).
 */
object FlvRelayDefaults {
    const val STREAM_TTL_MS = 300_000L
    const val RENEW_LEAD_MS = 5_000L
    const val RETRY_DELAY_MS = 500L
    const val MAX_FAILURES = 5
    const val MAX_PENDING = 8 shl 20
    const val READ_BUFFER = 32 * 1024
}

internal const val FLV_TAG_AUDIO: Int = 8
internal const val FLV_TAG_VIDEO: Int = 9
internal const val FLV_TAG_SCRIPT: Int = 18

internal data class FlvTag(
    val raw: ByteArray,
    val typ: Int,
    val timestamp: Long,
    val pts: Long,
    val keyframe: Boolean,
    val sequenceHeader: Boolean,
)

/** Incremental FLV demuxer (file header + tags). */
internal class FlvByteDemuxer {
    private var buf = ByteArray(FlvRelayDefaults.READ_BUFFER)
    private var start = 0
    private var end = 0
    var headerDone: Boolean = false
        private set
    var header: ByteArray = ByteArray(0)
        private set

    fun pending(): Int = end - start

    fun reset() {
        start = 0
        end = 0
        header = ByteArray(0)
        headerDone = false
    }

    fun push(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size) {
        ensureCapacity(length)
        System.arraycopy(chunk, offset, buf, end, length)
        end += length
    }

    private fun ensureCapacity(extra: Int) {
        val pending = pending()
        if (start > 0 && pending + extra > buf.size - end) {
            System.arraycopy(buf, start, buf, 0, pending)
            start = 0
            end = pending
        }
        if (pending + extra > buf.size) {
            var cap = maxOf(buf.size * 2, pending + extra)
            val next = ByteArray(cap)
            System.arraycopy(buf, start, next, 0, pending)
            buf = next
            start = 0
            end = pending
        }
    }

    fun skipHeader(): Boolean {
        if (headerDone) return true
        if (pending() < 9) return false
        var total = 0
        if (buf[start] == 'F'.code.toByte() &&
            buf[start + 1] == 'L'.code.toByte() &&
            buf[start + 2] == 'V'.code.toByte()
        ) {
            var dataOffset = readU32(start + 5).toInt()
            if (dataOffset < 9) dataOffset = 9
            total = dataOffset + 4
            if (pending() < total) return false
        }
        header = buf.copyOfRange(start, start + total)
        start += total
        headerDone = true
        return true
    }

    fun nextTag(): FlvTag? {
        if (!skipHeader()) return null
        if (pending() < 11) return null
        val size = ((buf[start + 1].toInt() and 0xff) shl 16) or
            ((buf[start + 2].toInt() and 0xff) shl 8) or
            (buf[start + 3].toInt() and 0xff)
        val total = 11 + size + 4
        if (pending() < total) return null

        val typ = buf[start].toInt() and 0x1f
        val ts = ((buf[start + 7].toInt() and 0xff).toLong() shl 24) or
            ((buf[start + 4].toInt() and 0xff).toLong() shl 16) or
            ((buf[start + 5].toInt() and 0xff).toLong() shl 8) or
            (buf[start + 6].toInt() and 0xff).toLong()

        var keyframe = false
        var seq = false
        var cts = 0
        if (size >= 2) {
            val first = buf[start + 11].toInt() and 0xff
            val second = buf[start + 12].toInt() and 0xff
            when (typ) {
                FLV_TAG_VIDEO -> {
                    keyframe = (first shr 4) == 1
                    seq = second == 0
                    if (second == 1 && size >= 5) {
                        var rawCts = ((buf[start + 13].toInt() and 0xff) shl 16) or
                            ((buf[start + 14].toInt() and 0xff) shl 8) or
                            (buf[start + 15].toInt() and 0xff)
                        if (rawCts and 0x800000 != 0) rawCts = rawCts or -0x1000000
                        cts = rawCts
                    }
                }
                FLV_TAG_AUDIO -> seq = second == 0
            }
        }

        val raw = buf.copyOfRange(start, start + total)
        start += total
        if (start == end) {
            start = 0
            end = 0
        }
        return FlvTag(raw, typ, ts, ts + cts, keyframe, seq)
    }

    private fun readU32(index: Int): Long =
        ((buf[index].toInt() and 0xff).toLong() shl 24) or
            ((buf[index + 1].toInt() and 0xff).toLong() shl 16) or
            ((buf[index + 2].toInt() and 0xff).toLong() shl 8) or
            (buf[index + 3].toInt() and 0xff).toLong()
}

internal class FlvTimeline {
    private var lastVideoTs = 0L
    private var hasVideo = false
    private var lastAudioTs = 0L
    private var hasAudio = false

    fun observe(tag: FlvTag) {
        when (tag.typ) {
            FLV_TAG_VIDEO -> {
                lastVideoTs = tag.timestamp
                hasVideo = true
            }
            FLV_TAG_AUDIO -> {
                lastAudioTs = tag.timestamp
                hasAudio = true
            }
        }
    }

    fun isAhead(tag: FlvTag): Boolean =
        when (tag.typ) {
            FLV_TAG_VIDEO -> !hasVideo || tag.timestamp > lastVideoTs
            FLV_TAG_AUDIO -> !hasAudio || tag.timestamp > lastAudioTs
            else -> true
        }
}

/** Stats for continuity evidence / logging. */
class FlvRelayStats {
    val bytesOut = AtomicLong(0)
    val segmentSwitches = AtomicInteger(0)
    val renewFailures = AtomicInteger(0)
    val runningMs = AtomicLong(0)
    @Volatile var lastEvent: String = "idle"
}

fun interface UpstreamOpener {
    fun open(): InputStream
}

/**
 * Write one logical FLV stream to [output], splicing across CDN cuts.
 *
 * @param streamTtlMs Douyu hard cut (~300s); injectable for accelerated tests.
 */
fun relayFlv(
    first: InputStream,
    open: UpstreamOpener,
    output: OutputStream,
    cancel: AtomicBoolean,
    renew: Boolean = true,
    streamTtlMs: Long = FlvRelayDefaults.STREAM_TTL_MS,
    renewLeadMs: Long = FlvRelayDefaults.RENEW_LEAD_MS,
    stats: FlvRelayStats? = null,
    onSwitch: ((elapsedMs: Long, error: Throwable?) -> Unit)? = null,
) {
    val wallStart = System.currentTimeMillis()
    var segment = FlvSegment(first, continuation = false)
    val timeline = FlvTimeline()
    var trackTimeline = renew
    var failures = 0
    val buf = ByteArray(FlvRelayDefaults.READ_BUFFER)
    var prefetch: PrefetchHandle? = null

    fun maybeStartPrefetch() {
        if (!renew || prefetch != null) return
        if (System.currentTimeMillis() - segment.startedAt + renewLeadMs < streamTtlMs) return
        prefetch = PrefetchHandle.start(open)
        stats?.lastEvent = "prefetch_started"
    }

    fun takePrefetched(): InputStream {
        val p = prefetch
        prefetch = null
        return if (p != null) p.await() else open.open()
    }

    fun switchSegment(reason: String) {
        val elapsed = System.currentTimeMillis() - segment.startedAt
        runCatching { segment.body.close() }
        stats?.runningMs?.set(System.currentTimeMillis() - wallStart)
        try {
            val next = takePrefetched()
            failures = 0
            stats?.segmentSwitches?.incrementAndGet()
            stats?.lastEvent = "$reason after ${elapsed}ms"
            onSwitch?.invoke(elapsed, null)
            segment = FlvSegment(next, continuation = true)
        } catch (err: Exception) {
            failures++
            stats?.renewFailures?.incrementAndGet()
            stats?.lastEvent = "renew_fail:$failures ${err.message}"
            onSwitch?.invoke(elapsed, err)
            if (failures >= FlvRelayDefaults.MAX_FAILURES) {
                cancel.set(true)
                return
            }
            try {
                Thread.sleep(FlvRelayDefaults.RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                cancel.set(true)
                return
            }
            try {
                val body = open.open()
                failures = 0
                stats?.segmentSwitches?.incrementAndGet()
                segment = FlvSegment(body, continuation = true)
            } catch (_: Exception) {
                // continue failure budget on next loop
            }
        }
    }

    try {
        while (!cancel.get()) {
            maybeStartPrefetch()
            // Some Douyu edges keep the TCP open past ~300s after expire=0; still rotate
            // onto a freshly signed URL at TTL so Media3 never sits on a dying lease.
            if (renew && System.currentTimeMillis() - segment.startedAt >= streamTtlMs) {
                switchSegment("ttl_rotate")
                continue
            }
            val n = try {
                segment.body.read(buf)
            } catch (_: Exception) {
                if (cancel.get()) break
                -1
            }
            if (n > 0) {
                val (written, stillTrack) = forwardSegment(
                    output, segment, buf, n, timeline, trackTimeline,
                )
                trackTimeline = stillTrack
                if (written > 0) {
                    stats?.bytesOut?.addAndGet(written.toLong())
                    output.flush()
                }
                continue
            }

            // Upstream EOF / CDN cut.
            if (!renew || cancel.get()) {
                runCatching { segment.body.close() }
                break
            }
            switchSegment("eof_renew")
        }
    } finally {
        prefetch?.cancel()
        runCatching { segment.body.close() }
        stats?.runningMs?.set(System.currentTimeMillis() - wallStart)
        val event = stats?.lastEvent.orEmpty()
        if (event == "idle" || event.startsWith("prefetch")) {
            stats?.lastEvent = if (cancel.get()) "cancelled" else "stopped"
        }
    }
}

private class PrefetchHandle private constructor(
    private val latch: CountDownLatch,
    private val result: AtomicReference<Any>,
) {
    fun await(): InputStream {
        latch.await()
        val value = result.get()
        if (value is Exception) throw value
        @Suppress("UNCHECKED_CAST")
        return value as InputStream
    }

    fun cancel() {
        val value = result.get()
        if (value is InputStream) runCatching { value.close() }
    }

    companion object {
        fun start(open: UpstreamOpener): PrefetchHandle {
            val latch = CountDownLatch(1)
            val result = AtomicReference<Any>()
            val handle = PrefetchHandle(latch, result)
            Thread({
                try {
                    result.set(open.open())
                } catch (e: Exception) {
                    result.set(e)
                } finally {
                    latch.countDown()
                }
            }, "flv-prefetch").apply {
                isDaemon = true
                start()
            }
            return handle
        }
    }
}

private class FlvSegment(
    val body: InputStream,
    val continuation: Boolean,
) {
    val startedAt: Long = System.currentTimeMillis()
    val demux = FlvByteDemuxer()
    var waitingKeyframe: Boolean = continuation
}

private fun forwardSegment(
    output: OutputStream,
    segment: FlvSegment,
    chunk: ByteArray,
    length: Int,
    timeline: FlvTimeline,
    trackTimeline: Boolean,
): Pair<Int, Boolean> {
    var tracking = trackTimeline
    if (!segment.continuation) {
        output.write(chunk, 0, length)
        if (!tracking) return length to false
        segment.demux.push(chunk, 0, length)
        segment.demux.skipHeader()
        while (true) {
            val tag = segment.demux.nextTag() ?: break
            timeline.observe(tag)
        }
        if (segment.demux.pending() > FlvRelayDefaults.MAX_PENDING) {
            tracking = false
            segment.demux.reset()
        }
        return length to tracking
    }

    segment.demux.push(chunk, 0, length)
    segment.demux.skipHeader()
    val out = ByteArrayOutputStream()
    while (true) {
        val tag = segment.demux.nextTag() ?: break
        if (segment.waitingKeyframe) {
            if (tag.typ == FLV_TAG_SCRIPT || tag.sequenceHeader) continue
            if (!(tag.typ == FLV_TAG_VIDEO && tag.keyframe && timeline.isAhead(tag))) continue
            segment.waitingKeyframe = false
        } else if (tag.typ != FLV_TAG_SCRIPT && !timeline.isAhead(tag)) {
            continue
        }
        timeline.observe(tag)
        out.write(tag.raw)
    }
    if (segment.demux.pending() > FlvRelayDefaults.MAX_PENDING) {
        segment.demux.reset()
    }
    val bytes = out.toByteArray()
    if (bytes.isNotEmpty()) {
        output.write(bytes)
        return bytes.size to tracking
    }
    return 0 to tracking
}
