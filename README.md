# GalaxyBridge

GalaxyBridge syncs Samsung Health sleep stages from an Android phone to Apple Health on iPhone.

This repository contains two apps:

- `android/`: Android Health Connect collector and local HTTP server.
- `ios/`: iOS HealthKit writer app with manual and background sync support.

## How It Works

1. The Android app reads Samsung Health sleep sessions through Health Connect.
2. It exposes a local API on port `8787`.
3. The iOS app requests `/sync/sleep` and writes sleep stages into Apple Health.

The Android API currently returns only Samsung Health records with non-empty, valid stage data.

## Android

Open `android/` in Android Studio.

Before building, set your own bridge token in:

`android/app/src/main/java/com/example/galaxybridge/BridgeForegroundService.kt`

The default open-source placeholder is:

```text
change-me
```

The server endpoints are:

```text
GET /health
GET /sync/sleep?since=0
POST /sync/ack?upto=...
GET /inventory
```

Requests except `/health` require:

```text
X-Bridge-Token: your-token
```

The app also advertises Bonjour/NSD service discovery as:

```text
GalaxyBridge._http._tcp.local
```

## iOS

Open `ios/sync.xcodeproj` in Xcode.

Before running:

- Set your own Apple development team.
- Use your own bundle identifier.
- Enable the HealthKit capability.
- Match the bridge token in `ios/sync/SleepSyncManager.swift`.

The default open-source token placeholder is:

```text
change-me
```

Free Apple developer signing expires after 7 days. A paid Apple Developer Program account is recommended for long-lived installs.

## Local Network Notes

If Bonjour resolution works, the iOS app can use a `.local` Android hostname such as:

```text
http://Android_xxxxx.local:8787
```

If Bonjour is blocked by the router or Wi-Fi isolation, use the Android phone's LAN IP:

```text
http://192.168.x.x:8787
```

## Security

The local API is protected only by a shared token and is intended for trusted home LAN use. Do not expose it to the public internet without adding stronger authentication and TLS.

