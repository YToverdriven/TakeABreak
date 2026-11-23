package com.takeabreak.controller

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
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
 *  - is NOT laid out under the status bar / navigation bar, so Back + Overview (or the
 *    swipe-up-and-hold gesture) can still be used to unpin an app.
 *
 * It only draws and reports button presses. All decisions live in the service.
 * The PIN pad is built from plain buttons so no keyboard is needed.
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

    /** Screen area the overlay occupies. Everything outside it is left to the system. */
    private class Frame(val x: Int, val width: Int, val height: Int, val topInset: Int)

    /**
     * The overlay must NOT cover the navigation bar / gesture area, otherwise a pinned app
     * cannot be unpinned. Uses the real navigation-bar insets (Android 11+), and never
     * reserves less than a safe minimum, so it still works if the insets read as zero.
     */
    private fun computeFrame(): Frame {
        val dm = service.resources.displayMetrics
        var width = dm.widthPixels
        var height = dm.heightPixels
        var left = 0
        var right = 0
        var bottom = 0
        var top = 0
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager.maximumWindowMetrics
                width = metrics.bounds.width()
                height = metrics.bounds.height()
                val nav = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                left = nav.left
                right = nav.right
                bottom = nav.bottom
                top = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            }
        } catch (_: Exception) {
        }
        val minBottom = ((if (isGestureNavigation()) 32 else 56) * dm.density).toInt()
        bottom = maxOf(bottom, minBottom)
        return Frame(x = left, width = width - left - right, height = height - bottom, topInset = top)
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
     * Safe to call every second: if the same screen is already up it only refreshes the hint.
     */
    @SuppressLint("RtlHardcoded")
    fun show(pkg: String, decision: Decision, pinned: Boolean): Boolean {
        if ((root != null) && (shownPkg == pkg) && (shownDecision == decision) &&
            (shownOrientation == orientation())
        ) {
            pinnedNote?.visibility = if (pinned) View.VISIBLE else View.GONE
            return true
        }
        hide()
        val frame = computeFrame()
        val view = build(pkg, decision, frame.topInset)
        val params = WindowManager.LayoutParams(
            frame.width,
            frame.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable (so Back reaches us). LAYOUT_IN_SCREEN + NO_LIMITS make the exact size
            // above apply. NOT_TOUCH_MODAL lets touches outside the overlay reach the system
            // navigation bar, so a pinned app can be unpinned.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.OPAQUE,
        )
        // Physical LEFT is intentional because params.x holds the left navigation-bar inset.
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = frame.x
        params.y = 0
        params.title = "TakeABreakBlock"
        return try {
            windowManager.addView(view, params)
            root = view
            shownPkg = pkg
            shownDecision = decision
            shownOrientation = orientation()
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
    }

    private fun appLabel(pkg: String): String = try {
        val pm = service.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun build(pkg: String, decision: Decision, topInset: Int): View {
        val ctx: Context = service
        val label = appLabel(pkg)
        val entered = StringBuilder()

        val title = TextView(ctx).apply {
            text = if (decision == Decision.PIN_LOCK) "$label is locked" else "Time limit reached: $label"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val message = TextView(ctx).apply {
            text = if (decision == Decision.PIN_LOCK) {
                "Enter your PIN to open this app."
            } else {
                "Enter your PIN to unlock this app for the rest of today, or use the one-time 5-minute extension."
            }
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }

        val note = TextView(ctx).apply {
            text = ctx.getString(R.string.screen_pinned_note)
            textSize = 14f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
            visibility = View.GONE
        }
        pinnedNote = note

        val dots = TextView(ctx).apply {
            text = " "
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }

        fun refreshDots() {
            dots.text = if (entered.isEmpty()) " " else "\u25CF".repeat(entered.length)
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

        fun key(caption: String, onClick: () -> Unit): Button = Button(ctx).apply {
            text = caption
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        fun digit(d: String): Button = key(d) {
            if (entered.length < 12) {
                entered.append(d)
                refreshDots()
            }
        }

        fun row(vararg keys: View): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            keys.forEach { addView(it) }
        }

        val pad = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(row(digit("1"), digit("2"), digit("3")))
            addView(row(digit("4"), digit("5"), digit("6")))
            addView(row(digit("7"), digit("8"), digit("9")))
            addView(
                row(
                    key("Del") {
                        if (entered.isNotEmpty()) {
                            entered.deleteCharAt(entered.length - 1)
                            refreshDots()
                        }
                    },
                    digit("0"),
                    key("OK") { submit() }
                )
            )
        }

        val homeBtn = Button(ctx).apply {
            text = "Go to Home Screen"
            setOnClickListener { onGoHome() }
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48 + topInset, 48, 48)
            addView(title)
            addView(message)
            addView(note)
            addView(dots)
            addView(pad)
            addView(homeBtn)
            if (decision == Decision.TIME_UP) {
                val used = Prefs.isExtensionUsed(ctx, pkg)
                addView(
                    Button(ctx).apply {
                        text = if (used) "5-minute extension already used today"
                        else "Extend 5 minutes (one-time today, no PIN)"
                        isEnabled = !used
                        setOnClickListener { onExtend(pkg) }
                    },
                )
            }
        }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        return OverlayRoot(ctx) { onGoHome() }.apply {
            setBackgroundColor(Color.BLACK)
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
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
