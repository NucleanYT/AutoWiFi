# AutoWiFi

An Android app that switches between Wi-Fi and mobile data for you:

- **A saved Wi-Fi network is in range** → turns Wi-Fi on.
- **Connected to Wi-Fi** → turns mobile data off (optional).
- **No saved Wi-Fi in range** → turns mobile data on, and turns Wi-Fi off after 60 seconds (optional).

It only turns Wi-Fi on for networks you've saved, so it won't switch on for random networks nearby.

## Requirements

Android 8.0 or newer.

Android doesn't let normal apps switch mobile data, or Wi-Fi on Android 10 and newer. AutoWiFi gets around that with one of these:

- **[Shizuku](https://shizuku.rikka.app/)** (recommended, no root needed). Install it and start it. On Android 11 and newer you can start it with *Wireless debugging*, no computer needed. Without root, you have to start Shizuku again after every reboot.
- **Root**: tap *Use root* in the app.

Without either one, AutoWiFi still watches for saved networks and shows a notification you can tap to switch yourself.

## Install

1. Open the [Actions tab](../../actions), pick the latest successful **Build APK** run, and download the **AutoWiFi-apk** artifact.
2. Unzip it and install `app-debug.apk` on your phone (you'll need to allow installing apps from unknown sources).

Each build is signed with a different temporary key. If Android won't install over an older build, uninstall the old one first.

## Setup (in the app)

1. Start Shizuku. Then, in AutoWiFi's **Setup** list, tap **Allow Shizuku**.
2. Tap **Set up everything automatically**. It will:
   - grant AutoWiFi its location (all the time) and notification permissions,
   - turn Location and Wi-Fi scanning on,
   - turn battery optimization off,
   - on Xiaomi phones, turn on MIUI Autostart for AutoWiFi and Shizuku,
   - import your phone's saved Wi-Fi networks (Android 11+),
   - turn auto-switching on.
3. Do whatever the summary says is still left. Each item has a button in the Setup list.

You can still add networks by hand under **Saved Wi-Fi networks**: **Add nearby…** or **Add by name…**. Any network you connect to is added automatically.

### Xiaomi / Redmi / POCO (MIUI)

Xiaomi adds a few things no app can do for you:

- **Developer options**: go to Settings → About phone and tap **MIUI version** 7 times.
- **Pairing Shizuku**: if the box for typing the pairing code doesn't appear, set the notification style to Android in Settings → Notifications & Control center.
- **USB debugging (Security settings)**: turn this on in Developer options. Xiaomi requires a SIM card and a Mi account for it. Without it, MIUI silently ignores mobile-data switching. AutoWiFi checks for this and warns you if mobile data didn't actually switch.
- **Battery saver → No restrictions** for AutoWiFi and Shizuku. Apps can't read this setting, so the app opens the screen for you and you tap **Done** when it's set.

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
