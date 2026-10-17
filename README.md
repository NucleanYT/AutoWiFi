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

Work down the **Setup** list until every item has a ✓:

1. **Location permission**: Android only shows nearby Wi-Fi networks to apps with location access. Choose *Allow all the time* so AutoWiFi can restart itself after a reboot.
2. **Location turned on** and **Wi-Fi scanning** turned on, so the phone can find networks while Wi-Fi is off.
3. **Shizuku or root access**
4. **Battery optimization off** (recommended)

Then add your networks under **Saved Wi-Fi networks**:

- **Import from phone** copies the phone's saved Wi-Fi list (needs Shizuku/root, Android 11+).
- **Add nearby…** picks from the networks around you.
- Any network you connect to is added automatically.

Last, turn on **Auto-switch between Wi-Fi and mobile data**.

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
