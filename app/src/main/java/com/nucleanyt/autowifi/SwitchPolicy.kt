package com.nucleanyt.autowifi

/** What the phone looks like right now. Times are in milliseconds from a monotonic clock. */
data class Snapshot(
    val now: Long,
    val airplaneMode: Boolean,
    val wifiEnabled: Boolean,
    val wifiConnected: Boolean,
    val savedNetworkNearby: Boolean,
)

data class PolicyConfig(
    /** Turn mobile data off while the phone is connected to Wi-Fi. */
    val dataOffOnWifi: Boolean = true,
    /** Turn Wi-Fi off when it is not connected and no saved network has been seen for [graceMs]. */
    val wifiOffWhenAway: Boolean = true,
    /** How long Wi-Fi gets to find/connect to a network before it is switched off again. */
    val graceMs: Long = 60_000,
)

/** Desired radio states. `null` means "leave it as it is". */
data class Decision(val wifi: Boolean?, val data: Boolean?, val reason: String)

/**
 * Decides when to switch between Wi-Fi and mobile data:
 *  - connected to Wi-Fi                      -> keep Wi-Fi, (optionally) mobile data off
 *  - a saved Wi-Fi network is in range       -> Wi-Fi on, data stays on until Wi-Fi connects
 *  - no saved network in range / not connected -> mobile data on, Wi-Fi off after a grace period
 */
class SwitchPolicy(startedAt: Long) {

    // Last moment Wi-Fi was useful (connected or a saved network was in range).
    // Starts at the service start time so Wi-Fi is not switched off before the first scan.
    private var lastWifiUsefulAt = startedAt

    fun decide(s: Snapshot, config: PolicyConfig): Decision {
        if (s.airplaneMode) {
            return Decision(wifi = null, data = null, reason = "Airplane mode is on — not touching anything")
        }
        if (s.wifiConnected) {
            lastWifiUsefulAt = s.now
            return Decision(
                wifi = null,
                data = if (config.dataOffOnWifi) false else null,
                reason = "Connected to Wi-Fi",
            )
        }
        if (s.savedNetworkNearby) {
            lastWifiUsefulAt = s.now
            // Keep data on while Wi-Fi connects so there is no gap in the connection.
            return Decision(wifi = true, data = true, reason = "Saved Wi-Fi in range — connecting")
        }
        val turnWifiOff = config.wifiOffWhenAway && s.wifiEnabled && s.now - lastWifiUsefulAt >= config.graceMs
        return Decision(
            wifi = if (turnWifiOff) false else null,
            data = true,
            reason = "No saved Wi-Fi in range — using mobile data",
        )
    }
}
