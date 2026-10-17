package com.nucleanyt.autowifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import java.text.DateFormat
import java.util.Date

/**
 * Foreground service that watches for saved Wi-Fi networks and switches between Wi-Fi and
 * mobile data. All the work happens on a background thread ([handler]).
 */
class AutoSwitchService : Service() {

    data class Status(
        val backend: Backend,
        val wifiOn: Boolean,
        val wifiSsid: String?,
        val dataOn: Boolean?,
        val savedInRange: List<String>,
        val reason: String,
        val lastAction: String,
    )

    private lateinit var prefs: Prefs
    private lateinit var wifi: WifiManager
    private lateinit var connectivity: ConnectivityManager
    private lateinit var telephony: TelephonyManager
    private lateinit var notifications: NotificationManager
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var policy: SwitchPolicy

    private var started = false
    private var lastScanAt = Long.MIN_VALUE / 2
    private var lastWifiCommandAt = Long.MIN_VALUE / 2
    private var lastDataCommandAt = Long.MIN_VALUE / 2
    private var lastCommandedData: Boolean? = null
    private var lastAction = "Nothing yet"
    private var shownHint: String? = null
    private var shownNotificationText: String? = null

    /** Wi-Fi networks we are connected to, mapped to whether they have working internet. */
    private val wifiNetworks = HashMap<Network, Boolean>()

    private val evaluateRunnable = Runnable { evaluate() }

    private val tick = object : Runnable {
        override fun run() {
            requestScan()
            evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = scheduleEvaluate()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            wifiNetworks[network] = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            scheduleEvaluate()
        }

        override fun onLost(network: Network) {
            wifiNetworks.remove(network)
            scheduleEvaluate()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wifi = applicationContext.getSystemService(WifiManager::class.java)
        connectivity = getSystemService(ConnectivityManager::class.java)
        telephony = getSystemService(TelephonyManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        createChannels(this)
        worker = HandlerThread("autowifi-worker").apply { start() }
        handler = Handler(worker.looper)
        policy = SwitchPolicy(SystemClock.elapsedRealtime())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            running = true
            registerListeners()
            handler.post(tick)
        } else {
            scheduleEvaluate()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        status = null
        if (started) {
            try {
                unregisterReceiver(receiver)
                connectivity.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                Log.w(TAG, "unregister failed", e)
            }
        }
        handler.removeCallbacksAndMessages(null)
        worker.quitSafely()
        notifications.cancel(HINT_NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun goForeground(): Boolean = try {
        val notification = buildStatusNotification("Starting…", "Looking for saved Wi-Fi networks")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(STATUS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(STATUS_NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        // Android 12+ can refuse a location service started from the background (e.g. after a
        // reboot without "Allow all the time" location access).
        Log.w(TAG, "startForeground failed", e)
        notifications.notify(
            HINT_NOTIFICATION_ID,
            buildHintNotification("AutoWiFi is paused", "Tap to open AutoWiFi and start it again.", openAppIntent()),
        )
        false
    }

    private fun registerListeners() {
        val filter = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter, null, handler)
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivity.registerNetworkCallback(request, networkCallback, handler)
    }

    private fun scheduleEvaluate() {
        handler.removeCallbacks(evaluateRunnable)
        handler.postDelayed(evaluateRunnable, 500)
    }

    @Suppress("DEPRECATION")
    private fun requestScan() {
        val now = SystemClock.elapsedRealtime()
        // Android allows 4 scans per 2 minutes, so stay at one every 30 s.
        if (now - lastScanAt < SCAN_INTERVAL_MS) return
        lastScanAt = now
        try {
            wifi.startScan()
        } catch (e: SecurityException) {
            Log.w(TAG, "startScan not allowed", e)
        }
    }

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val config = prefs.policyConfig()
        val wifiOn = isWifiOn()
        val wifiConnected = wifiNetworks.values.any { it }
        val ssid = if (wifiNetworks.isNotEmpty()) currentSsid() else null

        // A network the phone connects to is a saved network, so remember it.
        if (ssid != null && prefs.addSsids(listOf(ssid)) > 0) lastAction = "Learned network \"$ssid\" (${time()})"

        val inRange = savedNetworksInRange(prefs.savedSsids)
        val snapshot = Snapshot(
            now = now,
            airplaneMode = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
            wifiEnabled = wifiOn,
            wifiConnected = wifiConnected,
            savedNetworkNearby = inRange.isNotEmpty(),
        )
        val decision = policy.decide(snapshot, config)
        val backend = Privileged.backend(this)

        val wantWifi = decision.wifi
        if (wantWifi != null && wantWifi != wifiOn && now - lastWifiCommandAt >= COMMAND_COOLDOWN_MS) {
            lastWifiCommandAt = now
            if (setWifi(wantWifi, backend)) lastAction = "Turned Wi-Fi ${onOff(wantWifi)} (${time()})"
        }

        var dataOn = readDataEnabled(backend)
        val wantData = decision.data
        if (wantData != null && wantData != (dataOn ?: lastCommandedData) &&
            now - lastDataCommandAt >= COMMAND_COOLDOWN_MS
        ) {
            lastDataCommandAt = now
            if (setData(wantData, backend)) {
                lastCommandedData = wantData
                dataOn = wantData
                lastAction = "Turned mobile data ${onOff(wantData)} (${time()})"
            }
        }

        // Without Shizuku/root we can only suggest the switch.
        val hint = when {
            backend != Backend.NONE -> null
            wantWifi == true && !isWifiOn() ->
                "Saved Wi-Fi \"${inRange.firstOrNull()}\" is in range — tap to turn Wi-Fi on" to
                    settingsPanelIntent(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS)
            wantData == true && dataOn == false ->
                "Not on Wi-Fi — tap to turn mobile data on" to
                    settingsPanelIntent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_WIRELESS_SETTINGS)
            else -> null
        }
        if (hint?.first != shownHint) {
            if (hint == null) {
                notifications.cancel(HINT_NOTIFICATION_ID)
            } else {
                notifications.notify(HINT_NOTIFICATION_ID, buildHintNotification("AutoWiFi", hint.first, hint.second))
            }
            shownHint = hint?.first
        }

        val current = Status(
            backend = backend,
            wifiOn = isWifiOn(),
            wifiSsid = ssid,
            dataOn = dataOn,
            savedInRange = inRange,
            reason = decision.reason,
            lastAction = lastAction,
        )
        status = current
        updateStatusNotification(current)
    }

    private fun isWifiOn(): Boolean = when (wifi.wifiState) {
        WifiManager.WIFI_STATE_ENABLED, WifiManager.WIFI_STATE_ENABLING -> true
        else -> false
    }

    @Suppress("DEPRECATION")
    private fun currentSsid(): String? =
        Ssids.normalize(wifi.connectionInfo?.ssid).takeIf { it.isNotEmpty() }

    @Suppress("DEPRECATION")
    private fun savedNetworksInRange(saved: Set<String>): List<String> {
        if (saved.isEmpty()) return emptyList()
        val results = try {
            wifi.scanResults
        } catch (e: SecurityException) {
            return emptyList()
        } ?: return emptyList()
        val nowMicros = SystemClock.elapsedRealtime() * 1000
        return results
            .filter { nowMicros - it.timestamp <= SCAN_MAX_AGE_US && it.level >= MIN_RSSI_DBM }
            .sortedByDescending { it.level }
            .map { Ssids.normalize(it.SSID) }
            .filter { it in saved }
            .distinct()
    }

    private fun readDataEnabled(backend: Backend): Boolean? {
        try {
            return telephony.isDataEnabled
        } catch (e: SecurityException) {
            // Some Android versions want READ_PHONE_STATE. Use what we last set, and only
            // ask the shell when we have never set it (each root call may show a toast).
        }
        if (lastCommandedData == null && backend != Backend.NONE) {
            lastCommandedData = when (Privileged.exec(this, "settings get global mobile_data")?.output?.trim()) {
                "1" -> true
                "0" -> false
                else -> null
            }
        }
        return lastCommandedData
    }

    @Suppress("DEPRECATION")
    private fun setWifi(on: Boolean, backend: Backend): Boolean {
        // Before Android 10 normal apps may still toggle Wi-Fi themselves.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && wifi.setWifiEnabled(on)) return true
        if (backend == Backend.NONE) return false
        val arg = if (on) "enable" else "disable"
        if (Privileged.exec(this, "svc wifi $arg")?.ok == true) return true
        return Privileged.exec(this, "cmd wifi set-wifi-enabled ${if (on) "enabled" else "disabled"}")?.ok == true
    }

    private fun setData(on: Boolean, backend: Backend): Boolean {
        if (backend == Backend.NONE) return false
        return Privileged.exec(this, "svc data ${if (on) "enable" else "disable"}")?.ok == true
    }

    private fun updateStatusNotification(s: Status) {
        if (!running) return
        val title = when {
            s.wifiSsid != null -> "On Wi-Fi: ${s.wifiSsid}"
            s.savedInRange.isNotEmpty() -> "Connecting to ${s.savedInRange.first()}…"
            else -> "No saved Wi-Fi in range"
        }
        val text = "Wi-Fi ${onOff(s.wifiOn)} · Mobile data ${s.dataOn?.let { onOff(it) } ?: "?"}" +
            if (s.backend == Backend.NONE) " · Shizuku/root not set up" else ""
        if (title + text == shownNotificationText) return
        shownNotificationText = title + text
        notifications.notify(STATUS_NOTIFICATION_ID, buildStatusNotification(title, text))
    }

    private fun buildStatusNotification(title: String, text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, AutoSwitchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stopIntent).build())
            .build()
    }

    private fun buildHintNotification(title: String, text: String, intent: PendingIntent): Notification =
        Notification.Builder(this, CHANNEL_HINTS)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun settingsPanelIntent(panelAction: String, fallbackAction: String): PendingIntent {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) panelAction else fallbackAction
        return PendingIntent.getActivity(
            this, action.hashCode(), Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val TAG = "AutoWiFi"
        private const val ACTION_STOP = "com.nucleanyt.autowifi.STOP"
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_HINTS = "hints"
        private const val STATUS_NOTIFICATION_ID = 1
        private const val HINT_NOTIFICATION_ID = 2

        private const val TICK_MS = 30_000L
        private const val SCAN_INTERVAL_MS = 30_000L
        private const val SCAN_MAX_AGE_US = 3 * 60 * 1_000_000L
        private const val MIN_RSSI_DBM = -85
        private const val COMMAND_COOLDOWN_MS = 15_000L

        @Volatile
        var running = false
            private set

        @Volatile
        var status: Status? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AutoSwitchService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoSwitchService::class.java))
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "Status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows what AutoWiFi is doing"
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_HINTS, "Suggestions", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Asks you to switch Wi-Fi or data when AutoWiFi can't do it itself"
                },
            )
        }

        private fun onOff(on: Boolean) = if (on) "on" else "off"

        private fun time(): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
    }
}
