package io.github.dovecoteescapee.byedpi.activities

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
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
    private var stateAnimator: ValueAnimator? = null
    private var activeAmount = 0f

    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        animateRunningState(if (value) 1f else 0f)
        if (value) startAnimationIfAllowed() else stopAnimation()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) startAnimationIfAllowed()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        stateAnimator?.cancel()
        stateAnimator = null
        activeAmount = if (running) 1f else 0f
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            activeAmount = if (running) 1f else 0f
            if (running) startAnimationIfAllowed()
        } else {
            stopAnimation()
            stateAnimator?.cancel()
            stateAnimator = null
            activeAmount = if (running) 1f else 0f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = min(width, height).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = size * 0.39f
        val angleOffset = progress * 360f - 22f

        drawTicks(canvas, centerX, centerY, radius)
        drawRoute(canvas, centerX, centerY, radius, angleOffset, activeAmount)
        drawCenterMark(canvas, centerX, centerY, size, activeAmount)
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        linePaint.shader = null
        linePaint.strokeWidth = dp(1f)

        repeat(48) { index ->
            val angle = Math.toRadians((index * 7.5f - 90f).toDouble())
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

    private fun drawRoute(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        offset: Float,
        activity: Float,
    ) {
        val ring = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        linePaint.strokeWidth = dp(2.2f)
        linePaint.color = quietStrong
        linePaint.alpha = (130f + 25f * activity).toInt()
        linePaint.shader = null
        canvas.drawArc(ring, -65f, 288f, false, linePaint)

        val accentRing = RectF(
            ring.left + dp(6f),
            ring.top + dp(6f),
            ring.right - dp(6f),
            ring.bottom - dp(6f),
        )
        linePaint.strokeWidth = dp(3f)
        linePaint.alpha = (100f + 155f * activity).toInt()
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

        if (activity > 0.02f) {
            val packetAlpha = (255f * activity).toInt()
            drawPacket(canvas, cx, cy, radius - dp(6f), offset - 72f, blue, 3.5f, packetAlpha)
            drawPacket(canvas, cx, cy, radius - dp(6f), offset + 100f, rose, 2.5f, packetAlpha)
            drawPacket(canvas, cx, cy, radius - dp(6f), offset + 237f, violetSoft, 3f, packetAlpha)
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
        alpha: Int,
    ) {
        val angle = Math.toRadians(angleDegrees.toDouble())
        fillPaint.color = color
        fillPaint.alpha = alpha
        canvas.drawCircle(
            cx + cos(angle).toFloat() * radius,
            cy + sin(angle).toFloat() * radius,
            dp(sizeDp),
            fillPaint,
        )
    }

    private fun drawCenterMark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        activity: Float,
    ) {
        linePaint.shader = null
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = dp(2.4f)
        linePaint.strokeCap = Paint.Cap.ROUND
        linePaint.color = ColorUtils.blendARGB(quietStrong, violetSoft, activity)
        linePaint.alpha = (220f + 35f * activity).toInt()

        val radius = size * 0.085f
        val arc = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arc, -42f, 264f, false, linePaint)
        canvas.drawLine(cx, cy - radius - dp(8f), cx, cy - dp(1f), linePaint)
        linePaint.style = Paint.Style.STROKE
    }

    private fun startAnimationIfAllowed() {
        if (!isAttachedToWindow || animator != null || !animationsEnabled()) return
        animator = ValueAnimator.ofFloat(progress, progress + 1f).apply {
            duration = 20_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = (it.animatedValue as Float) % 1f
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    private fun animateRunningState(target: Float) {
        stateAnimator?.cancel()
        stateAnimator = null

        if (!animationsEnabled() || activeAmount == target) {
            activeAmount = target
            invalidate()
            return
        }

        val transition = ValueAnimator.ofFloat(activeAmount, target).apply {
            duration = if (target > activeAmount) 420L else 300L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                activeAmount = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (stateAnimator === animation) stateAnimator = null
                }
            })
        }
        stateAnimator = transition
        transition.start()
    }

    private fun animationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
