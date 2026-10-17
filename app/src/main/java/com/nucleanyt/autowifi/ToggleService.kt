package com.nucleanyt.autowifi

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Switches Wi-Fi and mobile data without Shizuku or root: it opens Quick settings and taps the
 * Wi-Fi / Mobile data tile, the same way you would. The user turns it on in Accessibility settings.
 */
class ToggleService : AccessibilityService() {

    enum class Tile { WIFI, MOBILE_DATA }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Opens Quick settings and taps [tile]. Takes a few seconds — never call on the main thread. */
    @Synchronized
    fun tapTile(tile: Tile): Boolean {
        val labels = labelsFor(tile)
        if (!performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) return false
        try {
            var node: AccessibilityNodeInfo? = null
            for (attempt in 0 until 6) {
                Thread.sleep(400)
                node = findTile(labels)
                if (node != null) break
            }
            val target = node?.let { clickableSelfOrParent(it) }
            if (target == null) {
                Log.w(TAG, "No $tile tile found (looked for $labels)")
                return false
            }
            val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(600)
            return clicked
        } finally {
            closeShade()
        }
    }

    private fun closeShade() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun findTile(labels: List<String>): AccessibilityNodeInfo? {
        val roots = try {
            windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow)
        } catch (e: Exception) {
            listOfNotNull(rootInActiveWindow)
        }
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var visited = 0
            while (queue.isNotEmpty() && visited++ < MAX_NODES) {
                val node = queue.removeFirst()
                if (TileLabels.matches(node.text, labels) || TileLabels.matches(node.contentDescription, labels)) {
                    return node
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
        }
        return null
    }

    private fun clickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val n = current ?: return null
            if (n.isClickable) return n
            current = n.parent
        }
        return null
    }

    /** The tile names in the phone's language (from System UI), plus English fallbacks. */
    private fun labelsFor(tile: Tile): List<String> {
        val resourceNames = when (tile) {
            Tile.WIFI -> listOf("quick_settings_wifi_label")
            Tile.MOBILE_DATA -> listOf(
                "quick_settings_cellular_detail_title",
                "quick_settings_mobile_data_label",
                "mobile_data_settings_title",
                "mobile_data",
            )
        }
        val english = when (tile) {
            Tile.WIFI -> listOf("Wi-Fi", "WLAN", "Wifi")
            Tile.MOBILE_DATA -> listOf("Mobile data", "Cellular data", "Mobile network data")
        }
        val fromSystem = try {
            val res = packageManager.getResourcesForApplication(SYSTEM_UI)
            resourceNames.mapNotNull { name ->
                @Suppress("DiscouragedApi")
                val id = res.getIdentifier(name, "string", SYSTEM_UI)
                if (id != 0) res.getString(id) else null
            }
        } catch (e: Exception) {
            emptyList()
        }
        return (fromSystem + english).map { TileLabels.normalize(it) }.filter { it.isNotEmpty() }.distinct()
    }

    companion object {
        private const val TAG = "AutoWiFi"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val MAX_NODES = 3_000

        @Volatile
        var instance: ToggleService? = null
            private set
    }
}
