package com.takeabreak.controller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared visual primitives for Take a Break's Liquid Glass × Windows Vista Aero aesthetic:
 * - Pure white middle canvas with minimal sky-blue hue at the very top and minimal green hue at the very bottom.
 * - Crystal-clear refractive Liquid Glass pills with curved specular highlights, optical lens edges,
 *   luminous bottom caustic bounce, and translucent glass typography.
 */
object AeroGlassUi {

    enum class GlassTone(
        val bodyTop: Int,
        val bodyMid: Int,
        val bodyBottom: Int,
        val outerContourTop: Int,
        val outerContourBottom: Int,
        val ambientShadow: Int,
        val contactShadow: Int,
        val textTopColor: Int,
        val textBottomColor: Int,
        val glossAlpha: Int,
    ) {
        /**
         * Pure crystal Liquid Glass — clear, luminous, and refractive, letting the screen's
         * white center (or faint green edge at the very bottom) shine naturally through.
         */
        LIQUID_CLEAR(
            bodyTop = 0xCCFFFFFF.toInt(),
            bodyMid = 0xB8F9FCFE.toInt(),
            bodyBottom = 0xC8F0F6FA.toInt(),
            outerContourTop = 0x4C8AA9BD,
            outerContourBottom = 0x5C6F93AA,
            ambientShadow = 0x1D113148,
            contactShadow = 0x14113148,
            textTopColor = 0xFF0E4563.toInt(),
            textBottomColor = 0xFF1A6078.toInt(),
            glossAlpha = 235,
        ),

        /**
         * Ultra-airy clear Liquid Glass for utility/lower controls, subtly catching the
         * delicate green edge glow near the bottom of the screen.
         */
        LIQUID_FROST(
            bodyTop = 0xC4FFFFFF.toInt(),
            bodyMid = 0xAEF7FBFC.toInt(),
            bodyBottom = 0xC2EEF6F4.toInt(),
            outerContourTop = 0x4688A6B5,
            outerContourBottom = 0x546E9B8C,
            ambientShadow = 0x19123344,
            contactShadow = 0x12123344,
            textTopColor = 0xFF1A4B61.toInt(),
            textBottomColor = 0xFF216255.toInt(),
            glossAlpha = 230,
        ),
    }

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics,
        ).toInt()

    fun dpF(context: Context, value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics,
        )

    /**
     * Tall, sleek modern condensed font using Android's system condensed grotesque family.
     */
    fun tallFont(weight: Int = 500): Typeface {
        val base = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight, false)
        } else {
            Typeface.create(
                "sans-serif-condensed",
                if (weight >= 600) Typeface.BOLD else Typeface.NORMAL,
            )
        }
    }

    /**
     * Applies a subtle Liquid Glass vertical sheen shader to a [TextView] (used on the prominent
     * heading and clear glass button labels so text itself looks sculpted in glass).
     */
    fun applyGlassTextGradient(
        textView: TextView,
        topColor: Int,
        midColor: Int,
        bottomColor: Int,
    ) {
        textView.setTextColor(topColor)
        textView.addOnLayoutChangeListener { view, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val h = (bottom - top).toFloat()
            val oldH = (oldBottom - oldTop).toFloat()
            if ((h > 0f) && ((h != oldH) || (textView.paint.shader == null))) {
                textView.paint.shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    h,
                    intArrayOf(topColor, midColor, bottomColor),
                    floatArrayOf(0f, 0.48f, 1f),
                    Shader.TileMode.CLAMP,
                )
                view.invalidate()
            }
        }
    }

    /**
     * Creates a clear Liquid Glass × Vista Aero pill button with high optical clarity,
     * specular refractive rim, subtle glass-gradient typography, and spring-like press feedback.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun createGlassButton(
        context: Context,
        label: String,
        tone: GlassTone = GlassTone.LIQUID_CLEAR,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 18f
        typeface = tallFont(weight = 600)
        letterSpacing = 0.02f
        setShadowLayer(2f, 0f, 1.2f, 0xE0FFFFFF.toInt())
        applyGlassTextGradient(
            this,
            topColor = tone.textTopColor,
            midColor = blend(tone.textTopColor, tone.textBottomColor, 0.35f),
            bottomColor = tone.textBottomColor,
        )
        gravity = Gravity.CENTER
        stateListAnimator = null
        elevation = 0f
        background = LiquidGlassPillDrawable(context, tone)
        minHeight = dp(context, 56f)
        minimumHeight = dp(context, 56f)
        setPadding(dp(context, 24f), dp(12f, context), dp(context, 24f), dp(14f, context))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(context, 5f)
            bottomMargin = dp(context, 5f)
        }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(85).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
            }
            false
        }
        setOnClickListener { onClick() }
    }

    private fun dp(value: Float, context: Context): Int = dp(context, value)

    /** Creates a pure crystal-clear Liquid Glass status card (no heavy hues in the middle). */
    fun createGlassCard(context: Context): LinearLayout = LinearLayout(context).apply {
        background = AeroGlassCardDrawable(
            context = context,
            tintTop = 0xE6FFFFFF.toInt(),
            tintMid = 0xD8FBFDFE.toInt(),
            tintBottom = 0xE0F2F7FA.toInt(),
            rimColor = 0xFFFFFFFF.toInt(),
            strokeTint = 0x527EA0B5,
            shadowColor = 0x1A123247,
        )
        setPadding(dp(context, 18f), dp(context, 14f), dp(context, 18f), dp(context, 16f))
    }

    /** Creates a compact clear frosted glass notice banner. */
    fun createGlassNotice(
        context: Context,
        message: String,
        isWarning: Boolean,
    ): TextView = TextView(context).apply {
        text = message
        textSize = 14f
        typeface = tallFont(weight = 400)
        letterSpacing = 0.01f
        gravity = Gravity.CENTER
        setLineSpacing(dpF(context, 2f), 1f)
        setTextColor(if (isWarning) 0xFF7A4400.toInt() else 0xFF1F4C63.toInt())
        background = if (isWarning) {
            AeroGlassCardDrawable(
                context = context,
                tintTop = 0xF2FFFDF6.toInt(),
                tintMid = 0xEDFFF9EA.toInt(),
                tintBottom = 0xECFFF3D8.toInt(),
                rimColor = 0xFFFFFBF2.toInt(),
                strokeTint = 0x66DDA03B,
                shadowColor = 0x1AC48216,
                cornerDp = 18f,
            )
        } else {
            AeroGlassCardDrawable(
                context = context,
                tintTop = 0xE8FFFFFF.toInt(),
                tintMid = 0xDAFBFDFE.toInt(),
                tintBottom = 0xE0F3F8FB.toInt(),
                rimColor = 0xFFFFFFFF.toInt(),
                strokeTint = 0x4E7E9FB4,
                shadowColor = 0x16123247,
                cornerDp = 18f,
            )
        }
        setPadding(dp(context, 16f), dp(context, 11f), dp(context, 16f), dp(context, 12f))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(context, 4f)
            bottomMargin = dp(context, 6f)
        }
    }

    private fun blend(c1: Int, c2: Int, t: Float): Int {
        val inv = 1f - t
        return Color.argb(
            ((Color.alpha(c1) * inv) + (Color.alpha(c2) * t)).toInt().coerceIn(0, 255),
            ((Color.red(c1) * inv) + (Color.red(c2) * t)).toInt().coerceIn(0, 255),
            ((Color.green(c1) * inv) + (Color.green(c2) * t)).toInt().coerceIn(0, 255),
            ((Color.blue(c1) * inv) + (Color.blue(c2) * t)).toInt().coerceIn(0, 255),
        )
    }

    /**
     * Pure white center canvas with ONLY a minimal sky-blue wash at the very top edge
     * (above the heading) and a minimal nature-green wash at the very bottom edge.
     */
    class MainMenuBackgroundDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if ((w <= 0f) || (h <= 0f)) return

            paint.shader = LinearGradient(
                0f,
                0f,
                0f,
                h,
                intArrayOf(
                    0xFFD7EEFF.toInt(), // Minimal sky-blue hue at very top edge
                    0xFFEDF7FF.toInt(), // Softly fading before heading
                    0xFFFFFFFF.toInt(), // Pure crisp white — NO hues through the entire middle
                    0xFFFFFFFF.toInt(), // Pure crisp white through the lower-middle buttons
                    0xFFEDF9F2.toInt(), // Softly emerging near the bottom edge
                    0xFFD6F3E2.toInt(), // Minimal nature-green hue at very bottom edge
                ),
                floatArrayOf(0f, 0.055f, 0.115f, 0.875f, 0.945f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(b, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    /**
     * High-clarity Liquid Glass × Windows Vista Aero pill surface:
     * layered ambient & contact glass shadows → crystal-clear translucent droplet body →
     * subtle optical edge thickness bevel → curved Vista upper specular glass reflection →
     * luminous bottom internal light refraction → bright specular inner rim + sleek outer contour.
     */
    private class LiquidGlassPillDrawable(
        context: Context,
        private val tone: GlassTone,
    ) : Drawable() {
        private val radius = dpF(context, 26f)
        private val insetX = dpF(context, 3.5f)
        private val insetTop = dpF(context, 2f)
        private val insetBottom = dpF(context, 6f)
        private val strokeW = dpF(context, 1.25f)

        private val ambientShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val contactShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val depthPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val causticPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val topHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val outerContourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        private val bodyRect = RectF()
        private val shadowRect = RectF()
        private val glossRect = RectF()
        private val bodyPath = Path()
        private val glossPath = Path()

        private var pressed = false

        override fun isStateful(): Boolean = true

        override fun onStateChange(state: IntArray): Boolean {
            val nowPressed = state.contains(android.R.attr.state_pressed)
            if (nowPressed != pressed) {
                pressed = nowPressed
                invalidateSelf()
                return true
            }
            return false
        }

        override fun draw(canvas: Canvas) {
            val b: Rect = bounds
            if ((b.width() <= 0) || (b.height() <= 0)) return

            val pressOffset = if (pressed) insetTop * 0.7f else 0f
            bodyRect.set(
                b.left + insetX,
                b.top + insetTop + pressOffset,
                b.right - insetX,
                (b.bottom - insetBottom) + pressOffset,
            )
            if ((bodyRect.width() <= 0f) || (bodyRect.height() <= 0f)) return

            // 1. Two-stage Liquid Glass elevation shadow (broad airy ambient + tight crisp contact)
            val ambDrop = if (pressed) insetBottom * 0.30f else insetBottom * 0.78f
            shadowRect.set(
                bodyRect.left + (insetX * 0.6f),
                bodyRect.top + ambDrop,
                bodyRect.right - (insetX * 0.6f),
                bodyRect.bottom + ambDrop,
            )
            ambientShadowPaint.color = tone.ambientShadow
            canvas.drawRoundRect(shadowRect, radius, radius, ambientShadowPaint)

            val contactDrop = if (pressed) insetBottom * 0.14f else insetBottom * 0.34f
            shadowRect.set(
                bodyRect.left + (insetX * 0.25f),
                bodyRect.top + contactDrop,
                bodyRect.right - (insetX * 0.25f),
                bodyRect.bottom + contactDrop,
            )
            contactShadowPaint.color = tone.contactShadow
            canvas.drawRoundRect(shadowRect, radius, radius, contactShadowPaint)

            // 2. Crystal-clear translucent Liquid Glass fill
            val topC = if (pressed) tintDown(tone.bodyTop, 0.95f) else tone.bodyTop
            val midC = if (pressed) tintDown(tone.bodyMid, 0.94f) else tone.bodyMid
            val botC = if (pressed) tintDown(tone.bodyBottom, 0.94f) else tone.bodyBottom
            bodyPaint.shader = LinearGradient(
                0f,
                bodyRect.top,
                0f,
                bodyRect.bottom,
                intArrayOf(topC, midC, botC),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP,
            )
            bodyPath.reset()
            bodyPath.addRoundRect(bodyRect, radius, radius, Path.Direction.CW)
            canvas.drawPath(bodyPath, bodyPaint)

            canvas.save()
            canvas.clipPath(bodyPath)

            // 3. Subtle optical glass edge refraction along lower curve (gives physical glass thickness)
            depthPaint.shader = LinearGradient(
                0f,
                bodyRect.top + (bodyRect.height() * 0.56f),
                0f,
                bodyRect.bottom,
                intArrayOf(0x001B3C52, 0x0D1B3C52, 0x1C1B3C52),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(bodyRect, depthPaint)

            // 4. Curved Vista × Liquid Glass upper specular reflection band
            val glossHeight = bodyRect.height() * 0.50f
            glossRect.set(
                bodyRect.left + (strokeW * 1.4f),
                bodyRect.top + (strokeW * 0.6f),
                bodyRect.right - (strokeW * 1.4f),
                bodyRect.top + glossHeight,
            )
            glossPath.reset()
            glossPath.moveTo(glossRect.left, glossRect.top)
            glossPath.lineTo(glossRect.right, glossRect.top)
            glossPath.lineTo(glossRect.right, glossRect.bottom - (glossHeight * 0.18f))
            glossPath.quadTo(
                glossRect.centerX(),
                glossRect.bottom + (glossHeight * 0.18f),
                glossRect.left,
                glossRect.bottom - (glossHeight * 0.18f),
            )
            glossPath.close()

            val topGloss = Color.argb(
                if (pressed) (tone.glossAlpha * 0.70f).toInt() else tone.glossAlpha,
                255,
                255,
                255,
            )
            val botGloss = Color.argb(if (pressed) 26 else 64, 255, 255, 255)
            glossPaint.shader = LinearGradient(
                0f,
                glossRect.top,
                0f,
                glossRect.bottom,
                topGloss,
                botGloss,
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(glossPath, glossPaint)

            // 5. Luminous pure-crystal bottom internal caustic light bounce (signature Liquid Glass)
            val bounceR = (bodyRect.width() * 0.52f).coerceAtLeast(1f)
            causticPaint.shader = RadialGradient(
                bodyRect.centerX(),
                bodyRect.bottom + (bodyRect.height() * 0.18f),
                bounceR,
                intArrayOf(0xD8FFFFFF.toInt(), 0x55FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(bodyRect, causticPaint)

            canvas.restore()

            // 6. Crisp Liquid Glass refractive inner white specular rim
            val rimInset = strokeW * 1.15f
            val rimRect = RectF(
                bodyRect.left + rimInset,
                bodyRect.top + rimInset,
                bodyRect.right - rimInset,
                bodyRect.bottom - rimInset,
            )
            innerRimPaint.strokeWidth = strokeW * 1.35f
            innerRimPaint.shader = LinearGradient(
                0f,
                rimRect.top,
                0f,
                rimRect.bottom,
                intArrayOf(0xFFFFFFFF.toInt(), 0x7AFFFFFF, 0xF0FFFFFF.toInt()),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(
                rimRect,
                (radius - rimInset).coerceAtLeast(1f),
                (radius - rimInset).coerceAtLeast(1f),
                innerRimPaint,
            )

            // 7. Ultra-crisp top-edge glass sparkle line
            topHighlightPaint.strokeWidth = strokeW * 0.7f
            topHighlightPaint.shader = LinearGradient(
                bodyRect.left + radius,
                bodyRect.top,
                bodyRect.right - radius,
                bodyRect.top,
                intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt(), 0x00FFFFFF),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawLine(
                bodyRect.left + (radius * 0.65f),
                bodyRect.top + strokeW,
                bodyRect.right - (radius * 0.65f),
                bodyRect.top + strokeW,
                topHighlightPaint,
            )

            // 8. Sleek refractive crystal outer contour
            outerContourPaint.strokeWidth = strokeW * 0.95f
            outerContourPaint.shader = LinearGradient(
                0f,
                bodyRect.top,
                0f,
                bodyRect.bottom,
                intArrayOf(tone.outerContourTop, 0x3A8AA6B8, tone.outerContourBottom),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(bodyRect, radius, radius, outerContourPaint)
        }

        private fun tintDown(color: Int, factor: Float): Int = Color.argb(
            Color.alpha(color),
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255),
        )

        override fun setAlpha(alpha: Int) {
            bodyPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bodyPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** Frosted crystal Liquid Glass card drawable with bright specular bevel and soft shadow. */
    private class AeroGlassCardDrawable(
        context: Context,
        private val tintTop: Int,
        private val tintMid: Int,
        private val tintBottom: Int,
        private val rimColor: Int,
        private val strokeTint: Int,
        private val shadowColor: Int,
        cornerDp: Float = 24f,
    ) : Drawable() {
        private val radius = dpF(context, cornerDp)
        private val insetX = dpF(context, 2.5f)
        private val insetTop = dpF(context, 1.5f)
        private val insetBottom = dpF(context, 4.5f)
        private val strokeW = dpF(context, 1.2f)

        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        private val rect = RectF()
        private val shadowRect = RectF()
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if ((b.width() <= 0) || (b.height() <= 0)) return

            rect.set(b.left + insetX, b.top + insetTop, b.right - insetX, b.bottom - insetBottom)
            shadowRect.set(
                rect.left + insetX,
                rect.top + (insetBottom * 0.72f),
                rect.right - insetX,
                rect.bottom + (insetBottom * 0.72f),
            )
            shadowPaint.color = shadowColor
            canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint)

            fillPaint.shader = LinearGradient(
                0f,
                rect.top,
                0f,
                rect.bottom,
                intArrayOf(tintTop, tintMid, tintBottom),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP,
            )
            path.reset()
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.drawPath(path, fillPaint)

            canvas.save()
            canvas.clipPath(path)
            sheenPaint.shader = LinearGradient(
                0f,
                rect.top,
                0f,
                rect.top + (rect.height() * 0.48f),
                0xD0FFFFFF.toInt(),
                0x00FFFFFF,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(
                rect.left,
                rect.top,
                rect.right,
                rect.top + (rect.height() * 0.48f),
                sheenPaint,
            )
            canvas.restore()

            rimPaint.strokeWidth = strokeW * 1.15f
            rimPaint.color = rimColor
            val innerRect = RectF(
                rect.left + strokeW,
                rect.top + strokeW,
                rect.right - strokeW,
                rect.bottom - strokeW,
            )
            canvas.drawRoundRect(
                innerRect,
                (radius - strokeW).coerceAtLeast(1f),
                (radius - strokeW).coerceAtLeast(1f),
                rimPaint,
            )

            borderPaint.strokeWidth = strokeW * 0.9f
            borderPaint.color = strokeTint
            canvas.drawRoundRect(rect, radius, radius, borderPaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
