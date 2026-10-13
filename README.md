<p align="center">
  <img src="UPort_Brand_Kit/04_GitHub/GitHub_README_Hero_1600x500.png" alt="UPort — Smart security for your TV" width="100%" />
</p>

<p align="center">
  <img alt="Version 0.3.4" src="https://img.shields.io/badge/version-0.3.4-0860FA?style=flat-square" />
  <img alt="Android TV" src="https://img.shields.io/badge/Android%20TV-min%20SDK%2026-071427?style=flat-square" />
  <img alt="Google TV" src="https://img.shields.io/badge/Google%20TV-supported-0750DC?style=flat-square" />
  <img alt="Fire TV" src="https://img.shields.io/badge/Fire%20TV-supported-0860FA?style=flat-square" />
  <img alt="Landscape" src="https://img.shields.io/badge/layout-10--foot%20landscape-14284A?style=flat-square" />
</p>

<p align="center">
  <strong>Security, built for the big screen.</strong><br />
  A third-party TV app for viewing and controlling a compatible UniFi Protect, Access, and Network site.
</p>

<p align="center">
  <a href="#install-with-adb"><strong>Install with ADB</strong></a>
  &nbsp;·&nbsp;
  <a href="#permissions-over-adb"><strong>Permissions over ADB</strong></a>
  &nbsp;·&nbsp;
  <a href="#build-from-source"><strong>Build</strong></a>
  &nbsp;·&nbsp;
  <a href="https://github.com/zodyking/UPort/issues/new?template=bug_report.yml"><strong>Report a bug</strong></a>
  &nbsp;·&nbsp;
  <a href="https://github.com/zodyking/UPort/issues/new?template=feature_request.yml"><strong>Request a feature</strong></a>
</p>

---

## What it does

UPort is a leanback app for one site. It opens in demo mode so the interface can be explored before a console is connected. Switch to live in Settings and store the console host and API keys on the TV. Keys stay in the Android Keystore.

| | |
| --- | --- |
| **Live Feed** | Watch cameras on the TV, including a single-camera view. |
| **Playback** | Browse recorded footage from the NVR. |
| **Access** | See access events and unlock a door after a second confirmation. |
| **Network** | Check network status for the site. |
| **Tailscale** | Reach the site remotely with an on-device VPN when you choose to. |
| **Alerts** | Doorbell and camera popups over whatever is playing, with spoken headlines and camera-speaker announcements. |
| **Screensaver** | Camera wall after the remote sits idle, or from the switch at the bottom of Live Feed. |
| **This TV** | Per-camera popup rules, quiet hours, size, corner, and picture. |
| **Your stream** | When Protect has no RTSP address, paste one on the TV or from a phone on the same Wi-Fi. It stays on the TV. |

Android TV and Google TV can grant “display over other apps” from Settings. Fire TV has no screen for that permission, so corner alerts there use the **UPort alerts** accessibility service. The service is used to place the alert window. It does not read the picture on screen.

## Download

Current sideload build: **0.3.4** (`versionCode` 19), package `app.apt443`.

<p align="center">
  <a href="https://github.com/zodyking/UPort/raw/refs/heads/main/apk/UPort.apk">
    <img alt="Download UPort 0.3.4 APK" src="https://img.shields.io/badge/Download-UPort%200.3.4%20APK-0860FA?style=for-the-badge" />
  </a>
</p>

The file in this repository is [`apk/UPort.apk`](apk/UPort.apk). It is the 0.3.4 sideload build for manual install. It is not a Google Play or Amazon Appstore listing.

This copy is signed with the Android debug certificate. SHA-256: `ef60592035fc38fd6a6ce71d93de69ad7aa04ba8c0e778fad324e754d16a0043`. Confirm a download with `apksigner verify --print-certs` before you install it. If a previous UPort install used a different key, uninstall that copy first.

## Install with ADB

You need a computer with [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) (`adb` on your PATH) and the TV on the same network.

### 1. Turn on network debugging

**Amazon Fire TV**

1. Settings → My Fire TV → About → click the device name until Developer options unlock.
2. Settings → My Fire TV → Developer options → **ADB debugging** → On.
3. If a network debugging toggle is present, turn that on too.

**Google TV / Android TV**

1. Settings → System → About → click **Android TV OS build** seven times.
2. Settings → System → Developer options → **USB debugging** → On.
3. Turn on **Wireless debugging** or **Network debugging** when the menu offers it.

Note the TV’s IPv4 address (Settings → Network). UPort’s alert screen can also show the address to use.

### 2. Connect and install

Replace `192.168.1.50` with the TV address.

```bash
adb connect 192.168.1.50:5555
adb devices
adb install -r apk/UPort.apk
adb shell am start -n app.apt443/.MainActivity
```

`adb devices` should list the TV as `device`, not `unauthorized`. If it says unauthorized, accept the debugging prompt on the TV and run `adb devices` again.

`adb install -r` replaces an existing UPort install and keeps its data when the signing key matches. A signature mismatch means uninstall the old copy first:

```bash
adb uninstall app.apt443
adb install apk/UPort.apk
```

These commands are the same in PowerShell, Command Prompt, and a Unix shell. Download the APK first if you are not in a clone of this repo, then pass that file path to `adb install -r`.

## Permissions over ADB

Install the APK before these commands. They target package `app.apt443` and survive reboot. Run them again after an uninstall and reinstall.

Connect once:

```bash
adb connect 192.168.1.50:5555
```

### Alerts over other apps

Corner popups need one of these paths.

**Draw over other apps** (Android TV and Google TV). Many TV builds hide the Settings toggle, so ADB is the reliable grant:

```bash
adb shell appops set app.apt443 SYSTEM_ALERT_WINDOW allow
```

**UPort alerts accessibility service** (required on Fire TV, and the fallback everywhere else):

```bash
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure enabled_accessibility_services app.apt443/app.apt443.alert.Apt443AccessibilityService
```

That second command **replaces** the list of enabled accessibility services. If another service must stay on, read the current value and join both with a colon:

```bash
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services com.example/.OtherService:app.apt443/app.apt443.alert.Apt443AccessibilityService
```

Check what stuck:

```bash
adb shell appops get app.apt443 SYSTEM_ALERT_WINDOW
adb shell settings get secure enabled_accessibility_services
```

### Notifications and full-screen alerts

On Android 13 and newer, doorbell alerts also need notification permission. On Android 14 and newer, a full-screen alert needs the full-screen intent app-op.

```bash
adb shell pm grant app.apt443 android.permission.POST_NOTIFICATIONS
adb shell appops set app.apt443 USE_FULL_SCREEN_INTENT allow
```

`pm grant` for `POST_NOTIFICATIONS` only applies on API 33+. Older TVs already allow notifications.

### Keep awake and the camera screensaver

Writing the sleep timer and screensaver component needs secure settings. Changing the screen-off timeout needs the write-settings app-op. Only ADB can grant these. If they are missing, UPort keeps running and logs the failure.

```bash
adb shell pm grant app.apt443 android.permission.WRITE_SECURE_SETTINGS
adb shell appops set app.apt443 WRITE_SETTINGS allow
```

### One block you can paste

```bash
adb connect 192.168.1.50:5555
adb install -r apk/UPort.apk
adb shell appops set app.apt443 SYSTEM_ALERT_WINDOW allow
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure enabled_accessibility_services app.apt443/app.apt443.alert.Apt443AccessibilityService
adb shell pm grant app.apt443 android.permission.POST_NOTIFICATIONS
adb shell appops set app.apt443 USE_FULL_SCREEN_INTENT allow
adb shell pm grant app.apt443 android.permission.WRITE_SECURE_SETTINGS
adb shell appops set app.apt443 WRITE_SETTINGS allow
adb shell am start -n app.apt443/.MainActivity
```

Then open UPort → Settings and use **Test alert**. A corner card over another app means the grant worked.

## Build from source

Open this folder in current Android Studio with Android SDK 37.

| | |
| --- | --- |
| Application id | `app.apt443` |
| Compile / target / min SDK | 37 / 36 / 26 |
| Version | 0.3.4 (19) |
| UI | Jetpack Compose for TV |
| JDK | 17 or newer to compile |

1. Let Android Studio write `local.properties` with your SDK path. That file is not in git.
2. Release signing reads `keystore.properties` at the repo root when the file exists. That file and the keystore are not in git. Without them, debug builds still run.
3. Run the `app` configuration on an Android TV emulator or a TV. The launcher intent is `LEANBACK_LAUNCHER`.

From a terminal, with the SDK installed:

```bash
./gradlew :app:assembleDebug
```

On Windows:

```bat
gradlew.bat :app:assembleDebug
```

The debug APK is written under `app/build/outputs/apk/debug/`. A signed release build is:

```bash
./gradlew :app:assembleRelease
```

## Project map

```
app/                 Android TV application
app/libs/            Bundled Tailscale library used by the remote VPN
apk/                 Sideload APK published with this repo
gradle/              Version catalog and wrapper
UPort_Brand_Kit/     Logo masters, store art, and palette
```

Brand colors used in the product and in this README: signal blue `#0860FA`, electric dark `#0750DC`, sky `#68C7FF`, midnight `#071427`, night surface `#0A1D3B`, panel `#14284A`, ice `#EAF3FF`. Wordmarks are the traced SVG masters in `UPort_Brand_Kit/01_Logo_Masters/`. Do not redraw the logo.

## Contributing

Bugs and ideas go through the issue forms:

- [Bug report](https://github.com/zodyking/UPort/issues/new?template=bug_report.yml)
- [Feature request](https://github.com/zodyking/UPort/issues/new?template=feature_request.yml)

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Security reports go through [SECURITY.md](SECURITY.md), not a public issue.

## Notice

UPort is independently developed third-party software. It is not affiliated with, endorsed by, or sponsored by Ubiquiti Inc. or Amazon. UniFi, Protect, and related names are used only to identify compatibility. Brand files in `UPort_Brand_Kit/` are part of the UPort identity and are separate from those trademarks.
