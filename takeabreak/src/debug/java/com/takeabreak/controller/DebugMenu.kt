package com.takeabreak.controller

import android.app.Activity
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout

/** Adds the PIN-gated Debug button to [MainActivity] in debug builds. */
object DebugMenu {
    fun addButton(
        activity: Activity,
        container: LinearLayout,
        requirePinThen: (() -> Unit) -> Unit,
    ) {
        val debugBtn = Button(activity).apply {
            text = activity.getString(R.string.debug_title)
            setOnClickListener {
                requirePinThen {
                    activity.startActivity(Intent(activity, DebugActivity::class.java))
                }
            }
        }
        container.addView(debugBtn)
    }
}
