package com.nucleanyt.autowifi

import org.junit.Assert.assertEquals
import org.junit.Test

class SsidsTest {

    @Test
    fun parsesListNetworksOutput() {
        val output = """
            Network Id      SSID                         Security type
            0            Home WiFi                        wpa2-psk
            1            Cafe                             open
            12           My "Guest" Net 5G                wpa3-sae^wpa2-psk
            3            Home WiFi                        wpa2-psk
        """.trimIndent()
        assertEquals(listOf("Home WiFi", "Cafe", "My \"Guest\" Net 5G"), Ssids.parseListNetworks(output))
    }

    @Test
    fun noNetworks_givesEmptyList() {
        assertEquals(emptyList<String>(), Ssids.parseListNetworks("No networks\n"))
        assertEquals(emptyList<String>(), Ssids.parseListNetworks(""))
    }

    @Test
    fun normalizeStripsQuotesAndPlaceholders() {
        assertEquals("Home", Ssids.normalize("\"Home\""))
        assertEquals("Home", Ssids.normalize("Home"))
        assertEquals("", Ssids.normalize("<unknown ssid>"))
        assertEquals("", Ssids.normalize(null))
    }
}
