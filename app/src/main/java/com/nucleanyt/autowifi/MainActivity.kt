package com.nucleanyt.autowifi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private class Check(
        val title: String,
        val ok: Boolean,
        val detail: String,
        val required: Boolean,
        val actions: List<Pair<String, () -> Unit>> = emptyList(),
    ) {
        val key get() = "$title|$ok|$detail|${actions.joinToString { it.first }}"
    }

    private lateinit var prefs: Prefs
    private lateinit var wifi: WifiManager
    private val ui = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()

    private lateinit var statusText: TextView
    private lateinit var masterSwitch: Switch
    private lateinit var dataOffSwitch: Switch
    private lateinit var wifiOffSwitch: Switch
    private lateinit var setupList: LinearLayout
    private lateinit var networksList: LinearLayout

    private var shownChecks: String? = null
    private var shownNetworks: String? = null
    private var updatingUi = false

    private lateinit var autoSetupButton: Button
    private var autoSetupRunning = false

    // Xiaomi settings only the shell can read; refreshed in the background.
    private var shellState = DeviceSetup.ShellState(null, null)
    private var shellStateAt = 0L
    private var shellStateLoading = false

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1_500)
        }
    }

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener { ui.post { refresh() } }
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> ui.post { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        wifi = applicationContext.getSystemService(WifiManager::class.java)
        AutoSwitchService.createChannels(this)
        setContentView(buildUi())
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        background.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Restart the service if it was supposed to run but Android refused to start it (e.g. after a reboot).
        if (prefs.enabled && !AutoSwitchService.running && hasLocationPermission()) startService()
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ---------------------------------------------------------------- UI construction

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(statusText)

        masterSwitch = Switch(this).apply {
            text = "Auto-switch between Wi-Fi and mobile data"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setOnCheckedChangeListener { _, checked -> if (!updatingUi) onMasterSwitch(checked) }
        }
        root.addView(masterSwitch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(header("Setup"))
        root.addView(
            small(
                "1. Get Shizuku running and allow AutoWiFi to use it (first item below).\n" +
                    "2. Tap \"Set up everything automatically\" — it does the rest.",
            ),
        )
        autoSetupButton = Button(this).apply {
            text = "Set up everything automatically"
            setAllCaps(false)
            setOnClickListener { runAutoSetup() }
        }
        root.addView(autoSetupButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setupList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(setupList)

        root.addView(header("Options"))
        dataOffSwitch = Switch(this).apply {
            text = "Turn mobile data off while connected to Wi-Fi"
            setOnCheckedChangeListener { _, checked -> if (!updatingUi) prefs.dataOffOnWifi = checked }
        }
        root.addView(dataOffSwitch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        wifiOffSwitch = Switch(this).apply {
            text = "Turn Wi-Fi off when no saved network is in range"
            setPadding(0, dp(8), 0, 0)
            setOnCheckedChangeListener { _, checked -> if (!updatingUi) prefs.wifiOffWhenAway = checked }
        }
        root.addView(wifiOffSwitch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(header("Saved Wi-Fi networks"))
        root.addView(
            small(
                "Wi-Fi is only turned on when one of these is in range. Networks you connect to are " +
                    "added automatically.",
            ),
        )
        networksList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(networksList)
        root.addView(
            buttonRow(
                listOf(
                    "Import from phone" to ::importNetworks,
                    "Add nearby…" to ::addNearbyNetwork,
                    "Add by name…" to ::addNetworkByName,
                ),
            ),
        )

        root.addView(header("How it works"))
        root.addView(
            small(
                "Every 30 seconds AutoWiFi checks which Wi-Fi networks are around. When a saved network " +
                    "is in range it turns Wi-Fi on, and once Wi-Fi is connected it turns mobile data off. " +
                    "When you leave, it turns mobile data back on and Wi-Fi off.\n\n" +
                    "Android does not let normal apps switch mobile data (or Wi-Fi on Android 10+), so " +
                    "AutoWiFi uses Shizuku or root for that. Without them it shows a notification you can " +
                    "tap to switch yourself.",
            ),
        )

        return ScrollView(this).apply { addView(root) }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setAllCaps(true)
        setTextColor(ACCENT)
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun small(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        alpha = 0.8f
    }

    private fun buttonRow(buttons: List<Pair<String, () -> Unit>>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START
        for ((label, action) in buttons) {
            addView(
                Button(this@MainActivity).apply {
                    text = label
                    setAllCaps(false)
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
            )
        }
    }

    // ---------------------------------------------------------------- Refresh

    private fun refresh() {
        if (isDestroyed) return
        refreshShellState()
        autoSetupButton.isEnabled = !autoSetupRunning
        autoSetupButton.text = if (autoSetupRunning) "Setting up…" else "Set up everything automatically"

        updatingUi = true
        masterSwitch.isChecked = prefs.enabled
        dataOffSwitch.isChecked = prefs.dataOffOnWifi
        wifiOffSwitch.isChecked = prefs.wifiOffWhenAway
        updatingUi = false

        statusText.text = statusLine()

        val checks = checks()
        val checksKey = checks.joinToString("\n") { it.key }
        if (checksKey != shownChecks) {
            shownChecks = checksKey
            setupList.removeAllViews()
            checks.forEach { setupList.addView(checkView(it)) }
        }

        val inRange = AutoSwitchService.status?.savedInRange.orEmpty()
        val networks = prefs.savedSsids.sortedBy { it.lowercase() }
        val networksKey = networks.joinToString("\n") + "|" + inRange.joinToString()
        if (networksKey != shownNetworks) {
            shownNetworks = networksKey
            networksList.removeAllViews()
            if (networks.isEmpty()) {
                networksList.addView(small("No networks yet — import them or add one below.").apply { setPadding(0, dp(8), 0, 0) })
            }
            networks.forEach { networksList.addView(networkRow(it, it in inRange)) }
        }
    }

    private fun refreshShellState() {
        val backend = Privileged.backend(this)
        if (!DeviceSetup.isXiaomi || backend == Backend.NONE || shellStateLoading) return
        // Root managers may show a toast for every call, so ask root less often.
        val maxAge = if (backend == Backend.SHIZUKU) 10_000L else 120_000L
        val now = SystemClock.elapsedRealtime()
        if (shellStateAt != 0L && now - shellStateAt < maxAge) return
        shellStateLoading = true
        background.execute {
            val state = DeviceSetup.readShellState(applicationContext)
            ui.post {
                shellState = state
                shellStateAt = SystemClock.elapsedRealtime()
                shellStateLoading = false
                refresh()
            }
        }
    }

    private fun statusLine(): String {
        if (!prefs.enabled) return "Auto-switching is off."
        val s = AutoSwitchService.status ?: return "Starting…"
        val wifiText = when {
            s.wifiSsid != null -> "on, connected to ${s.wifiSsid}"
            s.wifiOn -> "on, not connected"
            else -> "off"
        }
        val dataText = when (s.dataOn) {
            true -> "on"
            false -> "off"
            null -> "unknown"
        }
        return "${s.reason}\n" +
            "Wi-Fi: $wifiText\n" +
            "Mobile data: $dataText\n" +
            "Control: ${if (s.backend == Backend.NONE) "suggestions only (no Shizuku/root)" else s.backend.label}\n" +
            "Last action: ${s.lastAction}" +
            if (s.dataSwitchBlocked) "\n⚠ Mobile data didn't switch — see Setup below." else ""
    }

    private fun checkView(check: Check) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(
            TextView(this@MainActivity).apply {
                val mark = when {
                    check.ok -> "✓"
                    check.required -> "✗"
                    else -> "○"
                }
                text = "$mark  ${check.title}${if (!check.required && !check.ok) " (recommended)" else ""}"
                textSize = 16f
                setTextColor(
                    when {
                        check.ok -> GOOD
                        check.required -> BAD
                        else -> currentTextColor
                    },
                )
            },
        )
        addView(small(check.detail).apply { setPadding(dp(24), 0, 0, 0) })
        if (!check.ok && check.actions.isNotEmpty()) {
            addView(buttonRow(check.actions).apply { setPadding(dp(16), 0, 0, 0) })
        }
    }

    private fun networkRow(ssid: String, inRange: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            TextView(this@MainActivity).apply {
                text = if (inRange) "$ssid  •  in range" else ssid
                textSize = 16f
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        addView(
            Button(this@MainActivity, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Remove"
                setAllCaps(false)
                setOnClickListener {
                    prefs.removeSsid(ssid)
                    refresh()
                }
            },
        )
    }

    // ---------------------------------------------------------------- Setup checks

    private fun checks(): List<Check> {
        val list = mutableListOf<Check>()

        list += controlCheck()
        if (DeviceSetup.isXiaomi) list += miuiDebuggingCheck()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Check(
                title = "Notifications",
                ok = granted(Manifest.permission.POST_NOTIFICATIONS),
                detail = "Shows the status notification and switch suggestions.",
                required = false,
                actions = listOf("Allow" to { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }),
            )
        }

        list += Check(
            title = "Location permission",
            ok = hasLocationPermission(),
            detail = "Android only shows nearby Wi-Fi networks to apps with location access.",
            required = true,
            actions = listOf("Allow" to ::requestLocationPermission),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocationPermission()) {
            list += Check(
                title = "Location: Allow all the time",
                ok = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                detail = "Lets AutoWiFi start again by itself after the phone restarts.",
                required = false,
                actions = listOf(
                    "Allow" to { requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 3) },
                ),
            )
        }

        list += Check(
            title = "Location turned on",
            ok = isLocationOn(),
            detail = "Wi-Fi scanning only works while Location is on.",
            required = true,
            actions = listOf("Open settings" to { open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }),
        )

        list += Check(
            title = "Wi-Fi scanning",
            ok = isScanAlwaysAvailable(),
            detail = "Lets the phone spot saved networks while Wi-Fi itself is off.",
            required = true,
            actions = listOf("Turn on" to ::enableWifiScanning),
        )

        val power = getSystemService(PowerManager::class.java)
        list += Check(
            title = "Battery optimization off",
            ok = power.isIgnoringBatteryOptimizations(packageName),
            detail = "Stops Android from killing AutoWiFi in the background.",
            required = false,
            actions = listOf(
                "Allow" to {
                    open(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                    )
                },
            ),
        )

        if (DeviceSetup.isXiaomi) list += miuiChecks()
        return list
    }

    private fun miuiDebuggingCheck(): Check {
        val blocked = AutoSwitchService.status?.dataSwitchBlocked == true
        val verified = AutoSwitchService.status?.dataSwitchVerified == true
        val secure = shellState.miuiSecureDebugging
        val detail = "Xiaomi blocks Shizuku from switching mobile data until this is on. In Developer options, " +
            "turn on \"USB debugging (Security settings)\". Xiaomi only allows it with a SIM card in the phone " +
            "and a Mi account signed in."
        return Check(
            title = "USB debugging (Security settings)",
            ok = !blocked && (secure == true || verified),
            detail = when {
                blocked -> "Mobile data didn't switch. $detail"
                secure == null -> "$detail (Can't check until Shizuku is running.)"
                else -> detail
            },
            required = blocked || (secure == false && !verified),
            actions = listOf(
                "Open Developer options" to { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) },
            ),
        )
    }

    private fun miuiChecks(): List<Check> {
        val autostart = Check(
            title = "MIUI Autostart",
            ok = shellState.miuiAutostart == true,
            detail = "Lets AutoWiFi start again after a reboot. \"Set up everything automatically\" turns it " +
                "on; if it stays off, switch on AutoWiFi in the Autostart screen.",
            required = false,
            actions = listOf(
                "Open Autostart" to {
                    if (!tryOpen(DeviceSetup.miuiAutostartIntent())) open(DeviceSetup.appDetailsIntent(packageName))
                },
            ),
        )
        val battery = Check(
            title = "MIUI battery saver: No restrictions",
            ok = prefs.miuiBatteryConfirmed,
            detail = "MIUI closes background apps on its own. Set Battery saver to \"No restrictions\" for " +
                "AutoWiFi and for Shizuku, then tap Done. (Apps can't check this setting, so you confirm it.)",
            required = false,
            actions = listOf(
                "AutoWiFi" to { openMiuiBattery(packageName, getString(R.string.app_name)) },
                "Shizuku" to { openMiuiBattery(DeviceSetup.SHIZUKU_PACKAGE, "Shizuku") },
                "Done" to {
                    prefs.miuiBatteryConfirmed = true
                    refresh()
                },
            ),
        )
        return listOf(autostart, battery)
    }

    private fun openMiuiBattery(pkg: String, label: String) {
        if (!tryOpen(DeviceSetup.miuiBatteryIntent(pkg, label))) open(DeviceSetup.appDetailsIntent(pkg))
    }

    private fun controlCheck(): Check {
        val title = "Shizuku or root access"
        if (ShizukuShell.isReady()) return Check(title, true, "Using Shizuku.", required = true)
        if (prefs.useRoot) return Check(title, true, "Using root.", required = true)

        val useRoot = "Use root" to ::tryRoot
        if (ShizukuShell.isRunning()) {
            return Check(
                title, false,
                "Shizuku is running — allow AutoWiFi to use it.",
                required = true,
                actions = listOf("Allow Shizuku" to { ShizukuShell.requestPermission() }, useRoot),
            )
        }
        val shizukuLaunch = packageManager.getLaunchIntentForPackage(DeviceSetup.SHIZUKU_PACKAGE)
        val getShizuku = if (shizukuLaunch != null) {
            "Open Shizuku" to { open(shizukuLaunch) }
        } else {
            "Get Shizuku" to {
                open(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${DeviceSetup.SHIZUKU_PACKAGE}")))
            }
        }
        val xiaomiTip = if (DeviceSetup.isXiaomi) {
            " On Xiaomi: turn on Developer options by tapping \"MIUI version\" 7 times in Settings → About " +
                "phone. If Shizuku's box for the pairing code doesn't appear, set the notification style to " +
                "Android in Settings → Notifications & Control center."
        } else {
            ""
        }
        return Check(
            title, false,
            "Needed to switch mobile data (and Wi-Fi on Android 10+). Install Shizuku, connect to Wi-Fi and start " +
                "it with Wireless debugging (no computer needed on Android 11+), or use root.$xiaomiTip",
            required = true,
            actions = listOf(getShizuku, useRoot),
        )
    }

    // ---------------------------------------------------------------- Actions

    private fun onMasterSwitch(on: Boolean) {
        if (on && !hasLocationPermission()) {
            toast("Allow location access first, then turn this on.")
            requestLocationPermission()
            refresh()
            return
        }
        prefs.enabled = on
        if (on) {
            startService()
            if (Privileged.backend(this) == Backend.NONE) {
                toast("Without Shizuku or root AutoWiFi can only suggest when to switch.")
            }
        } else {
            AutoSwitchService.stop(this)
        }
        refresh()
    }

    private fun startService() {
        try {
            AutoSwitchService.start(this)
        } catch (e: Exception) {
            toast("Could not start: ${e.message}")
        }
    }

    private fun requestLocationPermission() {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
    }

    private fun runAutoSetup() {
        if (Privileged.backend(this) == Backend.NONE) {
            AlertDialog.Builder(this)
                .setTitle("Shizuku first")
                .setMessage(
                    "Automatic setup needs Shizuku (or root). Start Shizuku, then tap \"Allow Shizuku\" in the " +
                        "first Setup item, and try again.",
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        autoSetupRunning = true
        refresh()
        background.execute {
            val steps = DeviceSetup.runAutoSetup(applicationContext)
            val state = DeviceSetup.readShellState(applicationContext)
            ui.post {
                autoSetupRunning = false
                shellState = state
                shellStateAt = SystemClock.elapsedRealtime()
                if (isDestroyed) return@post
                if (hasLocationPermission()) {
                    prefs.enabled = true
                    startService()
                }
                showAutoSetupResult(steps)
                refresh()
            }
        }
    }

    private fun showAutoSetupResult(steps: List<Pair<String, Boolean>>) {
        val lines = steps.map { (label, ok) -> "${if (ok) "✓" else "✗"}  $label" }.toMutableList()
        if (prefs.enabled) lines += "✓  Auto-switching turned on"

        val todo = mutableListOf<String>()
        if (DeviceSetup.isXiaomi && shellState.miuiSecureDebugging != true &&
            AutoSwitchService.status?.dataSwitchVerified != true
        ) {
            todo += "Turn on \"USB debugging (Security settings)\" in Developer options (needs a SIM card and " +
                "a Mi account). Without it, Xiaomi won't let AutoWiFi switch mobile data."
        }
        if (DeviceSetup.isXiaomi && !prefs.miuiBatteryConfirmed) {
            todo += "Set MIUI Battery saver to \"No restrictions\" for AutoWiFi and Shizuku."
        }
        if (steps.any { !it.second }) todo += "Anything marked ✗ has a button in the Setup list."

        val message = lines.joinToString("\n") +
            if (todo.isEmpty()) "\n\nAll done!" else "\n\nStill to do by hand:\n" + todo.joinToString("\n") { "• $it" }
        AlertDialog.Builder(this)
            .setTitle("Automatic setup")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun tryRoot() {
        toast("Checking root access…")
        background.execute {
            val ok = RootShell.isAvailable()
            prefs.useRoot = ok
            ui.post {
                toast(if (ok) "Root access granted." else "Root access not available.")
                refresh()
            }
        }
    }

    private fun enableWifiScanning() {
        if (Privileged.backend(this) == Backend.NONE) {
            openScanSetting()
            return
        }
        background.execute {
            val ok = Privileged.exec(applicationContext, "settings put global wifi_scan_always_enabled 1")?.ok == true
            ui.post {
                if (!ok) openScanSetting()
                refresh()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun openScanSetting() {
        try {
            startActivity(Intent(WifiManager.ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE))
        } catch (e: ActivityNotFoundException) {
            toast("Turn on \"Wi-Fi scanning\" in Location settings.")
            open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    private fun importNetworks() {
        if (Privileged.backend(this) == Backend.NONE) {
            AlertDialog.Builder(this)
                .setTitle("Needs Shizuku or root")
                .setMessage(
                    "Android only shares the phone's saved Wi-Fi list with Shizuku or root. You can still use " +
                        "\"Add nearby…\", and every network you connect to is added automatically.",
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        toast("Reading saved networks…")
        background.execute {
            val result = Privileged.exec(applicationContext, "cmd wifi list-networks")
            val ssids = result?.takeIf { it.ok }?.output?.let { Ssids.parseListNetworks(it) }.orEmpty()
            val added = prefs.addSsids(ssids)
            ui.post {
                toast(
                    if (ssids.isEmpty()) {
                        "Couldn't read the phone's saved networks (needs Android 11+). Use \"Add nearby…\" instead."
                    } else {
                        "Found ${ssids.size} saved networks ($added new)."
                    },
                )
                refresh()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun addNearbyNetwork() {
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }
        wifi.startScan()
        val results = try {
            wifi.scanResults.orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
        val saved = prefs.savedSsids
        val ssids = results.sortedByDescending { it.level }
            .map { Ssids.normalize(it.SSID) }
            .filter { it.isNotEmpty() && it !in saved }
            .distinct()
        if (ssids.isEmpty()) {
            toast("No new networks found. Make sure Location and Wi-Fi scanning are on, then try again.")
            return
        }
        val checked = BooleanArray(ssids.size)
        AlertDialog.Builder(this)
            .setTitle("Add nearby networks")
            .setMultiChoiceItems(ssids.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Add") { _, _ ->
                prefs.addSsids(ssids.filterIndexed { i, _ -> checked[i] })
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addNetworkByName() {
        val input = EditText(this).apply {
            hint = "Network name (SSID)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Add network")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                if (prefs.addSsids(listOf(input.text.toString())) == 0) toast("Nothing added.")
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- Helpers

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    @Suppress("DEPRECATION")
    private fun isLocationOn(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getSystemService(LocationManager::class.java).isLocationEnabled
        }
        return Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, 0) !=
            Settings.Secure.LOCATION_MODE_OFF
    }

    @Suppress("DEPRECATION")
    private fun isScanAlwaysAvailable(): Boolean = wifi.isScanAlwaysAvailable

    /** Opens a screen if this phone has it. */
    private fun tryOpen(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("Couldn't open that screen on this phone.")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ACCENT = 0xFF1E88E5.toInt()
        const val GOOD = 0xFF43A047.toInt()
        const val BAD = 0xFFE53935.toInt()
    }
}
