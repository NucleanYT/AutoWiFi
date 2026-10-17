package com.nucleanyt.autowifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwitchPolicyTest {

    private val config = PolicyConfig(dataOffOnWifi = true, wifiOffWhenAway = true, graceMs = 60_000)

    private fun snapshot(
        now: Long,
        wifiEnabled: Boolean = false,
        wifiConnected: Boolean = false,
        savedNearby: Boolean = false,
        airplane: Boolean = false,
    ) = Snapshot(now, airplane, wifiEnabled, wifiConnected, savedNearby)

    @Test
    fun savedNetworkInRange_turnsWifiOn_andKeepsDataOnWhileConnecting() {
        val d = SwitchPolicy(0).decide(snapshot(now = 1_000, savedNearby = true), config)
        assertEquals(true, d.wifi)
        assertEquals(true, d.data)
    }

    @Test
    fun connectedToWifi_turnsDataOff_andLeavesWifiAlone() {
        val d = SwitchPolicy(0).decide(snapshot(now = 1_000, wifiEnabled = true, wifiConnected = true), config)
        assertNull(d.wifi)
        assertEquals(false, d.data)
    }

    @Test
    fun connectedToWifi_keepsDataWhenOptionOff() {
        val d = SwitchPolicy(0).decide(
            snapshot(now = 1_000, wifiEnabled = true, wifiConnected = true),
            config.copy(dataOffOnWifi = false),
        )
        assertNull(d.data)
    }

    @Test
    fun noSavedNetwork_turnsDataOn_andWifiOffOnlyAfterGrace() {
        val policy = SwitchPolicy(0)
        val early = policy.decide(snapshot(now = 30_000, wifiEnabled = true), config)
        assertEquals(true, early.data)
        assertNull(early.wifi) // still within the grace period after start

        val late = policy.decide(snapshot(now = 60_000, wifiEnabled = true), config)
        assertEquals(false, late.wifi)
        assertEquals(true, late.data)
    }

    @Test
    fun leavingWifi_waitsGraceFromLastConnection() {
        val policy = SwitchPolicy(0)
        policy.decide(snapshot(now = 100_000, wifiEnabled = true, wifiConnected = true), config)

        // Wi-Fi dropped and no saved network seen: data comes back at once, Wi-Fi waits.
        val justLost = policy.decide(snapshot(now = 110_000, wifiEnabled = true), config)
        assertEquals(true, justLost.data)
        assertNull(justLost.wifi)

        val later = policy.decide(snapshot(now = 160_000, wifiEnabled = true), config)
        assertEquals(false, later.wifi)
    }

    @Test
    fun wifiAlreadyOff_isNotTurnedOffAgain() {
        val d = SwitchPolicy(0).decide(snapshot(now = 500_000, wifiEnabled = false), config)
        assertNull(d.wifi)
        assertEquals(true, d.data)
    }

    @Test
    fun wifiOffWhenAwayDisabled_neverTurnsWifiOff() {
        val d = SwitchPolicy(0).decide(
            snapshot(now = 500_000, wifiEnabled = true),
            config.copy(wifiOffWhenAway = false),
        )
        assertNull(d.wifi)
        assertEquals(true, d.data)
    }

    @Test
    fun airplaneMode_doesNothing() {
        val d = SwitchPolicy(0).decide(snapshot(now = 500_000, wifiEnabled = true, airplane = true), config)
        assertNull(d.wifi)
        assertNull(d.data)
    }
}
