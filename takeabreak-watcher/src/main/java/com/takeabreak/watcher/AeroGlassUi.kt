package com.takeabreak.watcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.min

/**
 * Shared visual primitives for Take a Break Watcher's Liquid Glass × Windows Vista Aero aesthetic,
 * matching the main Take a Break app seamlessly across every screen and pop-up.
 */
object AeroGlassUi {

    const val TEXT_BLACK = 0xFF000000.toInt()
    const val TEXT_SECONDARY = 0xFF475866.toInt()
    const val TEXT_HINT = 0xFF7B8E9C.toInt()
    const val DIVIDER_GLASS = 0x2E7C9FB5

    enum class GlassTone(
        val bodyTop: Int,
        val bodyMid: Int,
        val bodyBottom: Int,
        val outerContourTop: Int,
        val outerContourBottom: Int,
        val ambientShadow: Int,
        val contactShadow: Int,
        val glossAlpha: Int,
    ) {
        LIQUID_CLEAR(
            bodyTop = 0xCCFFFFFF.toInt(),
            bodyMid = 0xB8F9FCFE.toInt(),
            bodyBottom = 0xC8F0F6FA.toInt(),
            outerContourTop = 0x4C8AA9BD,
            outerContourBottom = 0x5C6F93AA,
            ambientShadow = 0x1D113148,
            contactShadow = 0x14113148,
            glossAlpha = 235,
        ),
        LIQUID_FROST(
            bodyTop = 0xC4FFFFFF.toInt(),
            bodyMid = 0xAEF7FBFC.toInt(),
            bodyBottom = 0xC2EEF6F4.toInt(),
            outerContourTop = 0x4688A6B5,
            outerContourBottom = 0x546E9B8C,
            ambientShadow = 0x19123344,
            contactShadow = 0x12123344,
            glossAlpha = 230,
        ),
        LIQUID_SKY(
            bodyTop = 0xDCEFF8FF.toInt(),
            bodyMid = 0xCCE1F3FF.toInt(),
            bodyBottom = 0xD8D4ECFC.toInt(),
            outerContourTop = 0x665D9BC2,
            outerContourBottom = 0x7A4785AD,
            ambientShadow = 0x1F123A54,
            contactShadow = 0x16123A54,
            glossAlpha = 240,
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

    fun minimalistFont(light: Boolean = false): Typeface {
        val weight = if (light) 300 else 400
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.SANS_SERIF, weight, false)
        } else {
            Typeface.create(
                if (light) "sans-serif-light" else "sans-serif",
                Typeface.NORMAL,
            )
        }
    }

    fun configureSystemBars(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        @Suppress("DEPRECATION")
        activity.window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    fun wrapScrollableScreen(
        activity: Activity,
        content: View,
        padHDp: Float = 24f,
        padTopDp: Float = 20f,
        padBotDp: Float = 20f,
        maxContentWidthDp: Float = 460f,
    ): ScrollView {
        configureSystemBars(activity)
        val basePadH = dp(activity, padHDp)
        val basePadTop = dp(activity, padTopDp)
        val basePadBot = dp(activity, padBotDp)
        val maxContentW = dp(activity, maxContentWidthDp)

        content.setPadding(basePadH, basePadTop, basePadH, basePadBot)

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = MainMenuBackgroundDrawable()
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            val availableW = activity.resources.displayMetrics.widthPixels - bars.left - bars.right
            val extraSide = ((availableW - maxContentW) / 2).coerceAtLeast(0)
            content.setPadding(
                basePadH + bars.left + extraSide,
                basePadTop + bars.top,
                basePadH + bars.right + extraSide,
                basePadBot + bars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
        return scroll
    }

    private fun buttonTextColors(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(),
        ),
        intArrayOf(
            0x7A243642,
            TEXT_BLACK,
        ),
    )

    fun createTitle(
        context: Context,
        text: String,
        sizeSp: Float = 28f,
        centered: Boolean = false,
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        typeface = minimalistFont(light = true)
        letterSpacing = 0.02f
        setTextColor(TEXT_BLACK)
        gravity = if (centered) Gravity.CENTER else Gravity.START
        setPadding(0, dp(context, 6f), 0, dp(context, 8f))
    }

    fun createSectionTitle(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 16.5f
        typeface = minimalistFont(light = false)
        letterSpacing = 0.015f
        setTextColor(TEXT_BLACK)
        setPadding(0, dp(context, 4f), 0, dp(context, 6f))
    }

    fun createBodyText(
        context: Context,
        text: String,
        centered: Boolean = false,
        secondary: Boolean = false,
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = 14.5f
        typeface = minimalistFont(light = false)
        letterSpacing = 0.01f
        setLineSpacing(dpF(context, 2.5f), 1f)
        setTextColor(if (secondary) TEXT_SECONDARY else TEXT_BLACK)
        gravity = if (centered) Gravity.CENTER else Gravity.START
        setPadding(0, 0, 0, dp(context, 10f))
    }

    fun createCaptionText(
        context: Context,
        text: String,
        centered: Boolean = false,
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        typeface = minimalistFont(light = false)
        letterSpacing = 0.01f
        setLineSpacing(dpF(context, 2f), 1f)
        setTextColor(TEXT_SECONDARY)
        gravity = if (centered) Gravity.CENTER else Gravity.START
    }

    @SuppressLint("ClickableViewAccessibility")
    fun createGlassButton(
        context: Context,
        label: String,
        tone: GlassTone = GlassTone.LIQUID_CLEAR,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 16.5f
        typeface = minimalistFont(light = false)
        letterSpacing = 0.015f
        setTextColor(buttonTextColors())
        gravity = Gravity.CENTER
        stateListAnimator = null
        elevation = 0f
        background = LiquidGlassPillDrawable(context, tone)
        minHeight = dp(context, 56f)
        minimumHeight = dp(context, 56f)
        setPadding(dp(context, 24f), dp(context, 12f), dp(context, 24f), dp(context, 14f))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(context, 5f)
            bottomMargin = dp(context, 5f)
        }
        attachPressFeedback(this)
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun createCompactGlassButton(
        context: Context,
        label: String,
        tone: GlassTone = GlassTone.LIQUID_FROST,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14.5f
        typeface = minimalistFont(light = false)
        letterSpacing = 0.015f
        setTextColor(buttonTextColors())
        gravity = Gravity.CENTER
        stateListAnimator = null
        elevation = 0f
        background = LiquidGlassPillDrawable(context, tone, cornerDp = 21f)
        minHeight = dp(context, 44f)
        minimumHeight = dp(context, 44f)
        minWidth = dp(context, 72f)
        minimumWidth = dp(context, 72f)
        setPadding(dp(context, 18f), dp(context, 8f), dp(context, 18f), dp(context, 10f))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        attachPressFeedback(this)
        setOnClickListener { onClick() }
    }

    fun createTopBarBackButton(
        context: Context,
        label: String = "Back",
        onClick: () -> Unit,
    ): Button = createCompactGlassButton(
        context = context,
        label = if (label == "Back") "\u2190  Back" else label,
        tone = GlassTone.LIQUID_FROST,
        onClick = onClick,
    ).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.START
            bottomMargin = dp(context, 8f)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachPressFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            if (!v.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(85).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
            }
            false
        }
    }

    fun createGlassCard(
        context: Context,
        cornerDp: Float = 24f,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = AeroGlassCardDrawable(
            context = context,
            tintTop = 0xE6FFFFFF.toInt(),
            tintMid = 0xD8FBFDFE.toInt(),
            tintBottom = 0xE0F2F7FA.toInt(),
            rimColor = 0xFFFFFFFF.toInt(),
            strokeTint = 0x527EA0B5,
            shadowColor = 0x1A123247,
            cornerDp = cornerDp,
        )
        setPadding(dp(context, 18f), dp(context, 14f), dp(context, 18f), dp(context, 16f))
    }

    fun createGlassNotice(
        context: Context,
        message: String,
        isWarning: Boolean,
    ): TextView = TextView(context).apply {
        text = message
        textSize = 13.5f
        typeface = minimalistFont(light = false)
        letterSpacing = 0.01f
        gravity = Gravity.CENTER
        setLineSpacing(dpF(context, 2f), 1f)
        setTextColor(TEXT_BLACK)
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

    fun createGlassInput(
        context: Context,
        hintText: String,
        inputTypeFlags: Int,
    ): EditText = EditText(context).apply {
        hint = hintText
        inputType = inputTypeFlags
        textSize = 15.5f
        typeface = minimalistFont(light = false)
        letterSpacing = 0.01f
        setTextColor(TEXT_BLACK)
        setHintTextColor(TEXT_HINT)
        background = LiquidGlassInputDrawable(context)
        minHeight = dp(context, 50f)
        minimumHeight = dp(context, 50f)
        setPadding(dp(context, 16f), dp(context, 12f), dp(context, 16f), dp(context, 12f))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(context, 4f)
            bottomMargin = dp(context, 8f)
        }
    }

    fun createGlassDialog(
        context: Context,
        title: String? = null,
        message: String? = null,
        customView: View? = null,
        positiveText: String? = null,
        onPositive: ((AlertDialog) -> Unit)? = null,
        negativeText: String? = null,
        onNegative: ((AlertDialog) -> Unit)? = null,
        neutralText: String? = null,
        onNeutral: ((AlertDialog) -> Unit)? = null,
        cancelable: Boolean = true,
        maxWidthDp: Float = 400f,
    ): AlertDialog {
        lateinit var dialog: AlertDialog

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AeroGlassCardDrawable(
                context = context,
                tintTop = 0xF7FFFFFF.toInt(),
                tintMid = 0xF4F9FCFE.toInt(),
                tintBottom = 0xF5EFF7FB.toInt(),
                rimColor = 0xFFFFFFFF.toInt(),
                strokeTint = 0x627BA0B8,
                shadowColor = 0x26102F45,
                cornerDp = 28f,
            )
            setPadding(dp(context, 22f), dp(context, 20f), dp(context, 22f), dp(context, 20f))
        }

        if (!title.isNullOrBlank()) {
            card.addView(
                TextView(context).apply {
                    text = title
                    textSize = 21f
                    typeface = minimalistFont(light = true)
                    letterSpacing = 0.02f
                    setTextColor(TEXT_BLACK)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(context, 2f), 0, dp(context, 10f))
                },
            )
        }

        val bodyColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        if (!message.isNullOrBlank()) {
            bodyColumn.addView(
                TextView(context).apply {
                    text = message
                    textSize = 14.5f
                    typeface = minimalistFont(light = false)
                    letterSpacing = 0.01f
                    setLineSpacing(dpF(context, 2.5f), 1f)
                    setTextColor(TEXT_BLACK)
                    gravity = Gravity.START
                    setPadding(dp(context, 2f), 0, dp(context, 2f), dp(context, 12f))
                },
            )
        }

        if (customView != null) {
            bodyColumn.addView(customView)
        }

        val maxBodyH = (context.resources.displayMetrics.heightPixels * 0.62f).toInt()
            .coerceAtLeast(dp(context, 180f))
        val bodyScroll = MaxHeightScrollView(context, maxBodyH).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                bodyColumn,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        card.addView(bodyScroll)

        val hasAnyButton = (positiveText != null) || (negativeText != null) || (neutralText != null)
        if (hasAnyButton) {
            val buttonRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(context, 10f)
                }
            }

            fun addActionBtn(label: String, tone: GlassTone, action: () -> Unit) {
                val btn = createCompactGlassButton(context, label, tone, action).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply {
                        val gap = dp(context, 4f)
                        setMargins(gap, 0, gap, 0)
                    }
                    setPadding(dp(context, 10f), dp(context, 9f), dp(context, 10f), dp(context, 11f))
                }
                buttonRow.addView(btn)
            }

            if (neutralText != null) {
                addActionBtn(neutralText, GlassTone.LIQUID_FROST) {
                    if (onNeutral != null) onNeutral(dialog) else dialog.dismiss()
                }
            }
            if (negativeText != null) {
                addActionBtn(negativeText, GlassTone.LIQUID_FROST) {
                    if (onNegative != null) onNegative(dialog) else dialog.dismiss()
                }
            }
            if (positiveText != null) {
                addActionBtn(positiveText, GlassTone.LIQUID_SKY) {
                    if (onPositive != null) onPositive(dialog) else dialog.dismiss()
                }
            }

            card.addView(buttonRow)
        }

        val wrapper = FrameLayout(context).apply {
            setPadding(dp(context, 6f), dp(context, 6f), dp(context, 6f), dp(context, 6f))
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        dialog = AlertDialog.Builder(context)
            .setView(wrapper)
            .setCancelable(cancelable)
            .create()
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setOnShowListener {
            dialog.window?.let { win ->
                win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                win.decorView.setPadding(0, 0, 0, 0)
                win.setDimAmount(0.30f)
                val dm = context.resources.displayMetrics
                val maxW = dp(context, maxWidthDp)
                val margin = dp(context, 28f)
                val targetW = min(dm.widthPixels - margin, maxW).coerceAtLeast(dp(context, 260f))
                win.setLayout(targetW, WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
        return dialog
    }

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
                    0xFFD7EEFF.toInt(),
                    0xFFEDF7FF.toInt(),
                    0xFFFFFFFF.toInt(),
                    0xFFFFFFFF.toInt(),
                    0xFFEDF9F2.toInt(),
                    0xFFD6F3E2.toInt(),
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

    private class LiquidGlassPillDrawable(
        context: Context,
        private val tone: GlassTone,
        cornerDp: Float = 26f,
    ) : Drawable() {
        private val radius = dpF(context, cornerDp)
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
        private var enabled = true

        override fun isStateful(): Boolean = true

        override fun onStateChange(state: IntArray): Boolean {
            val nowPressed = state.contains(android.R.attr.state_pressed)
            val nowEnabled = state.contains(android.R.attr.state_enabled)
            if ((nowPressed != pressed) || (nowEnabled != enabled)) {
                pressed = nowPressed
                enabled = nowEnabled
                invalidateSelf()
                return true
            }
            return false
        }

        override fun draw(canvas: Canvas) {
            val b: Rect = bounds
            if ((b.width() <= 0) || (b.height() <= 0)) return

            val effPressed = pressed && enabled
            val pressOffset = if (effPressed) insetTop * 0.7f else 0f
            bodyRect.set(
                b.left + insetX,
                b.top + insetTop + pressOffset,
                b.right - insetX,
                (b.bottom - insetBottom) + pressOffset,
            )
            if ((bodyRect.width() <= 0f) || (bodyRect.height() <= 0f)) return

            val r = min(radius, bodyRect.height() * 0.5f)
            val shadowScale = if (enabled) 1f else 0.40f

            val ambDrop = if (effPressed) insetBottom * 0.30f else insetBottom * 0.78f
            shadowRect.set(
                bodyRect.left + (insetX * 0.6f),
                bodyRect.top + ambDrop,
                bodyRect.right - (insetX * 0.6f),
                bodyRect.bottom + ambDrop,
            )
            ambientShadowPaint.color = scaleAlpha(tone.ambientShadow, shadowScale)
            canvas.drawRoundRect(shadowRect, r, r, ambientShadowPaint)

            val contactDrop = if (effPressed) insetBottom * 0.14f else insetBottom * 0.34f
            shadowRect.set(
                bodyRect.left + (insetX * 0.25f),
                bodyRect.top + contactDrop,
                bodyRect.right - (insetX * 0.25f),
                bodyRect.bottom + contactDrop,
            )
            contactShadowPaint.color = scaleAlpha(tone.contactShadow, shadowScale)
            canvas.drawRoundRect(shadowRect, r, r, contactShadowPaint)

            val topC = when {
                !enabled -> 0xA8F5F8FA.toInt()
                effPressed -> tintDown(tone.bodyTop, 0.95f)
                else -> tone.bodyTop
            }
            val midC = when {
                !enabled -> 0x9AF1F5F8.toInt()
                effPressed -> tintDown(tone.bodyMid, 0.94f)
                else -> tone.bodyMid
            }
            val botC = when {
                !enabled -> 0xA4EBF1F5.toInt()
                effPressed -> tintDown(tone.bodyBottom, 0.94f)
                else -> tone.bodyBottom
            }
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
            bodyPath.addRoundRect(bodyRect, r, r, Path.Direction.CW)
            canvas.drawPath(bodyPath, bodyPaint)

            canvas.save()
            canvas.clipPath(bodyPath)

            if (enabled) {
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
            }

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

            val glossMult = when {
                !enabled -> 0.55f
                effPressed -> 0.70f
                else -> 1f
            }
            val topGloss = Color.argb((tone.glossAlpha * glossMult).toInt(), 255, 255, 255)
            val botGloss = Color.argb(if (!enabled) 18 else if (effPressed) 26 else 64, 255, 255, 255)
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

            if (enabled) {
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
            }

            canvas.restore()

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
                if (enabled) {
                    intArrayOf(0xFFFFFFFF.toInt(), 0x7AFFFFFF, 0xF0FFFFFF.toInt())
                } else {
                    intArrayOf(0xB8FFFFFF.toInt(), 0x48FFFFFF, 0x90FFFFFF.toInt())
                },
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(
                rimRect,
                (r - rimInset).coerceAtLeast(1f),
                (r - rimInset).coerceAtLeast(1f),
                innerRimPaint,
            )

            if (enabled) {
                topHighlightPaint.strokeWidth = strokeW * 0.7f
                topHighlightPaint.shader = LinearGradient(
                    bodyRect.left + r,
                    bodyRect.top,
                    bodyRect.right - r,
                    bodyRect.top,
                    intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt(), 0x00FFFFFF),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawLine(
                    bodyRect.left + (r * 0.65f),
                    bodyRect.top + strokeW,
                    bodyRect.right - (r * 0.65f),
                    bodyRect.top + strokeW,
                    topHighlightPaint,
                )
            }

            outerContourPaint.strokeWidth = strokeW * 0.95f
            val contourScale = if (enabled) 1f else 0.55f
            outerContourPaint.shader = LinearGradient(
                0f,
                bodyRect.top,
                0f,
                bodyRect.bottom,
                intArrayOf(
                    scaleAlpha(tone.outerContourTop, contourScale),
                    scaleAlpha(0x3A8AA6B8, contourScale),
                    scaleAlpha(tone.outerContourBottom, contourScale),
                ),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(bodyRect, r, r, outerContourPaint)
        }

        private fun tintDown(color: Int, factor: Float): Int = Color.argb(
            Color.alpha(color),
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255),
        )

        private fun scaleAlpha(color: Int, factor: Float): Int = Color.argb(
            (Color.alpha(color) * factor).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
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

    private class LiquidGlassInputDrawable(context: Context) : Drawable() {
        private val radius = dpF(context, 16f)
        private val inset = dpF(context, 1.5f)
        private val strokeW = dpF(context, 1.2f)

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()

        private var focused = false

        override fun isStateful(): Boolean = true

        override fun onStateChange(state: IntArray): Boolean {
            val nowFocused = state.contains(android.R.attr.state_focused)
            if (nowFocused != focused) {
                focused = nowFocused
                invalidateSelf()
                return true
            }
            return false
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if ((b.width() <= 0) || (b.height() <= 0)) return
            rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)

            fillPaint.shader = LinearGradient(
                0f,
                rect.top,
                0f,
                rect.bottom,
                intArrayOf(0xF2F7FBFD.toInt(), 0xFAFFFFFF.toInt()),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, fillPaint)

            rimPaint.strokeWidth = strokeW
            rimPaint.color = 0xD8FFFFFF.toInt()
            val inner = RectF(
                rect.left + strokeW,
                rect.top + strokeW,
                rect.right - strokeW,
                rect.bottom - strokeW,
            )
            canvas.drawRoundRect(inner, (radius - strokeW).coerceAtLeast(1f), (radius - strokeW).coerceAtLeast(1f), rimPaint)

            borderPaint.strokeWidth = if (focused) strokeW * 1.4f else strokeW * 0.95f
            borderPaint.color = if (focused) 0xBB3E8EBA.toInt() else 0x587B9DB2
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
            if ((rect.width() <= 0f) || (rect.height() <= 0f)) return
            val r = min(radius, min(rect.width(), rect.height()) * 0.5f)

            if (Color.alpha(shadowColor) > 0) {
                shadowRect.set(
                    rect.left + insetX,
                    rect.top + (insetBottom * 0.72f),
                    rect.right - insetX,
                    rect.bottom + (insetBottom * 0.72f),
                )
                shadowPaint.color = shadowColor
                canvas.drawRoundRect(shadowRect, r, r, shadowPaint)
            }

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
            path.addRoundRect(rect, r, r, Path.Direction.CW)
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
                (r - strokeW).coerceAtLeast(1f),
                (r - strokeW).coerceAtLeast(1f),
                rimPaint,
            )

            borderPaint.strokeWidth = strokeW * 0.9f
            borderPaint.color = strokeTint
            canvas.drawRoundRect(rect, r, r, borderPaint)
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

    private class MaxHeightScrollView(
        context: Context,
        private val maxHeightPx: Int,
    ) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val finalHeightSpec = if ((mode != MeasureSpec.UNSPECIFIED) && (size < maxHeightPx)) {
                heightMeasureSpec
            } else {
                capped
            }
            super.onMeasure(widthMeasureSpec, finalHeightSpec)
        }
    }
}
