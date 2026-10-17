package com.nucleanyt.autowifi

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("autowifi", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    var dataOffOnWifi: Boolean
        get() = sp.getBoolean(KEY_DATA_OFF_ON_WIFI, true)
        set(value) = sp.edit().putBoolean(KEY_DATA_OFF_ON_WIFI, value).apply()

    var wifiOffWhenAway: Boolean
        get() = sp.getBoolean(KEY_WIFI_OFF_WHEN_AWAY, true)
        set(value) = sp.edit().putBoolean(KEY_WIFI_OFF_WHEN_AWAY, value).apply()

    var useRoot: Boolean
        get() = sp.getBoolean(KEY_USE_ROOT, false)
        set(value) = sp.edit().putBoolean(KEY_USE_ROOT, value).apply()

    /** MIUI's battery saver can't be read by apps, so the user confirms it. */
    var miuiBatteryConfirmed: Boolean
        get() = sp.getBoolean(KEY_MIUI_BATTERY_CONFIRMED, false)
        set(value) = sp.edit().putBoolean(KEY_MIUI_BATTERY_CONFIRMED, value).apply()

    val savedSsids: Set<String>
        get() = sp.getStringSet(KEY_SAVED_SSIDS, emptySet())!!.toSet()

    /** Adds networks; returns how many were new. */
    fun addSsids(ssids: Collection<String>): Int = synchronized(LOCK) {
        val current = savedSsids
        val added = ssids.map { Ssids.normalize(it) }.filter { it.isNotEmpty() && it !in current }.toSet()
        if (added.isNotEmpty()) sp.edit().putStringSet(KEY_SAVED_SSIDS, current + added).apply()
        added.size
    }

    fun removeSsid(ssid: String) = synchronized(LOCK) {
        sp.edit().putStringSet(KEY_SAVED_SSIDS, savedSsids - ssid).apply()
    }

    fun policyConfig() = PolicyConfig(dataOffOnWifi = dataOffOnWifi, wifiOffWhenAway = wifiOffWhenAway)

    private companion object {
        val LOCK = Any()
        const val KEY_ENABLED = "enabled"
        const val KEY_DATA_OFF_ON_WIFI = "data_off_on_wifi"
        const val KEY_WIFI_OFF_WHEN_AWAY = "wifi_off_when_away"
        const val KEY_USE_ROOT = "use_root"
        const val KEY_SAVED_SSIDS = "saved_ssids"
        const val KEY_MIUI_BATTERY_CONFIRMED = "miui_battery_confirmed"
    }
}
