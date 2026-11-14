package com.dtv.androidtv.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.dtv.androidtv.data.model.DanmakuMessage
import com.dtv.androidtv.data.model.DanmakuMode

/**
 * Plain Android Canvas danmaku layer for Media3 [androidx.media3.ui.PlayerView]
 * [androidx.media3.ui.PlayerView.getOverlayFrameLayout].
 *
 * Compose / nested ComposeView overlays have failed repeatedly on Sony Leanback
 * (SurfaceView punch-through, then TextureView + Compose sibling / nested
 * ComposeView still invisible). Drawing inside PlayerView's official overlay
 * FrameLayout with a normal View is the reliable path.
 */
class DanmakuCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), Choreographer.FrameCallback {

    private data class Bullet(
        val text: String,
        val mode: DanmakuMode,
        val track: Int,
        val bornMs: Long,
        val durationMs: Long,
        val color: Int,
        val textWidth: Float,
    )

    private val active = ArrayList<Bullet>(32)
    private val seen = LinkedHashSet<String>(256)
    private val trackFreeAt = LongArray(16)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(28f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.WHITE
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textPaint.textSize
        typeface = textPaint.typeface
        style = Paint.Style.STROKE
        strokeWidth = sp(2.5f) / 3f
        color = Color.BLACK
    }

    private var overlayEnabled = true
    private var choreographerRegistered = false
    private var lastMessages: List<DanmakuMessage> = emptyList()

    init {
        setWillNotDraw(false)
        // Transparent so TextureView video shows through; we only paint glyphs.
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun setOverlayEnabled(enabled: Boolean) {
        if (overlayEnabled == enabled) return
        overlayEnabled = enabled
        if (!enabled) {
            active.clear()
            seen.clear()
            trackFreeAt.fill(0L)
            invalidate()
        } else {
            // Re-admit from last buffer after toggle-on.
            submit(lastMessages)
        }
    }

    /** Push the ViewModel ring buffer; admission is incremental via [seen]. */
    fun submit(messages: List<DanmakuMessage>) {
        lastMessages = messages
        if (!overlayEnabled) return
        if (messages.isEmpty()) {
            seen.clear()
            active.clear()
            trackFreeAt.fill(0L)
            invalidate()
            return
        }
        if (seen.size > 500) {
            val drop = seen.toList().take(seen.size - 400)
            drop.forEach { seen.remove(it) }
        }
        val now = System.currentTimeMillis()
        val admitted = DanmakuAdmission.admit(
            messages = messages,
            seen = seen,
            activeCount = active.size,
            maxActive = DanmakuTvDefaults.MAX_ACTIVE,
            trackFreeAt = trackFreeAt,
            nowMs = now,
        )
        for (c in admitted) {
            val text = c.message.content
            if (text.isBlank()) continue
            val color = parseColor(c.message.color)
            active += Bullet(
                text = text,
                mode = c.mode,
                track = c.track,
                bornMs = now,
                durationMs = c.durationMs,
                color = color,
                textWidth = textPaint.measureText(text),
            )
        }
        ensureChoreographer()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureChoreographer()
    }

    override fun onDetachedFromWindow() {
        if (choreographerRegistered) {
            Choreographer.getInstance().removeFrameCallback(this)
            choreographerRegistered = false
        }
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        choreographerRegistered = false
        if (!overlayEnabled || !isAttachedToWindow) return
        val now = System.currentTimeMillis()
        val before = active.size
        active.removeAll { now - it.bornMs >= it.durationMs }
        if (active.isNotEmpty() || before != active.size) {
            invalidate()
        }
        if (active.isNotEmpty()) {
            ensureChoreographer()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!overlayEnabled || active.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val areaH = h * DanmakuTvDefaults.AREA_FRACTION
        val trackH = textPaint.textSize + dp(6f)
        val trackCount = (areaH / trackH).toInt().coerceIn(4, trackFreeAt.size)
        val now = System.currentTimeMillis()
        val fontMetrics = textPaint.fontMetrics
        val baselineOffset = -fontMetrics.ascent

        for (b in active) {
            textPaint.color = withAlpha(b.color, DanmakuTvDefaults.OPACITY)
            strokePaint.textSize = textPaint.textSize
            when (b.mode) {
                DanmakuMode.SCROLL -> {
                    val progress = ((now - b.bornMs).toFloat() / b.durationMs).coerceIn(0f, 1f)
                    val travel = w + b.textWidth + dp(48f)
                    val x = w - travel * progress
                    val track = b.track.coerceAtMost(trackCount - 1)
                    val y = trackH * track + dp(8f) + baselineOffset
                    canvas.drawText(b.text, x, y, strokePaint)
                    canvas.drawText(b.text, x, y, textPaint)
                }
                DanmakuMode.TOP -> {
                    val x = (w - b.textWidth) / 2f
                    val y = dp(12f) + baselineOffset
                    canvas.drawText(b.text, x, y, strokePaint)
                    canvas.drawText(b.text, x, y, textPaint)
                }
                DanmakuMode.BOTTOM -> {
                    val x = (w - b.textWidth) / 2f
                    val y = h - dp(72f) + fontMetrics.descent
                    canvas.drawText(b.text, x, y, strokePaint)
                    canvas.drawText(b.text, x, y, textPaint)
                }
            }
        }
    }

    private fun ensureChoreographer() {
        if (choreographerRegistered || !isAttachedToWindow) return
        Choreographer.getInstance().postFrameCallback(this)
        choreographerRegistered = true
    }

    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun parseColor(raw: String): Int {
        val v = raw.trim()
        if (!v.startsWith("#") || (v.length != 7 && v.length != 4)) {
            return Color.WHITE
        }
        return try {
            Color.parseColor(v)
        } catch (_: Exception) {
            Color.WHITE
        }
    }

    private fun withAlpha(color: Int, alphaFrac: Float): Int {
        val a = (alphaFrac.coerceIn(0f, 1f) * 255f).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }
}
