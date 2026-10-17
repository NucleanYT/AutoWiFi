package com.nucleanyt.autowifi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileLabelsTest {

    private val wifi = listOf("Wi-Fi", "WLAN").map { TileLabels.normalize(it) }
    private val data = listOf("Mobile data", "Cellular data").map { TileLabels.normalize(it) }

    @Test
    fun matchesTileTextAndDescriptions() {
        assertTrue(TileLabels.matches("Wi-Fi", wifi))
        assertTrue(TileLabels.matches("Wi‑Fi", wifi)) // non-breaking hyphen used by System UI
        assertTrue(TileLabels.matches("Wi-Fi,On,HomeNet", wifi))
        assertTrue(TileLabels.matches("Wi-Fi off", wifi))
        assertTrue(TileLabels.matches("Mobile data", data))
        assertTrue(TileLabels.matches("Mobile data, Off", data))
        assertTrue(TileLabels.matches("MOBILE DATA", data))
    }

    @Test
    fun ignoresOtherTiles() {
        assertFalse(TileLabels.matches("Wi-Fi calling", wifi))
        assertFalse(TileLabels.matches("Wi-Fi hotspot", wifi))
        assertFalse(TileLabels.matches("Data saver", data))
        assertFalse(TileLabels.matches("Mobile data limit", data))
        assertFalse(TileLabels.matches("", wifi))
        assertFalse(TileLabels.matches(null, wifi))
    }
}
