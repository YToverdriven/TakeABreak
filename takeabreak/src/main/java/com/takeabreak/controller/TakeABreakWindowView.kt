package com.takeabreak.controller

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Animated sunlit open double-casement window graphic for the Take a Break home screen.
 *
 * Features symmetrical 4-pane glass casement sashes swung wide open on both sides (flush with
 * the frame with zero hinge gaps), a wide sunlit blue sky center with gently shimmering diagonal
 * rays, a crisp 3D windowsill, and solid opaque matching tied-back curtains on both sides.
 */
class TakeABreakWindowView(context: Context) : View(context) {
    private var shimmerPhase = 0f
    private var shimmerAnimator: ValueAnimator? = null

    private val outlineColor = 0xFF5A6470.toInt()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = outlineColor
    }
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val motePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val tempPath = Path()
    private val clipPath = Path()
    private val tempRect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = AeroGlassUi.dp(context, 286f)
        val desiredH = AeroGlassUi.dp(context, 208f)
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
            duration = 6400L
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
        val vw = width.toFloat()
        val vh = height.toFloat()
        if ((vw <= 0f) || (vh <= 0f)) return

        val scale = min(vw / VIRTUAL_W, vh / VIRTUAL_H)
        val dx = (vw - (VIRTUAL_W * scale)) * 0.5f
        val dy = (vh - (VIRTUAL_H * scale)) * 0.5f

        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)

        drawWallShadowUnderSill(canvas)
        drawOuterWindowFrame(canvas)
        drawSkyAndClouds(canvas)
        drawShimmeringSunRays(canvas)
        drawSymmetricalOpenSashes(canvas)
        drawWindowsill(canvas)
        drawSymmetricalCurtains(canvas)

        canvas.restore()
    }

    private fun drawWallShadowUnderSill(canvas: Canvas) {
        fillPaint.shader = LinearGradient(
            0f,
            274f,
            0f,
            312f,
            intArrayOf(0x265B6F84, 0x0E5B6F84, 0x005B6F84),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        tempPath.reset()
        tempPath.moveTo(56f, 276f)
        tempPath.lineTo(384f, 276f)
        tempPath.lineTo(370f, 308f)
        tempPath.lineTo(70f, 308f)
        tempPath.close()
        canvas.drawPath(tempPath, fillPaint)
        fillPaint.shader = null
    }

    private fun drawOuterWindowFrame(canvas: Canvas) {
        tempRect.set(62f, 24f, 378f, 250f)
        fillPaint.shader = LinearGradient(
            62f,
            24f,
            378f,
            250f,
            0xFFFFFFFF.toInt(),
            0xFFE9EFF4.toInt(),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(tempRect, 8f, 8f, fillPaint)
        fillPaint.shader = null

        strokePaint.strokeWidth = 3.2f
        strokePaint.color = outlineColor
        canvas.drawRoundRect(tempRect, 8f, 8f, strokePaint)

        // Recessed inner top lintel shadow strip
        fillPaint.color = 0xFFD9E1E9.toInt()
        canvas.drawRect(76f, 36f, 364f, 44f, fillPaint)
    }

    private fun drawSkyAndClouds(canvas: Canvas) {
        val skyRect = RectF(76f, 40f, 364f, 244f)
        canvas.save()
        clipPath.reset()
        clipPath.addRect(skyRect, Path.Direction.CW)
        canvas.clipPath(clipPath)

        // Vivid Frutiger Aero clear blue sky across the wide open window
        fillPaint.shader = LinearGradient(
            skyRect.left,
            skyRect.top,
            skyRect.right,
            skyRect.bottom,
            intArrayOf(
                0xFF0098F5.toInt(),
                0xFF0CB1FC.toInt(),
                0xFF2AC4FF.toInt(),
                0xFF68DAFF.toInt(),
            ),
            floatArrayOf(0f, 0.42f, 0.80f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(skyRect, fillPaint)

        // Gentle pulsing sunlight bloom centered in the upper open sky
        val bloomPulse = 0.85f + (0.15f * sin(shimmerPhase * 2.0 * PI).toFloat())
        val bloomAlpha = (96f * bloomPulse).toInt().coerceIn(0, 255)
        fillPaint.shader = RadialGradient(
            220f,
            46f,
            138f,
            intArrayOf(
                Color.argb(bloomAlpha, 255, 253, 232),
                Color.argb((bloomAlpha * 0.42f).toInt(), 210, 248, 255),
                0x00FFFFFF,
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(skyRect, fillPaint)
        fillPaint.shader = null

        // Soft cloud hints low along the open sky horizon
        drawCloudPuff(canvas, 252f, 234f, 42f, 0x82FFFFFF.toInt())
        drawCloudPuff(canvas, 214f, 240f, 36f, 0x76FFFFFF.toInt())

        canvas.restore()

        strokePaint.strokeWidth = 2.6f
        strokePaint.color = outlineColor
        canvas.drawRect(skyRect, strokePaint)
    }

    private fun drawCloudPuff(canvas: Canvas, cx: Float, cy: Float, radius: Float, centerColor: Int) {
        cloudPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(centerColor, 0x00FFFFFF),
            floatArrayOf(0.25f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, cloudPaint)
    }

    /**
     * Draws symmetrical 4-pane casement sashes swung wide open on both left and right,
     * anchored flush into the inner frame jambs with zero gap.
     */
    private fun drawSymmetricalOpenSashes(canvas: Canvas) {
        drawLeftCasementSash(canvas, phaseShift = 0f)

        // Exact horizontal mirror for the right casement sash across x = 220
        canvas.save()
        canvas.scale(-1f, 1f, VIRTUAL_W * 0.5f, 0f)
        drawLeftCasementSash(canvas, phaseShift = 1.6f)
        canvas.restore()
    }

    private fun drawLeftCasementSash(canvas: Canvas, phaseShift: Float) {
        // Hinged FLUSH on the left inner jamb (x = 76) — zero gap between jamb/curtain and sash!
        // Opened wide (projected width = 70 units) so a wide sunlit opening remains in the center.
        val tl = PointF(76f, 40f)
        val tr = PointF(146f, 58f)
        val br = PointF(146f, 226f)
        val bl = PointF(76f, 244f)

        // Subtle shadow behind the inward-swung outer edge of the sash
        fillPaint.color = 0x2208426E.toInt()
        tempPath.reset()
        tempPath.moveTo(tr.x + 6f, tr.y + 5f)
        tempPath.lineTo(tr.x + 13f, tr.y + 8f)
        tempPath.lineTo(br.x + 13f, br.y + 3f)
        tempPath.lineTo(br.x + 6f, br.y)
        tempPath.close()
        canvas.drawPath(tempPath, fillPaint)

        // 3D inner-facing thickness edge along the swung-open edge (x = 146..152)
        fillPaint.shader = LinearGradient(
            tr.x,
            tr.y,
            tr.x + 6f,
            br.y,
            0xFFEDF2F7.toInt(),
            0xFFD6E0EA.toInt(),
            Shader.TileMode.CLAMP,
        )
        tempPath.reset()
        tempPath.moveTo(tr.x, tr.y)
        tempPath.lineTo(tr.x + 6f, tr.y + 2f)
        tempPath.lineTo(br.x + 6f, br.y - 2f)
        tempPath.lineTo(br.x, br.y)
        tempPath.close()
        canvas.drawPath(tempPath, fillPaint)
        fillPaint.shader = null
        strokePaint.strokeWidth = 2.6f
        strokePaint.color = outlineColor
        canvas.drawPath(tempPath, strokePaint)

        // Solid opaque white casement sash body
        fillPaint.color = 0xFFFFFFFF.toInt()
        tempPath.reset()
        tempPath.moveTo(tl.x, tl.y)
        tempPath.lineTo(tr.x, tr.y)
        tempPath.lineTo(br.x, br.y)
        tempPath.lineTo(bl.x, bl.y)
        tempPath.close()
        canvas.drawPath(tempPath, fillPaint)
        strokePaint.strokeWidth = 3.0f
        strokePaint.color = outlineColor
        canvas.drawPath(tempPath, strokePaint)

        // 4 solid sky-blue glass panes with clean diagonal reflections
        val panes = arrayOf(
            floatArrayOf(0.14f, 0.44f, 0.09f, 0.45f), // top-left
            floatArrayOf(0.56f, 0.86f, 0.09f, 0.45f), // top-right
            floatArrayOf(0.14f, 0.44f, 0.55f, 0.91f), // bottom-left
            floatArrayOf(0.56f, 0.86f, 0.55f, 0.91f), // bottom-right
        )

        val glintShift = sin((shimmerPhase * 2.0 * PI) + phaseShift).toFloat() * 0.08f

        for ((index, p) in panes.withIndex()) {
            val pTL = bilerp(tl, tr, br, bl, p[0], p[2])
            val pTR = bilerp(tl, tr, br, bl, p[1], p[2])
            val pBR = bilerp(tl, tr, br, bl, p[1], p[3])
            val pBL = bilerp(tl, tr, br, bl, p[0], p[3])

            clipPath.reset()
            clipPath.moveTo(pTL.x, pTL.y)
            clipPath.lineTo(pTR.x, pTR.y)
            clipPath.lineTo(pBR.x, pBR.y)
            clipPath.lineTo(pBL.x, pBL.y)
            clipPath.close()

            canvas.save()
            canvas.clipPath(clipPath)

            // Solid vivid blue glass pane fill
            fillPaint.shader = LinearGradient(
                pTL.x,
                pTL.y,
                pBR.x,
                pBR.y,
                intArrayOf(0xFF0AAAF7.toInt(), 0xFF1BB8FF.toInt(), 0xFF47CEFF.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(clipPath, fillPaint)

            // Crisp diagonal specular sheen
            val stripeAlpha = (92 + (26 * sin((shimmerPhase * 2.0 * PI) + phaseShift + (index * 0.8))).toInt())
                .coerceIn(68, 126)
            val offset = (index * 0.05f) + glintShift
            fillPaint.shader = LinearGradient(
                pTL.x,
                pTL.y,
                pBR.x,
                pBR.y,
                intArrayOf(
                    0x00FFFFFF,
                    Color.argb(stripeAlpha, 255, 255, 255),
                    Color.argb((stripeAlpha * 0.82f).toInt(), 255, 255, 255),
                    0x00FFFFFF,
                ),
                floatArrayOf(
                    (0.24f + offset).coerceIn(0.02f, 0.80f),
                    (0.34f + offset).coerceIn(0.08f, 0.86f),
                    (0.46f + offset).coerceIn(0.14f, 0.92f),
                    (0.56f + offset).coerceIn(0.20f, 0.98f),
                ),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(clipPath, fillPaint)
            fillPaint.shader = null
            canvas.restore()

            strokePaint.strokeWidth = 2.3f
            strokePaint.color = outlineColor
            canvas.drawPath(clipPath, strokePaint)
        }
    }

    private fun drawWindowsill(canvas: Canvas) {
        // Solid sunlit white top face of the 3D windowsill
        fillPaint.shader = LinearGradient(
            0f,
            244f,
            0f,
            260f,
            0xFFFFFFFF.toInt(),
            0xFFF2F5F9.toInt(),
            Shader.TileMode.CLAMP,
        )
        tempPath.reset()
        tempPath.moveTo(52f, 244f)
        tempPath.lineTo(388f, 244f)
        tempPath.lineTo(398f, 260f)
        tempPath.lineTo(42f, 260f)
        tempPath.close()
        canvas.drawPath(tempPath, fillPaint)
        fillPaint.shader = null
        strokePaint.strokeWidth = 3.0f
        strokePaint.color = outlineColor
        canvas.drawPath(tempPath, strokePaint)

        // Front lip thickness of the 3D windowsill
        fillPaint.shader = LinearGradient(
            0f,
            260f,
            0f,
            274f,
            0xFFE4EBF2.toInt(),
            0xFFD1DBE5.toInt(),
            Shader.TileMode.CLAMP,
        )
        tempPath.reset()
        tempPath.moveTo(42f, 260f)
        tempPath.lineTo(398f, 260f)
        tempPath.lineTo(398f, 274f)
        tempPath.lineTo(42f, 274f)
        tempPath.close()
        canvas.drawPath(tempPath, fillPaint)
        fillPaint.shader = null
        strokePaint.strokeWidth = 3.0f
        strokePaint.color = outlineColor
        canvas.drawPath(tempPath, strokePaint)
    }

    /**
     * Draws solid, opaque, identical mirrored tied-back curtains on both the right and left sides,
     * hugging the outer sash hinges tightly with zero blue gaps.
     */
    private fun drawSymmetricalCurtains(canvas: Canvas) {
        drawRightTiedCurtain(canvas)

        canvas.save()
        canvas.scale(-1f, 1f, VIRTUAL_W * 0.5f, 0f)
        drawRightTiedCurtain(canvas)
        canvas.restore()
    }

    private fun drawRightTiedCurtain(canvas: Canvas) {
        // 1. Solid opaque lower flared tail below the tieback
        tempPath.reset()
        tempPath.moveTo(362f, 194f)
        tempPath.cubicTo(356f, 224f, 350f, 246f, 342f, 264f)
        tempPath.cubicTo(358f, 273f, 378f, 274f, 392f, 268f)
        tempPath.cubicTo(402f, 264f, 408f, 258f, 408f, 248f)
        tempPath.cubicTo(406f, 230f, 400f, 212f, 394f, 192f)
        tempPath.close()

        fillPaint.shader = LinearGradient(
            342f,
            194f,
            408f,
            270f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFECF1F6.toInt(), 0xFFD9E1EA.toInt()),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(tempPath, fillPaint)
        fillPaint.shader = null
        strokePaint.strokeWidth = 3.2f
        strokePaint.color = outlineColor
        canvas.drawPath(tempPath, strokePaint)

        // Lower tail folds
        strokePaint.strokeWidth = 2.1f
        strokePaint.color = 0xFF8C97A4.toInt()
        drawCurve(canvas, 372f, 198f, 366f, 234f, 360f, 266f)
        drawCurve(canvas, 384f, 196f, 385f, 232f, 384f, 266f)

        // 2. Solid opaque upper draped curtain panel (inner edge hugs x = 352..364 with zero gap)
        tempPath.reset()
        tempPath.moveTo(346f, 22f)
        tempPath.lineTo(392f, 22f)
        tempPath.cubicTo(404f, 22f, 410f, 32f, 412f, 52f)
        tempPath.cubicTo(415f, 102f, 410f, 150f, 396f, 184f)
        tempPath.lineTo(362f, 188f)
        tempPath.cubicTo(354f, 140f, 348f, 88f, 346f, 22f)
        tempPath.close()

        fillPaint.shader = LinearGradient(
            346f,
            22f,
            414f,
            188f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFF3F6FA.toInt(), 0xFFDFE6EE.toInt()),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(tempPath, fillPaint)
        fillPaint.shader = null
        strokePaint.strokeWidth = 3.2f
        strokePaint.color = outlineColor
        canvas.drawPath(tempPath, strokePaint)

        // Crisp fabric drape folds
        strokePaint.strokeWidth = 2.1f
        strokePaint.color = 0xFF8C97A4.toInt()
        drawCurve(canvas, 362f, 36f, 366f, 112f, 372f, 182f)
        drawCurve(canvas, 380f, 34f, 382f, 110f, 382f, 180f)
        drawCurve(canvas, 396f, 44f, 398f, 114f, 391f, 178f)

        // 3. Solid opaque rounded fabric tieback band
        tempPath.reset()
        tempPath.moveTo(360f, 184f)
        tempPath.quadTo(379f, 180f, 398f, 178f)
        tempPath.cubicTo(403f, 178f, 404f, 189f, 398f, 192f)
        tempPath.quadTo(379f, 196f, 361f, 197f)
        tempPath.cubicTo(356f, 197f, 355f, 185f, 360f, 184f)
        tempPath.close()

        fillPaint.shader = LinearGradient(
            357f,
            180f,
            402f,
            196f,
            0xFFFFFFFF.toInt(),
            0xFFD8E0E9.toInt(),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(tempPath, fillPaint)
        fillPaint.shader = null
        strokePaint.strokeWidth = 3.0f
        strokePaint.color = outlineColor
        canvas.drawPath(tempPath, strokePaint)
    }

    /**
     * Renders gently shimmering diagonal sunlight rays and delicate floating light motes
     * streaming down through the wide open center sky between both open casement sashes.
     */
    private fun drawShimmeringSunRays(canvas: Canvas) {
        val t = shimmerPhase * 2.0 * PI

        val openingClip = RectF(76f, 40f, 364f, 244f)
        canvas.save()
        clipPath.reset()
        clipPath.addRect(openingClip, Path.Direction.CW)
        canvas.clipPath(clipPath)

        data class RaySpec(
            val topX1: Float,
            val topX2: Float,
            val botX1: Float,
            val botX2: Float,
            val baseAlpha: Float,
            val phaseOffset: Double,
            val warmTint: Boolean,
        )

        val rays = listOf(
            RaySpec(156f, 184f, 192f, 232f, 0.36f, 0.0, warmTint = true),
            RaySpec(190f, 224f, 226f, 274f, 0.42f, 1.4, warmTint = false),
            RaySpec(228f, 258f, 266f, 310f, 0.34f, 2.7, warmTint = true),
            RaySpec(132f, 152f, 160f, 192f, 0.24f, 4.1, warmTint = false),
        )

        for (ray in rays) {
            val wave = 0.5f + (0.5f * sin(t + ray.phaseOffset).toFloat())
            val sway = sin(t + (ray.phaseOffset * 0.7)).toFloat() * 4.5f
            val alpha = (255f * ray.baseAlpha * (0.52f + (0.48f * wave))).toInt().coerceIn(0, 255)

            val startColor = if (ray.warmTint) {
                Color.argb(alpha, 255, 252, 216)
            } else {
                Color.argb(alpha, 230, 252, 255)
            }
            val midColor = if (ray.warmTint) {
                Color.argb((alpha * 0.50f).toInt(), 255, 248, 196)
            } else {
                Color.argb((alpha * 0.46f).toInt(), 200, 244, 255)
            }
            val endColor = if (ray.warmTint) 0x00FFF8C4 else 0x00C8F4FF

            rayPaint.shader = LinearGradient(
                (ray.topX1 + ray.topX2) * 0.5f,
                40f,
                ((ray.botX1 + ray.botX2) * 0.5f) + sway,
                244f,
                intArrayOf(startColor, midColor, endColor),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )

            tempPath.reset()
            tempPath.moveTo(ray.topX1, 40f)
            tempPath.lineTo(ray.topX2, 40f)
            tempPath.lineTo(ray.botX2 + sway, 244f)
            tempPath.lineTo(ray.botX1 + sway, 244f)
            tempPath.close()
            canvas.drawPath(tempPath, rayPaint)
        }
        rayPaint.shader = null

        // Subtle shimmering light motes floating gently inside the open central sky
        val motes = arrayOf(
            floatArrayOf(188f, 108f, 3.2f, 0.4f),
            floatArrayOf(224f, 142f, 3.6f, 1.8f),
            floatArrayOf(252f, 182f, 3.1f, 3.1f),
            floatArrayOf(202f, 196f, 3.1f, 4.4f),
            floatArrayOf(240f, 88f, 2.8f, 5.2f),
        )
        for (m in motes) {
            val phase = t + m[3]
            val pulse = 0.5f + (0.5f * sin(phase).toFloat())
            val mx = m[0] + (sin(phase * 0.5).toFloat() * 4f)
            val my = m[1] + (sin(phase + 1.1).toFloat() * 3.5f)
            val r = m[2] * (0.85f + (0.35f * pulse))
            val a = (165f * pulse).toInt().coerceIn(0, 255)
            motePaint.shader = RadialGradient(
                mx,
                my,
                r * 2.2f,
                intArrayOf(
                    Color.argb(a, 255, 253, 224),
                    Color.argb((a * 0.35f).toInt(), 210, 248, 255),
                    0x00FFFFFF,
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(mx, my, r * 2.2f, motePaint)
        }
        motePaint.shader = null
        canvas.restore()
    }

    private fun drawCurve(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        cx: Float,
        cy: Float,
        x2: Float,
        y2: Float,
    ) {
        tempPath.reset()
        tempPath.moveTo(x1, y1)
        tempPath.quadTo(cx, cy, x2, y2)
        canvas.drawPath(tempPath, strokePaint)
    }

    private fun bilerp(
        tl: PointF,
        tr: PointF,
        br: PointF,
        bl: PointF,
        u: Float,
        v: Float,
    ): PointF {
        val topX = tl.x + ((tr.x - tl.x) * u)
        val topY = tl.y + ((tr.y - tl.y) * u)
        val botX = bl.x + ((br.x - bl.x) * u)
        val botY = bl.y + ((br.y - bl.y) * u)
        return PointF(
            topX + ((botX - topX) * v),
            topY + ((botY - topY) * v),
        )
    }

    companion object {
        private const val VIRTUAL_W = 440f
        private const val VIRTUAL_H = 320f
    }
}
