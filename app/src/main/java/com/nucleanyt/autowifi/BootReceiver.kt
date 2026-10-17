package com.nucleanyt.autowifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restarts auto-switching after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs(context).enabled) return
        try {
            AutoSwitchService.start(context)
        } catch (e: Exception) {
            Log.w("AutoWiFi", "Could not start after ${intent.action}", e)
        }
    }
}
