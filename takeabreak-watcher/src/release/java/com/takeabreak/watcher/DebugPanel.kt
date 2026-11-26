package com.takeabreak.watcher

import android.app.Activity
import android.widget.LinearLayout

/**
 * Release builds of Watcher show no visible logs (no Messages or Link log) and no Test SMS box.
 */
@Suppress("UNUSED_PARAMETER")
class DebugPanel(
    activity: Activity,
    onStatusChanged: () -> Unit,
) {
    fun attach(root: LinearLayout) = Unit
    fun onStart() = Unit
    fun onStop() = Unit
    fun onDestroy() = Unit
    fun refresh() = Unit
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) = Unit
}
