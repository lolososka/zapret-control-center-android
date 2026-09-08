package io.github.dovecoteescapee.byedpi.activities

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import io.github.dovecoteescapee.byedpi.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A compact network instrument used on the main screen.
 *
 * The dial is intentionally the only ambient motion in the interface. It animates only while
 * the service is running and follows the system-wide animation preference on Android 8+.
 */
class RouteDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val quiet = ContextCompat.getColor(context, R.color.dial_quiet)
    private val quietStrong = ContextCompat.getColor(context, R.color.dial_quiet_strong)
    private val violet = ContextCompat.getColor(context, R.color.zapret_violet)
    private val violetSoft = ContextCompat.getColor(context, R.color.zapret_violet_soft)
    private val blue = ContextCompat.getColor(context, R.color.zapret_blue)
    private val rose = ContextCompat.getColor(context, R.color.zapret_rose)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var progress = 0f
    private var running = false
    private var animator: ValueAnimator? = null

    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        if (value) startAnimationIfAllowed() else stopAnimation()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) startAnimationIfAllowed()
    }

    override fun onDetachedFromWindow() {
        stopAnimation(reset = false)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = min(width, height).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = size * 0.39f
        val angleOffset = if (running) progress * 360f else -22f

        drawTicks(canvas, centerX, centerY, radius, angleOffset)
        drawRoute(canvas, centerX, centerY, radius, angleOffset)
        drawCenterMark(canvas, centerX, centerY, size)
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, radius: Float, offset: Float) {
        linePaint.shader = null
        linePaint.strokeWidth = dp(1f)

        repeat(48) { index ->
            val angle = Math.toRadians((index * 7.5f + offset * 0.08f - 90f).toDouble())
            val major = index % 4 == 0
            val startRadius = radius + if (major) dp(9f) else dp(12f)
            val endRadius = radius + dp(17f)
            linePaint.color = if (major) quietStrong else quiet
            linePaint.alpha = if (major) 190 else 105
            canvas.drawLine(
                cx + cos(angle).toFloat() * startRadius,
                cy + sin(angle).toFloat() * startRadius,
                cx + cos(angle).toFloat() * endRadius,
                cy + sin(angle).toFloat() * endRadius,
                linePaint,
            )
        }
    }

    private fun drawRoute(canvas: Canvas, cx: Float, cy: Float, radius: Float, offset: Float) {
        val ring = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        linePaint.strokeWidth = dp(2.2f)
        linePaint.color = quietStrong
        linePaint.alpha = 145
        linePaint.shader = null
        canvas.drawArc(ring, -65f, 288f, false, linePaint)

        val accentRing = RectF(
            ring.left + dp(6f),
            ring.top + dp(6f),
            ring.right - dp(6f),
            ring.bottom - dp(6f),
        )
        linePaint.strokeWidth = dp(3f)
        linePaint.alpha = 255
        linePaint.shader = SweepGradient(
            cx,
            cy,
            intArrayOf(violet, blue, violetSoft, rose, violet),
            floatArrayOf(0f, 0.24f, 0.5f, 0.76f, 1f),
        )

        canvas.save()
        canvas.rotate(offset, cx, cy)
        canvas.drawArc(accentRing, -72f, 86f, false, linePaint)
        canvas.drawArc(accentRing, 46f, 54f, false, linePaint)
        canvas.drawArc(accentRing, 133f, 104f, false, linePaint)
        canvas.restore()
        linePaint.shader = null

        if (running) {
            drawPacket(canvas, cx, cy, radius - dp(6f), offset - 72f, blue, 3.5f)
            drawPacket(canvas, cx, cy, radius - dp(6f), offset + 100f, rose, 2.5f)
            drawPacket(canvas, cx, cy, radius - dp(6f), offset + 237f, violetSoft, 3f)
        }
    }

    private fun drawPacket(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        angleDegrees: Float,
        color: Int,
        sizeDp: Float,
    ) {
        val angle = Math.toRadians(angleDegrees.toDouble())
        fillPaint.color = color
        fillPaint.alpha = 255
        canvas.drawCircle(
            cx + cos(angle).toFloat() * radius,
            cy + sin(angle).toFloat() * radius,
            dp(sizeDp),
            fillPaint,
        )
    }

    private fun drawCenterMark(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        linePaint.shader = null
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = dp(2.4f)
        linePaint.strokeCap = Paint.Cap.ROUND
        linePaint.color = if (running) violetSoft else quietStrong
        linePaint.alpha = if (running) 255 else 220

        val radius = size * 0.085f
        val arc = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arc, -42f, 264f, false, linePaint)
        canvas.drawLine(cx, cy - radius - dp(8f), cx, cy - dp(1f), linePaint)
        linePaint.style = Paint.Style.STROKE
    }

    private fun startAnimationIfAllowed() {
        if (!isAttachedToWindow || animator != null || !animationsEnabled()) return
        animator = ValueAnimator.ofFloat(progress, progress + 1f).apply {
            duration = 8_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = (it.animatedValue as Float) % 1f
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation(reset: Boolean = true) {
        animator?.cancel()
        animator = null
        if (reset) progress = 0f
    }

    private fun animationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
