package com.takeabreak.controller

import android.app.Activity
import android.widget.LinearLayout

/** Release builds omit the Debug button and [DebugActivity]. */
object DebugMenu {
    @Suppress("UNUSED_PARAMETER")
    fun addButton(
        activity: Activity,
        container: LinearLayout,
        requirePinThen: (() -> Unit) -> Unit,
    ) = Unit
}
