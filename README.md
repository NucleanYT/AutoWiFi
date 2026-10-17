# AutoWiFi

An Android app that switches between Wi-Fi and mobile data for you:

- **A saved Wi-Fi network is in range** → turns Wi-Fi on.
- **Connected to Wi-Fi** → turns mobile data off (optional).
- **No saved Wi-Fi in range** → turns mobile data on, and turns Wi-Fi off after 60 seconds (optional).

It only turns Wi-Fi on for networks you've saved, so it won't switch on for random networks nearby.

## Requirements

Android 8.0 or newer. **No Shizuku or root needed.**

Android doesn't let apps switch mobile data directly (or Wi-Fi on Android 10+). So AutoWiFi comes with an **Accessibility service**. When it needs to switch, it opens Quick settings for a moment and taps the Wi-Fi or Mobile data tile for you. It only does this while the phone is unlocked. If the phone is locked, the switch happens as soon as you unlock it.

Optional: with **[Shizuku](https://shizuku.rikka.app/)** or **root**, AutoWiFi switches silently instead, and can set itself up with one tap.

## Install

1. On your phone, open **https://github.com/NucleanYT/AutoWiFi/releases/download/latest/AutoWiFi.apk**. The download starts right away.
2. Open the downloaded `AutoWiFi.apk` and tap **Install**. If Android asks, allow your browser or file manager to install unknown apps.

Each build is signed with a different temporary key. If Android won't install over an older build, uninstall the old one first.

## Setup (in the app)

Work down the **Setup** list until everything has a ✓:

1. **AutoWiFi service (Accessibility)**: tap **Turn on** and switch on AutoWiFi. On Xiaomi it's in Settings → Additional settings → Accessibility → Downloaded apps. If Android says "Restricted setting", open AutoWiFi's App info → ⋮ → **Allow restricted settings**, then try again.
2. Keep the **Wi-Fi** and **Mobile data** tiles on the first page of Quick settings.
3. **Location permission** (choose *Allow all the time*), **Location turned on**, **Wi-Fi scanning**.
4. **Battery optimization off** (recommended).
5. Add your networks under **Saved Wi-Fi networks**: **Add nearby…** or **Add by name…**. Any network you connect to is added automatically.
6. Turn on **Auto-switch between Wi-Fi and mobile data**.

With Shizuku or root, a **Set up everything automatically** button appears. It does steps 3–6 for you and can import the phone's saved networks.

### Xiaomi / Redmi / POCO (MIUI)

Xiaomi adds a few things no app can do for you:

- **Autostart**: switch on AutoWiFi in Settings → Apps → Manage apps → AutoWiFi → Autostart.
- Only if you use Shizuku: turn on **USB debugging (Security settings)** in Developer options. Xiaomi requires a SIM card and a Mi account for it. Without it, MIUI silently ignores Shizuku's mobile-data switching, so AutoWiFi falls back to the accessibility service.
- **Battery saver → No restrictions** for AutoWiFi (and Shizuku, if you use it). Apps can't read this setting, so the app opens the screen for you and you tap **Done** when it's set.

## Notes

- AutoWiFi checks every 30 seconds while the phone is awake. It also checks right away when the screen turns on or a Wi-Fi connection drops. When the phone is asleep in your pocket, the switch can wait until you next wake it.
- AutoWiFi doesn't touch anything while airplane mode is on.
- If you turn Wi-Fi or data on or off yourself, AutoWiFi will switch them back. Use **Turn off** in its notification to pause it.
- Hidden networks don't show up in scans, so AutoWiFi can't detect them.

## Building

```
./gradlew assembleDebug
```

Requires JDK 17 and the Android SDK (API 35).
