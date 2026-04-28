# Embara — for TREK

> **Community / Unofficial** — This app is not affiliated with or endorsed by the TREK project. It is an independent, open-source mobile client for use with [TREK](https://github.com/mauriceboe/TREK).

Android app for [TREK](https://github.com/mauriceboe/TREK) — connect to any self-hosted TREK instance from your phone.

## What is this?

A thin WebView wrapper that lets you use your self-hosted TREK trip planner as a native Android app. Enter your server URL on first launch and you're done.

## Features

- **Any TREK instance** — enter your server URL on first launch
- **Full screen** — no browser chrome, feels native
- **Cookie persistence** — stay logged in across app restarts
- **Back button navigation** — navigates page history, not app exit
- **Offline handling** — shows a retry page instead of crashing
- **Settings** — change server, clear cache
- **Zero telemetry** — WebView metrics explicitly opted out
- **No third-party cookies** — only your TREK server's cookies

## Requirements

- Android 8.0+ (API 26)
- A running TREK instance accessible from your phone

## Install

Download the latest APK from [Releases](https://github.com/soult-io/trek-android/releases) and sideload it.

## Build from source

```bash
git clone https://github.com/soult-io/trek-android.git
cd trek-android
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## License

AGPL-3.0 — same as TREK.
