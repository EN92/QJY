package com.example.inclinometer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class InclinometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var pitchAngle: Float = 0f
        set(value) {
            field = value.coerceIn(-90f, 90f)
            invalidate()
        }

    var rollAngle: Float = 0f
        set(value) {
            field = value.coerceIn(-90f, 90f)
            invalidate()
        }

    var isAtLevel: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) animateLevelPulse()
                invalidate()
            }
        }

    var isAtEdge: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var isHeld: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var isNightMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updatePaintsForNightMode()
                invalidate()
            }
        }

    private var levelPulseRadius = 0f
    private val maxPulseRadiusRatio = 0.38f
    private val minPulseRadiusRatio = 0.32f
    private var pulseAnimator: ValueAnimator? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val levelRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pitchRollLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val bubbleRadiusRatio = 0.10f
    private val maxBubbleOffsetRatio = 0.34f
    private val density = resources.displayMetrics.density

    private val dayGradientColors = intArrayOf(
        Color.parseColor("#2C3E50"), Color.parseColor("#1A1A2E"), Color.parseColor("#0D0D1A"))
    private val nightGradientColors = intArrayOf(
        Color.parseColor("#141E28"), Color.parseColor("#0D0D16"), Color.parseColor("#06060C"))
    private val gradientStops = floatArrayOf(0f, 0.65f, 1f)

    companion object {
        private const val COLOR_RING = 0xFF4FC3F7.toInt()
        private const val COLOR_GREEN_BASE = 0xFF00E676.toInt()
        private const val COLOR_RED = 0xFFFF5252.toInt()
        private const val COLOR_PINK = 0xFFE91E63.toInt()
    }

    private fun nightDim(base: Int, factor: Float = 0.4f): Int {
        if (!isNightMode) return base
        return Color.rgb(
            (Color.red(base) * factor).toInt(),
            (Color.green(base) * factor).toInt(),
            (Color.blue(base) * factor).toInt()
        )
    }

    init {
        holdPaint.style = Paint.Style.STROKE
        holdPaint.strokeWidth = 2f
        holdPaint.color = COLOR_PINK
        updatePaintsForNightMode()
    }

    private fun updatePaintsForNightMode() {
        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeWidth = 3f
        ringPaint.color = nightDim(COLOR_RING)

        crossPaint.style = Paint.Style.STROKE
        crossPaint.strokeWidth = 1f
        crossPaint.color = if (isNightMode) 0x14FFFFFF.toInt() else 0x28FFFFFF.toInt()

        bubblePaint.style = Paint.Style.FILL
        bubblePaint.color = nightDim(COLOR_GREEN_BASE)

        bubbleHighlightPaint.style = Paint.Style.FILL
        bubbleHighlightPaint.color = if (isNightMode) 0x40FFFFFF.toInt() else 0x80FFFFFF.toInt()

        tickPaint.style = Paint.Style.STROKE
        tickPaint.strokeWidth = 1f
        tickPaint.color = if (isNightMode) 0x22DDDDDD.toInt() else 0x4DDDDDDD.toInt()

        majorTickPaint.style = Paint.Style.STROKE
        majorTickPaint.strokeWidth = 2.5f
        majorTickPaint.color = if (isNightMode) 0x44DDDDDD.toInt() else 0x88DDDDDD.toInt()

        levelRingPaint.style = Paint.Style.STROKE
        levelRingPaint.strokeWidth = 2.5f
        levelRingPaint.color = nightDim(0xFF4CAF50.toInt())
        levelRingPaint.alpha = 80

        pitchRollLinePaint.style = Paint.Style.STROKE
        pitchRollLinePaint.strokeWidth = 2f
        pitchRollLinePaint.color = if (isNightMode) 0x20FFFFFF.toInt() else 0x40FFFFFF.toInt()

        trackPaint.style = Paint.Style.STROKE
        trackPaint.strokeWidth = 1.5f
        trackPaint.color = if (isNightMode) 0x10FFFFFF.toInt() else 0x20FFFFFF.toInt()

        textPaint.color = if (isNightMode) 0x44FFFFFF.toInt() else 0x88FFFFFF.toInt()
        textPaint.textSize = 11f * density
        textPaint.textAlign = Paint.Align.CENTER

        centerPaint.style = Paint.Style.FILL
        centerPaint.color = nightDim(COLOR_RED)

        holdPaint.alpha = if (isNightMode) 90 else 180
    }

    private fun animateLevelPulse() {
        pulseAnimator?.cancel()
        val minPulse = minPulseRadiusRatio
        val maxPulse = maxPulseRadiusRatio
        pulseAnimator = ValueAnimator.ofFloat(minPulse, maxPulse).apply {
            duration = 600
            repeatCount = 2
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                levelPulseRadius = animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) * 0.90f
        val bubbleRadius = radius * bubbleRadiusRatio
        val innerRadius = radius * 0.78f

        val maxOffset = radius * maxBubbleOffsetRatio
        val bubbleX = cx + (rollAngle / 90f) * maxOffset
        val bubbleY = cy - (pitchAngle / 90f) * maxOffset
        val tilt = sqrt(pitchAngle * pitchAngle + rollAngle * rollAngle)

        // 背景圆
        bgPaint.shader = RadialGradient(
            cx, cy, radius,
            if (isNightMode) nightGradientColors else dayGradientColors,
            gradientStops, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // 刻度同心圆
        for (i in 1..4) {
            val r = radius * 0.82f + (radius - radius * 0.82f) * (i / 4f)
            ringPaint.strokeWidth = if (i == 4) 3f else 0.8f
            ringPaint.alpha = if (isNightMode) (20 + i * 8) else (35 + i * 15)
            canvas.drawCircle(cx, cy, r, ringPaint)
        }

        // 气泡移动轨道圈
        val trackR = radius * maxBubbleOffsetRatio
        trackPaint.alpha = if (isNightMode) 25 else 50
        canvas.drawCircle(cx, cy, trackR, trackPaint)

        drawTicks(canvas, cx, cy, radius)

        // 十字参考线
        crossPaint.alpha = if (isNightMode) 30 else 60
        canvas.drawLine(cx - innerRadius, cy, cx + innerRadius, cy, crossPaint)
        canvas.drawLine(cx, cy - innerRadius, cx, cy + innerRadius, crossPaint)

        // 倾斜方向线
        if (tilt > 0.5f) {
            pitchRollLinePaint.alpha = (if (isNightMode) 40 else 80 + (tilt / 90f) * 100).toInt().coerceIn(0, 180)
            canvas.drawLine(cx, cy, bubbleX, bubbleY, pitchRollLinePaint)
        }

        // 水平状态圈
        val pulseR = if (levelPulseRadius > 0f) levelPulseRadius else minPulseRadiusRatio
        if (isAtLevel || levelPulseRadius > 0f) {
            levelRingPaint.alpha = if (isAtLevel) (if (isNightMode) 100 else 200)
                else (200f * (1f - (maxPulseRadiusRatio - levelPulseRadius) / (maxPulseRadiusRatio - minPulseRadiusRatio))).toInt()
            canvas.drawCircle(cx, cy, radius * pulseR, levelRingPaint)
        } else if (tilt < 5f) {
            levelRingPaint.alpha = ((if (isNightMode) 90 else 180) * (1f - tilt / 5f)).toInt()
            canvas.drawCircle(cx, cy, radius * 0.32f, levelRingPaint)
        }

        // 外圈
        ringPaint.strokeWidth = 4f
        ringPaint.alpha = 255
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // 气泡阴影
        bgPaint.shader = null
        bgPaint.color = if (isNightMode) 0x18000000.toInt() else 0x28000000.toInt()
        canvas.drawCircle(bubbleX + 3f, bubbleY + 3f, bubbleRadius, bgPaint)

        // 气泡
        val edgeFactor = (tilt / 85f).coerceIn(0f, 1f)
        val isNearEdge = tilt > 70f
        drawBubble(canvas, bubbleX, bubbleY, bubbleRadius, isNearEdge, edgeFactor)

        // 中心点
        canvas.drawCircle(cx, cy, 3.5f, centerPaint)

        drawLabels(canvas, cx, cy, radius)
        drawDegreeLabels(canvas, cx, cy, radius)

        // Hold标记
        if (isHeld) {
            canvas.drawCircle(cx, cy, radius + 8f, holdPaint)
            textPaint.textSize = 10f * density
            textPaint.color = if (isNightMode) 0x80E91E63.toInt() else COLOR_PINK
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("HOLD", cx, cy - radius * 0.45f, textPaint)
        }
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val outerR = radius * 0.93f
        val normalInnerR = radius * 0.87f
        for (i in 0 until 72) {
            val angle = Math.toRadians((i * 5 - 90).toDouble())
            val isMajor = i % 9 == 0
            val sr = if (isMajor) radius * 0.83f else normalInnerR

            if (isMajor) {
                majorTickPaint.alpha = if (isNightMode) 90 else 180
                val x1 = cx + (cos(angle) * outerR).toFloat()
                val y1 = cy + (sin(angle) * outerR).toFloat()
                val x2 = cx + (cos(angle) * sr).toFloat()
                val y2 = cy + (sin(angle) * sr).toFloat()
                canvas.drawLine(x1, y1, x2, y2, majorTickPaint)
            } else {
                tickPaint.alpha = if (isNightMode) 22 else 45
                val x1 = cx + (cos(angle) * outerR).toFloat()
                val y1 = cy + (sin(angle) * outerR).toFloat()
                val x2 = cx + (cos(angle) * normalInnerR).toFloat()
                val y2 = cy + (sin(angle) * normalInnerR).toFloat()
                canvas.drawLine(x1, y1, x2, y2, tickPaint)
            }
        }
    }

    private fun drawDegreeLabels(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        textPaint.textSize = 10f * density
        textPaint.color = if (isNightMode) 0x33FFFFFF.toInt() else 0x66FFFFFF.toInt()
        for (deg in 0..330 step 30) {
            val angle = Math.toRadians((deg - 90).toDouble())
            val lx = cx + (cos(angle) * radius * 0.64f).toFloat()
            val ly = cy + (sin(angle) * radius * 0.64f).toFloat()
            canvas.drawText("${deg}°", lx, ly + 4f, textPaint)
        }
    }

    private fun drawBubble(canvas: Canvas, x: Float, y: Float, radius: Float, isNearEdge: Boolean, edgeFactor: Float) {
        val c0 = if (isNearEdge) lerpColor(0xFFFFD54F.toInt(), 0xFFFF5252.toInt(), edgeFactor)
                 else if (isNightMode) 0xFF58CC5B.toInt() else 0xFFB2FF59.toInt()
        val c1 = if (isNearEdge) lerpColor(0xFFFF9800.toInt(), 0xFFD32F2F.toInt(), edgeFactor)
                 else if (isNightMode) 0xFF00733A.toInt() else 0xFF00E676.toInt()
        val c2 = if (isNearEdge) lerpColor(0xFFE65100.toInt(), 0xFFB71C1C.toInt(), edgeFactor)
                 else if (isNightMode) 0xFF004D12.toInt() else 0xFF009624.toInt()

        val gradient = RadialGradient(
            x - radius * 0.3f, y - radius * 0.3f, radius,
            intArrayOf(c0, c1, c2),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        bubblePaint.shader = gradient
        canvas.drawCircle(x, y, radius, bubblePaint)
        canvas.drawCircle(x - radius * 0.25f, y - radius * 0.25f, radius * 0.35f, bubbleHighlightPaint)
        bubblePaint.shader = null
    }

    private fun lerpColor(c1: Int, c2: Int, factor: Float): Int {
        val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun drawLabels(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val labels = arrayOf("前", "右", "后", "左")
        val angles = floatArrayOf(270f, 0f, 90f, 180f)
        textPaint.textSize = 13f * density
        textPaint.color = if (isNightMode) 0x66607078.toInt() else 0xCCB0BEC5.toInt()

        for (i in labels.indices) {
            val a = Math.toRadians(angles[i].toDouble())
            val lx = cx + (cos(a) * radius * 0.48f).toFloat()
            val ly = cy + (sin(a) * radius * 0.48f).toFloat()
            canvas.drawText(labels[i], lx, ly + 5f, textPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
    }
}
