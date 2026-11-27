package com.takeabreak.watcher

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Displays the Take a Break open-window icon ([R.drawable.ic_takeabreak_window]) with smooth,
 * animated sunlight rays, a soft glossy light sweep, and delicate shimmering motes masked
 * strictly to the blue sky and blue windowpane pixels of the image.
 */
class TakeABreakWindowView(context: Context) : View(context) {
    private var shimmerPhase = 0f
    private var shimmerAnimator: ValueAnimator? = null

    private val cached: CachedAssets? = getOrLoadAssets(resources)
    private val iconBitmap: Bitmap? get() = cached?.iconBitmap
    private val blueMaskBitmap: Bitmap? get() = cached?.blueMaskBitmap
    private val normalizedBlueBounds: RectF get() = cached?.blueBounds ?: DEFAULT_BLUE_BOUNDS

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rayPath = Path()

    private val dstRect = RectF()
    private val blueDstRect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = AeroGlassUi.dp(context, 252f)
        val desiredH = AeroGlassUi.dp(context, 204f)
        val w = resolveSize(desiredW, widthMeasureSpec)
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startShimmerIfVisible()
    }

    override fun onDetachedFromWindow() {
        stopShimmer()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            startShimmerIfVisible()
        } else {
            stopShimmer()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            startShimmerIfVisible()
        } else {
            stopShimmer()
        }
    }

    private fun startShimmerIfVisible() {
        if (!isAttachedToWindow || (visibility != VISIBLE) || (windowVisibility != VISIBLE)) return
        if (shimmerAnimator?.isRunning == true) return
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                shimmerPhase = it.animatedValue as Float
                invalidate()
            }
        }
        shimmerAnimator = anim
        anim.start()
    }

    private fun stopShimmer() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = iconBitmap ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if ((vw <= 0f) || (vh <= 0f)) return

        val scale = min(vw / bmp.width.toFloat(), vh / bmp.height.toFloat())
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        val opticalShiftX = -(drawW * 0.055f)
        val left = (((vw - drawW) * 0.5f) + opticalShiftX).coerceAtLeast(0f)
        val top = (vh - drawH) * 0.5f
        dstRect.set(left, top, left + drawW, top + drawH)

        canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)

        val mask = blueMaskBitmap ?: return
        blueDstRect.set(
            dstRect.left + (drawW * normalizedBlueBounds.left),
            dstRect.top + (drawH * normalizedBlueBounds.top),
            dstRect.left + (drawW * normalizedBlueBounds.right),
            dstRect.top + (drawH * normalizedBlueBounds.bottom),
        )
        if ((blueDstRect.width() <= 0f) || (blueDstRect.height() <= 0f)) return

        val layer = canvas.saveLayer(blueDstRect, null)
        drawAnimatedBlueShine(canvas, blueDstRect)
        canvas.drawBitmap(mask, null, dstRect, maskPaint)
        canvas.restoreToCount(layer)
    }

    private fun drawAnimatedBlueShine(canvas: Canvas, b: RectF) {
        val t = shimmerPhase * 2.0 * PI
        val bw = b.width()
        val bh = b.height()

        val bloomPulse = 0.72f + (0.28f * sin(t).toFloat())
        val bloomAlpha = (92f * bloomPulse).toInt().coerceIn(0, 255)
        shinePaint.shader = RadialGradient(
            b.left + (bw * 0.62f),
            b.top + (bh * 0.08f),
            bw * 0.72f,
            intArrayOf(
                Color.argb(bloomAlpha, 255, 254, 236),
                Color.argb((bloomAlpha * 0.40f).toInt(), 218, 250, 255),
                0x00FFFFFF,
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(b, shinePaint)

        data class RaySpec(
            val topU1: Float,
            val topU2: Float,
            val botU1: Float,
            val botU2: Float,
            val baseAlpha: Float,
            val phase: Double,
            val warm: Boolean,
        )

        val rays = listOf(
            RaySpec(0.32f, 0.46f, 0.50f, 0.70f, 0.34f, 0.0, warm = true),
            RaySpec(0.50f, 0.66f, 0.68f, 0.90f, 0.40f, 1.5, warm = false),
            RaySpec(0.70f, 0.84f, 0.86f, 1.06f, 0.30f, 2.9, warm = true),
            RaySpec(0.10f, 0.24f, 0.26f, 0.44f, 0.22f, 4.2, warm = false),
        )

        for (ray in rays) {
            val wave = 0.5f + (0.5f * sin(t + ray.phase).toFloat())
            val sway = sin(t + (ray.phase * 0.65)).toFloat() * (bw * 0.025f)
            val alpha = (255f * ray.baseAlpha * (0.45f + (0.55f * wave))).toInt().coerceIn(0, 255)

            val startColor = if (ray.warm) {
                Color.argb(alpha, 255, 253, 224)
            } else {
                Color.argb(alpha, 232, 252, 255)
            }
            val midColor = if (ray.warm) {
                Color.argb((alpha * 0.48f).toInt(), 255, 249, 204)
            } else {
                Color.argb((alpha * 0.44f).toInt(), 208, 246, 255)
            }
            val endColor = if (ray.warm) 0x00FFF9CC else 0x00D0F6FF

            val tx1 = b.left + (bw * ray.topU1)
            val tx2 = b.left + (bw * ray.topU2)
            val bx1 = b.left + (bw * ray.botU1) + sway
            val bx2 = b.left + (bw * ray.botU2) + sway

            shinePaint.shader = LinearGradient(
                (tx1 + tx2) * 0.5f,
                b.top,
                (bx1 + bx2) * 0.5f,
                b.bottom,
                intArrayOf(startColor, midColor, endColor),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP,
            )
            rayPath.reset()
            rayPath.moveTo(tx1, b.top)
            rayPath.lineTo(tx2, b.top)
            rayPath.lineTo(bx2, b.bottom)
            rayPath.lineTo(bx1, b.bottom)
            rayPath.close()
            canvas.drawPath(rayPath, shinePaint)
        }

        val sweepPos = -0.35f + (shimmerPhase * 1.70f)
        val sweepCenterX = b.left + (bw * sweepPos)
        val sweepHalfW = bw * 0.22f
        val sweepEnvelope = sin(shimmerPhase * PI).toFloat().coerceIn(0f, 1f)
        val sweepAlpha = (108f * sweepEnvelope).toInt().coerceIn(0, 255)
        if (sweepAlpha > 4) {
            shinePaint.shader = LinearGradient(
                sweepCenterX - sweepHalfW,
                b.top,
                sweepCenterX + sweepHalfW,
                b.bottom,
                intArrayOf(
                    0x00FFFFFF,
                    Color.argb((sweepAlpha * 0.45f).toInt(), 240, 253, 255),
                    Color.argb(sweepAlpha, 255, 255, 255),
                    Color.argb((sweepAlpha * 0.45f).toInt(), 240, 253, 255),
                    0x00FFFFFF,
                ),
                floatArrayOf(0f, 0.34f, 0.50f, 0.66f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(b, shinePaint)
        }

        val motes = arrayOf(
            floatArrayOf(0.58f, 0.24f, 0.022f, 0.3f),
            floatArrayOf(0.72f, 0.44f, 0.026f, 1.7f),
            floatArrayOf(0.62f, 0.66f, 0.022f, 3.0f),
            floatArrayOf(0.80f, 0.74f, 0.020f, 4.3f),
            floatArrayOf(0.32f, 0.56f, 0.018f, 5.2f),
        )
        for (m in motes) {
            val phase = t + m[3]
            val pulse = 0.5f + (0.5f * sin(phase).toFloat())
            val mx = b.left + (bw * m[0]) + (sin(phase * 0.5).toFloat() * bw * 0.018f)
            val my = b.top + (bh * m[1]) + (sin(phase + 1.1).toFloat() * bh * 0.015f)
            val r = (bw * m[2]) * (0.85f + (0.40f * pulse))
            val a = (175f * pulse).toInt().coerceIn(0, 255)
            shinePaint.shader = RadialGradient(
                mx,
                my,
                r * 2.2f,
                intArrayOf(
                    Color.argb(a, 255, 255, 240),
                    Color.argb((a * 0.35f).toInt(), 216, 250, 255),
                    0x00FFFFFF,
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(mx, my, r * 2.2f, shinePaint)
        }
        shinePaint.shader = null
    }

    private class CachedAssets(
        val iconBitmap: Bitmap,
        val blueMaskBitmap: Bitmap,
        val blueBounds: RectF,
    )

    companion object {
        private val DEFAULT_BLUE_BOUNDS = RectF(0.28f, 0.14f, 0.80f, 0.78f)
        @Volatile
        private var cachedAssets: CachedAssets? = null

        private fun getOrLoadAssets(resources: Resources): CachedAssets? {
            cachedAssets?.let { return it }
            synchronized(this) {
                cachedAssets?.let { return it }
                val opts = BitmapFactory.Options().apply { inScaled = false }
                val decoded = BitmapFactory.decodeResource(
                    resources,
                    R.drawable.ic_takeabreak_window,
                    opts,
                ) ?: return null
                val bounds = RectF(DEFAULT_BLUE_BOUNDS)
                val mask = buildBlueAreaMask(decoded, bounds)
                return CachedAssets(decoded, mask, bounds).also { cachedAssets = it }
            }
        }

        private fun buildBlueAreaMask(src: Bitmap, outBounds: RectF): Bitmap {
            val srcW = src.width
            val srcH = src.height
            val maskW = (srcW / 2).coerceAtLeast(1)
            val maskH = (srcH / 2).coerceAtLeast(1)

            val srcPixels = IntArray(srcW * srcH)
            src.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

            val maskPixels = IntArray(maskW * maskH)
            var minX = maskW
            var minY = maskH
            var maxX = 0
            var maxY = 0

            for (my in 0 until maskH) {
                val sy = (my * 2).coerceAtMost(srcH - 1)
                val srcRow = sy * srcW
                val dstRow = my * maskW
                for (mx in 0 until maskW) {
                    val sx = (mx * 2).coerceAtMost(srcW - 1)
                    val px = srcPixels[srcRow + sx]
                    val a = Color.alpha(px)
                    val r = Color.red(px)
                    val g = Color.green(px)
                    val b = Color.blue(px)

                    val blueDelta = b - r
                    val cyanDelta = g - r
                    if ((a > 200) && (b > 160) && (blueDelta > 24) && (cyanDelta > 10)) {
                        val strength = ((blueDelta - 24f) / 36f).coerceIn(0f, 1f)
                        val maskAlpha = (strength * 255f).toInt().coerceIn(0, 255)
                        maskPixels[dstRow + mx] = Color.argb(maskAlpha, 255, 255, 255)
                        if (maskAlpha > 40) {
                            if (mx < minX) minX = mx
                            if (my < minY) minY = my
                            if (mx > maxX) maxX = mx
                            if (my > maxY) maxY = my
                        }
                    }
                }
            }

            if ((maxX > minX) && (maxY > minY)) {
                outBounds.set(
                    (minX.toFloat() / maskW).coerceIn(0f, 1f),
                    (minY.toFloat() / maskH).coerceIn(0f, 1f),
                    ((maxX + 1).toFloat() / maskW).coerceIn(0f, 1f),
                    ((maxY + 1).toFloat() / maskH).coerceIn(0f, 1f),
                )
            }

            return Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888).apply {
                setPixels(maskPixels, 0, maskW, 0, 0, maskW, maskH)
            }
        }
    }
}
