package com.takeabreak.controller

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The only "blocking screen" in the app. It replaces the old LockActivity, TimeUpActivity
 * and the separate "app is pinned" overlay.
 *
 * It is a window of type TYPE_ACCESSIBILITY_OVERLAY, which:
 *  - needs no extra permission (the accessibility service is enough),
 *  - appears instantly on top of whatever app is showing, even a screen-pinned one,
 *  - adapts seamlessly to the exact screen bounds and navigation bar insets with no gap,
 *    while still leaving the navigation bar / unpin gesture area reachable when required.
 *
 * It only draws and reports button presses. All decisions live in the service.
 * The PIN pad is built from Liquid Glass buttons so no keyboard is needed.
 */
class BlockOverlay(
    private val service: AccessibilityService,
    private val onGoHome: () -> Unit,
    private val onPinAccepted: (String, Decision) -> Unit,
    private val onExtend: (String) -> Unit,
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var pinnedNote: TextView? = null

    var shownPkg: String? = null
        private set
    var shownDecision: Decision? = null
        private set

    val isShowing: Boolean get() = root != null

    private var shownOrientation = -1
    private var shownPinned = false
    private var shownFrame: Frame? = null

    /** Screen area the overlay occupies and internal safe insets for its content. */
    private data class Frame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val topInset: Int,
        val bottomInset: Int,
        val leftInset: Int,
        val rightInset: Int,
    )

    /**
     * Computes the exact overlay bounds for the current screen size, cutout, navigation mode,
     * and screen-pinning state:
     *  - On gesture navigation when NOT pinned, covers the full display edge-to-edge so no
     *    strip of the blocked app peeks through at the bottom.
     *  - On gesture navigation when pinned, reserves only the real bottom gesture inset so
     *    swipe-up-and-hold unpinning remains accessible.
     *  - On 3-button / 2-button navigation, stops cleanly at the exact navigation-bar boundary
     *    so the system navigation bar stays usable with zero gap between it and the overlay.
     */
    private fun computeFrame(pinned: Boolean): Frame {
        val dm = service.resources.displayMetrics
        val gestureNav = isGestureNavigation()

        var fullW = dm.widthPixels
        var fullH = dm.heightPixels
        var navLeft = 0
        var navRight = 0
        var navBottom = 0
        var topSafe: Int
        var cutoutBottom = 0
        var cutoutLeft = 0
        var cutoutRight = 0

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager.maximumWindowMetrics
                fullW = metrics.bounds.width()
                fullH = metrics.bounds.height()
                val nav = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars(),
                )
                val status = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars(),
                )
                val cutout = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.displayCutout(),
                )
                navLeft = nav.left
                navRight = nav.right
                navBottom = nav.bottom
                topSafe = maxOf(status.top, cutout.top)
                cutoutBottom = cutout.bottom
                cutoutLeft = cutout.left
                cutoutRight = cutout.right
            } else {
                val real = Point()
                val usable = Point()
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                @Suppress("DEPRECATION")
                display.getRealSize(real)
                @Suppress("DEPRECATION")
                display.getSize(usable)
                if ((real.x > 0) && (real.y > 0)) {
                    fullW = real.x
                    fullH = real.y
                    navBottom = (real.y - usable.y).coerceAtLeast(0)
                    navRight = (real.x - usable.x).coerceAtLeast(0)
                }
                topSafe = statusBarHeightPx()
            }
        } catch (_: Exception) {
            topSafe = statusBarHeightPx()
        }

        val resLeft: Int
        val resRight: Int
        val resBottom: Int
        if (gestureNav) {
            resLeft = 0
            resRight = 0
            resBottom = if (pinned) {
                if (navBottom > 0) navBottom else (24 * dm.density).toInt()
            } else {
                0
            }
        } else {
            resLeft = navLeft
            resRight = navRight
            resBottom = if (pinned && (navLeft == 0) && (navRight == 0) && (navBottom == 0)) {
                (48 * dm.density).toInt()
            } else {
                navBottom
            }
        }

        val width = (fullW - resLeft - resRight).coerceAtLeast(1)
        val height = (fullH - resBottom).coerceAtLeast(1)
        val innerBottom = if (resBottom == 0) {
            maxOf(navBottom, cutoutBottom)
        } else {
            (cutoutBottom - resBottom).coerceAtLeast(0)
        }
        val innerLeft = (cutoutLeft - resLeft).coerceAtLeast(0)
        val innerRight = (cutoutRight - resRight).coerceAtLeast(0)

        return Frame(
            x = resLeft,
            y = 0,
            width = width,
            height = height,
            topInset = topSafe,
            bottomInset = innerBottom,
            leftInset = innerLeft,
            rightInset = innerRight,
        )
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeightPx(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else 0
    }

    private fun isGestureNavigation(): Boolean = try {
        // 0 = 3-button, 1 = 2-button, 2 = gesture
        android.provider.Settings.Secure.getInt(service.contentResolver, "navigation_mode", 0) == 2
    } catch (_: Exception) {
        false
    }

    private fun orientation(): Int = service.resources.configuration.orientation

    /**
     * Shows (or keeps showing) the block screen. Returns false if Android refused to add it.
     * Safe to call every second: if the same screen and frame are already up it only refreshes the hint.
     */
    @SuppressLint("RtlHardcoded")
    fun show(pkg: String, decision: Decision, pinned: Boolean): Boolean {
        val frame = computeFrame(pinned)
        if ((root != null) && (shownPkg == pkg) && (shownDecision == decision) &&
            (shownOrientation == orientation()) && (shownPinned == pinned) &&
            (shownFrame == frame)
        ) {
            pinnedNote?.visibility = if (pinned) View.VISIBLE else View.GONE
            return true
        }
        hide()
        val view = build(pkg, decision, frame)
        val params = WindowManager.LayoutParams(
            frame.width,
            frame.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable (so Back reaches us). LAYOUT_IN_SCREEN + NO_LIMITS make the exact size
            // above apply edge-to-edge. NOT_TOUCH_MODAL lets touches outside the overlay reach
            // the system navigation bar when uncovered (3-button nav or screen-pinned).
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.OPAQUE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.fitInsetsTypes = 0
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Physical LEFT is intentional because params.x holds the left navigation-bar inset.
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = frame.x
        params.y = frame.y
        params.title = "TakeABreakBlock"
        return try {
            windowManager.addView(view, params)
            root = view
            shownPkg = pkg
            shownDecision = decision
            shownOrientation = orientation()
            shownPinned = pinned
            shownFrame = frame
            pinnedNote?.visibility = if (pinned) View.VISIBLE else View.GONE
            true
        } catch (e: Exception) {
            Log.e("TakeABreak", "Could not show block overlay", e)
            pinnedNote = null
            false
        }
    }

    fun hide() {
        val view = root ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        root = null
        pinnedNote = null
        shownPkg = null
        shownDecision = null
        shownFrame = null
    }

    private fun appLabel(pkg: String): String = try {
        val pm = service.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun build(pkg: String, decision: Decision, frame: Frame): View {
        val ctx: Context = service
        val dp = { value: Float -> AeroGlassUi.dp(ctx, value) }
        val density = ctx.resources.displayMetrics.density.coerceAtLeast(1f)
        val availHeightDp = (frame.height - frame.topInset - frame.bottomInset) / density
        val availWidthDp = (frame.width - frame.leftInset - frame.rightInset) / density

        val isCompact = (availHeightDp < 620f)
        val isTall = (availHeightDp >= 740f)
        val iconW = when {
            isTall -> dp(176f)
            isCompact -> dp(104f)
            else -> dp(142f)
        }
        val iconH = when {
            isTall -> dp(136f)
            isCompact -> dp(82f)
            else -> dp(110f)
        }
        val keyHeightDp = when {
            isTall -> 54f
            isCompact -> 44f
            else -> 50f
        }
        val keyTextSp = if (isCompact) 17.5f else 20f

        val label = appLabel(pkg)
        val entered = StringBuilder()

        val windowGraphic = TakeABreakWindowView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(iconW, iconH).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(if (isCompact) 4f else 8f)
            }
        }

        val title = TextView(ctx).apply {
            text = if (decision == Decision.PIN_LOCK) {
                "$label is locked"
            } else {
                "Time limit reached: $label"
            }
            textSize = if (isCompact) 21f else 25f
            typeface = AeroGlassUi.minimalistFont(light = true)
            letterSpacing = 0.02f
            setTextColor(AeroGlassUi.TEXT_BLACK)
            gravity = Gravity.CENTER
            setPadding(0, dp(2f), 0, dp(4f))
        }

        val message = TextView(ctx).apply {
            text = if (decision == Decision.PIN_LOCK) {
                "Enter your PIN to open this app."
            } else {
                "Enter your PIN to unlock this app for the rest of today, or use the one-time 5-minute extension."
            }
            textSize = if (isCompact) 13.5f else 14.5f
            typeface = AeroGlassUi.minimalistFont(light = false)
            letterSpacing = 0.01f
            setLineSpacing(AeroGlassUi.dpF(ctx, 2f), 1f)
            setTextColor(AeroGlassUi.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(dp(4f), 0, dp(4f), dp(if (isCompact) 8f else 12f))
        }

        val note = AeroGlassUi.createGlassNotice(
            ctx,
            ctx.getString(R.string.screen_pinned_note),
            isWarning = true,
        ).apply {
            visibility = View.GONE
        }
        pinnedNote = note

        val dotsText = TextView(ctx).apply {
            text = "Enter PIN"
            textSize = if (isCompact) 18f else 20f
            typeface = AeroGlassUi.minimalistFont(light = true)
            letterSpacing = 0.14f
            setTextColor(AeroGlassUi.TEXT_HINT)
            gravity = Gravity.CENTER
        }

        val dotsCard = AeroGlassUi.createGlassCard(ctx, cornerDp = 20f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18f), dp(if (isCompact) 10f else 12f), dp(18f), dp(if (isCompact) 11f else 13f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(if (isCompact) 6f else 10f)
            }
            addView(dotsText)
        }

        fun refreshDots() {
            if (entered.isEmpty()) {
                dotsText.text = "Enter PIN"
                dotsText.textSize = if (isCompact) 17f else 18f
                dotsText.letterSpacing = 0.04f
                dotsText.setTextColor(AeroGlassUi.TEXT_HINT)
            } else {
                dotsText.text = "\u25CF".repeat(entered.length)
                dotsText.textSize = if (isCompact) 22f else 25f
                dotsText.letterSpacing = 0.22f
                dotsText.setTextColor(AeroGlassUi.TEXT_BLACK)
            }
        }

        fun submit() {
            val pin = entered.toString()
            entered.setLength(0)
            refreshDots()
            if (Prefs.checkPin(ctx, pin)) {
                onPinAccepted(pkg, decision)
            } else {
                Toast.makeText(ctx, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
        }

        fun digit(d: String) = AeroGlassUi.createKeypadButton(
            context = ctx,
            caption = d,
            tone = AeroGlassUi.GlassTone.LIQUID_CLEAR,
            keyHeightDp = keyHeightDp,
            textSizeSp = keyTextSp,
        ) {
            if (entered.length < 12) {
                entered.append(d)
                refreshDots()
            }
        }

        fun actionKey(caption: String, tone: AeroGlassUi.GlassTone, onClick: () -> Unit) =
            AeroGlassUi.createKeypadButton(
                context = ctx,
                caption = caption,
                tone = tone,
                keyHeightDp = keyHeightDp,
                textSizeSp = if (isCompact) 15f else 16.5f,
                onClick = onClick,
            )

        fun row(vararg keys: View): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            keys.forEach { addView(it) }
        }

        val pad = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(if (isCompact) 8f else 12f)
            }
            addView(row(digit("1"), digit("2"), digit("3")))
            addView(row(digit("4"), digit("5"), digit("6")))
            addView(row(digit("7"), digit("8"), digit("9")))
            addView(
                row(
                    actionKey("Del", AeroGlassUi.GlassTone.LIQUID_FROST) {
                        if (entered.isNotEmpty()) {
                            entered.deleteCharAt(entered.length - 1)
                            refreshDots()
                        }
                    },
                    digit("0"),
                    actionKey("OK", AeroGlassUi.GlassTone.LIQUID_SKY) { submit() },
                ),
            )
        }

        val homeBtn = AeroGlassUi.createGlassButton(
            ctx,
            "Go to Home Screen",
            AeroGlassUi.GlassTone.LIQUID_FROST,
        ) {
            onGoHome()
        }.apply {
            if (isCompact) {
                minHeight = dp(48f)
                minimumHeight = dp(48f)
                textSize = 15.5f
            }
        }

        val basePadH = dp(22f)
        val maxContentPx = dp(380f)
        val availWidthPx = (availWidthDp * density).toInt()
        val extraSide = ((availWidthPx - maxContentPx) / 2).coerceAtLeast(0)
        val padTop = frame.topInset + dp(if (isCompact) 10f else 18f)
        val padBot = frame.bottomInset + dp(if (isCompact) 12f else 20f)

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                basePadH + frame.leftInset + extraSide,
                padTop,
                basePadH + frame.rightInset + extraSide,
                padBot,
            )
            addView(windowGraphic)
            addView(title)
            addView(message)
            addView(note)
            addView(dotsCard)
            addView(pad)
            addView(homeBtn)
            if (decision == Decision.TIME_UP) {
                val used = Prefs.isExtensionUsed(ctx, pkg)
                val extendBtn = AeroGlassUi.createGlassButton(
                    ctx,
                    if (used) {
                        "5-minute extension already used today"
                    } else {
                        "Extend 5 minutes (one-time today, no PIN)"
                    },
                    AeroGlassUi.GlassTone.LIQUID_CLEAR,
                ) {
                    onExtend(pkg)
                }.apply {
                    isEnabled = !used
                    if (isCompact) {
                        minHeight = dp(48f)
                        minimumHeight = dp(48f)
                        textSize = 14.5f
                    }
                }
                addView(extendBtn)
            }
        }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        return OverlayRoot(ctx) { onGoHome() }.apply {
            background = AeroGlassUi.MainMenuBackgroundDrawable()
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    /**
     * Root view of the overlay. Its class name must not end in "Activity": the service uses
     * that to tell our own overlay events apart from our real screens (MainActivity etc.).
     */
    private class OverlayRoot(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }
}
