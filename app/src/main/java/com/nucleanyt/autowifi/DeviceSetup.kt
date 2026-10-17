package com.nucleanyt.autowifi

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** One-tap setup through Shizuku/root, plus the Xiaomi (MIUI/HyperOS) specific bits. */
object DeviceSetup {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** MIUI's private app-op for "Autostart". */
    private const val MIUI_OP_AUTOSTART = 10008

    val isXiaomi: Boolean
        get() = listOf(Build.MANUFACTURER, Build.BRAND).any {
            it.equals("Xiaomi", true) || it.equals("Redmi", true) || it.equals("POCO", true)
        }

    /** Things only the shell can read. `null` means "couldn't tell". */
    data class ShellState(
        /** MIUI "USB debugging (Security settings)"; without it MIUI blocks `svc data`. */
        val miuiSecureDebugging: Boolean?,
        val miuiAutostart: Boolean?,
    )

    /** Blocking. */
    fun readShellState(context: Context): ShellState {
        if (!isXiaomi || Privileged.backend(context) == Backend.NONE) return ShellState(null, null)
        val secure = Privileged.exec(context, "getprop persist.security.adbinput")?.output?.trim()
        val autostart = Privileged.exec(context, "appops get ${context.packageName} $MIUI_OP_AUTOSTART")?.output
        return ShellState(
            miuiSecureDebugging = when (secure) {
                "1" -> true
                "0", "" -> false
                else -> null
            },
            miuiAutostart = autostart?.let { AppOps.parseAllowed(it) },
        )
    }

    /**
     * Does everything that can be done without the user. Blocking — run in the background.
     * Returns one line per step and whether it worked.
     */
    fun runAutoSetup(context: Context): List<Pair<String, Boolean>> {
        val pkg = context.packageName
        val steps = mutableListOf<Pair<String, Boolean>>()
        fun step(label: String, vararg commands: String) {
            steps += label to commands.all { Privileged.exec(context, it)?.ok == true }
        }

        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        step("Location permission (all the time)", *permissions.map { "pm grant $pkg $it" }.toTypedArray())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            step("Turn Location on", "cmd location set-location-enabled true")
        }
        step("Turn Wi-Fi scanning on", "settings put global wifi_scan_always_enabled 1")
        step(
            "Battery optimization off",
            "dumpsys deviceidle whitelist +$pkg",
            "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
        )
        if (isXiaomi) {
            step("MIUI Autostart for AutoWiFi", "appops set $pkg $MIUI_OP_AUTOSTART allow")
            step(
                "MIUI Autostart and battery for Shizuku",
                "appops set $SHIZUKU_PACKAGE $MIUI_OP_AUTOSTART allow",
                "dumpsys deviceidle whitelist +$SHIZUKU_PACKAGE",
            )
        }

        val prefs = Prefs(context)
        if (prefs.savedSsids.isEmpty()) {
            val ssids = Privileged.exec(context, "cmd wifi list-networks")
                ?.takeIf { it.ok }?.output?.let { Ssids.parseListNetworks(it) }.orEmpty()
            prefs.addSsids(ssids)
            steps += "Import saved Wi-Fi networks (${ssids.size} found)" to ssids.isNotEmpty()
        }
        return steps
    }

    fun miuiAutostartIntent(): Intent = Intent().setComponent(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    )

    /** MIUI's per-app battery saver screen ("No restrictions"). */
    fun miuiBatteryIntent(packageName: String, label: String): Intent =
        Intent("miui.intent.action.HIDDENAPPS_CONFIG_ACTIVITY")
            .putExtra("package_name", packageName)
            .putExtra("package_label", label)

    fun appDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
}
