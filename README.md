# GalaxyBridge

GalaxyBridge syncs Samsung Health sleep stages from an Android phone to Apple Health on iPhone.

This repository contains two apps:

- `android/`: Android Health Connect collector and local HTTP server.
- `ios/`: iOS HealthKit writer app with manual and background sync support.

## Project Status

The end-to-end prototype has been tested on a Samsung Android phone and an iPhone on the same LAN:

- Android reads Samsung Health sleep sessions through Health Connect.
- The Android app serves JSON locally over HTTP.
- Bonjour/NSD service discovery advertises the local server.
- The iOS app fetches sleep stages and writes them into Apple Health through HealthKit.

The current server response is intentionally conservative: it only returns Samsung Health records from `com.sec.android.app.shealth`, only nights with non-empty stage data, and only stages whose `end` is later than `start`.

This keeps Google Fit duplicates, empty nights, and invalid zero-length stages out of the iOS writer.

## Current Reliability Notes

### Android Health Connect Reading

The Android collector has been tested against Health Connect sleep data from Samsung Health. It reads `SleepSessionRecord` entries and their stage list, then normalizes the stage values before sending them to iOS.

The stage normalization currently maps:

- `AWAKE_IN_BED` to `AWAKE`
- `UNKNOWN` to `SLEEPING`
- `LIGHT`, `DEEP`, `REM`, `OUT_OF_BED`, and other supported values through as-is

The server also filters out invalid stages where `end <= start`.

### Local Network Discovery and Transport

The Android app runs a foreground service and exposes the sync API on port `8787`.

It registers a Bonjour/NSD service:

```text
GalaxyBridge._http._tcp.local
```

On networks where mDNS is allowed, clients can resolve the advertised service to the Android device hostname, for example:

```text
Android_xxxxx.local:8787
```

If mDNS is blocked by the router, guest Wi-Fi isolation, VPN rules, or phone power management, use the Android phone's LAN IP directly.

Transport is plain HTTP by default. This is intended for a trusted home LAN. HTTPS for `.local` names requires installing and trusting a local certificate authority on iOS, so it is not enabled by default.

### iOS HealthKit Writing

The iOS app requests HealthKit write access for sleep analysis and writes each Android sleep stage as an `HKCategorySample`.

Stage mapping:

- `1` -> `awake`
- `2` -> `asleepUnspecified`
- `3` -> `inBed`
- `4` -> `asleepCore`
- `5` -> `asleepDeep`
- `6` -> `asleepREM`

HealthKit entitlement is required. If the app is installed through a resigning tool that strips HealthKit entitlement, the app will show a missing entitlement permission error.

Free Apple developer signing expires after 7 days. For long-term use, use a paid Apple Developer Program account or set up your own automatic resign/install workflow on a trusted Mac.

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

### Android Permissions

The Android app needs:

- Health Connect sleep read permission.
- Network access for the local HTTP server.
- Foreground service permission to keep the server alive.
- Wi-Fi/multicast permissions for local discovery.
- Battery optimization exemption is recommended for stable background service behavior.

If Health Connect permissions are missing, open the app and grant the requested Health Connect permissions before starting sync.

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

### iOS Permissions

The iOS app needs:

- HealthKit capability enabled in Xcode.
- `com.apple.developer.healthkit` entitlement in the signed app.
- User-granted HealthKit permission to write sleep analysis.
- Local network access to reach the Android device.

If the app reports a missing HealthKit entitlement, rebuild and install it through Xcode or a signing profile that preserves HealthKit. Generic sideload resigning may remove this entitlement.

## Error Handling

The apps include basic error reporting for the most common failure modes:

- Android server not running or unreachable.
- Missing Android Health Connect permission.
- Invalid bridge token.
- JSON decode failures on iOS, including a response preview.
- HealthKit permission or entitlement errors.
- Invalid sleep stages are filtered server-side before they reach iOS.

For debugging, use:

```text
GET /health
GET /inventory
GET /sync/sleep?since=0
```

`/inventory` is useful for checking whether Health Connect has recent sleep data and which source package produced it.

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

## Known Limitations

- This is a local LAN sync tool, not a cloud service.
- HTTPS is not enabled by default.
- Bonjour discovery depends on the local router and Wi-Fi configuration.
- iOS HealthKit writing requires a valid HealthKit entitlement.
- Free Apple developer signing expires after 7 days.
- The Android server currently focuses on Samsung Health staged sleep data.
