# GalaxyBridge

GalaxyBridge syncs Samsung Health sleep stages from an Android phone to Apple Health on iPhone. The Android phone runs a local HTTP server, advertises itself with Bonjour/mDNS and BLE discovery, and the iPhone app writes the imported sleep stages into HealthKit.

This repository contains two apps:

- `android/`: Android Health Connect collector, local HTTP server, Bonjour/mDNS publisher, and BLE discovery advertiser.
- `ios/`: iOS HealthKit writer app with manual sync, background sync, and BLE-assisted server discovery.

## Project Status

The end-to-end prototype has been tested on a Samsung Android phone and an iPhone on the same LAN:

- Android reads Samsung Health sleep sessions through Health Connect.
- The Android app serves JSON locally over HTTP.
- Bonjour/NSD service discovery advertises the local server.
- BLE discovery advertises the local HTTP endpoint so the iOS app can auto-fill the Android IP and port without Bluetooth pairing.
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

It supports two discovery paths:

1. Bonjour/NSD over the local network.
2. BLE discovery for finding the HTTP endpoint when IP addresses change.

BLE is used only for discovery. It does not transfer sleep data and it does not broadcast the bridge token. After discovery, all sync traffic still uses the local HTTP API with the `X-Bridge-Token` header.

The Bonjour/NSD service is:

```text
GalaxyBridge._http._tcp.local
```

On networks where mDNS is allowed, clients can resolve the advertised service to the Android device hostname, for example:

```text
Android_xxxxx.local:8787
```

If mDNS is blocked by the router, guest Wi-Fi isolation, VPN rules, or phone power management, use the Android phone's LAN IP directly.

The BLE discovery service exposes a small JSON endpoint descriptor:

```json
{
  "version": 1,
  "name": "GalaxyBridge",
  "host": "192.168.x.x",
  "port": 8787,
  "path": "/sync/sleep",
  "health": "/health",
  "tokenHeader": "X-Bridge-Token"
}
```

The BLE service UUID is:

```text
8E4B7F20-0F74-4DB7-9C71-FB7A2D22C001
```

The endpoint characteristic UUID is:

```text
8E4B7F21-0F74-4DB7-9C71-FB7A2D22C001
```

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
3. The Android app advertises the endpoint through Bonjour/mDNS and BLE discovery.
4. The iOS app can auto-discover the Android endpoint, requests `/sync/sleep`, and writes new sleep stages into Apple Health.

The Android API currently returns only Samsung Health records with non-empty, valid stage data.

The iOS writer skips sleep records that were already imported by GalaxyBridge. This allows the Android server to return full history while the iPhone only writes new nights.

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

It also advertises a BLE GATT service for endpoint discovery. BLE discovery requires Android 12+ Bluetooth runtime permissions on newer devices:

```text
android.permission.BLUETOOTH_ADVERTISE
android.permission.BLUETOOTH_CONNECT
```

### Android Permissions

The Android app needs:

- Health Connect sleep read permission.
- Network access for the local HTTP server.
- Foreground service permission to keep the server alive.
- Wi-Fi/multicast permissions for local discovery.
- Bluetooth advertise/connect permissions for BLE endpoint discovery.
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

The iOS app includes an `Auto Discover` button. It scans for the Android BLE discovery service, reads the advertised endpoint, fills the Android IP and port, and then syncs over HTTP as usual. iOS will ask for Bluetooth permission the first time discovery runs; this is not Bluetooth pairing.

Free Apple developer signing expires after 7 days. A paid Apple Developer Program account is recommended for long-lived installs.

### Optional: Automatic Re-Signing With a Mac

If you use a free Apple developer account, the iOS app expires after 7 days. One practical workaround is to let a trusted Mac rebuild and reinstall the app automatically whenever the iPhone is reachable.

This does not bypass Apple's 7-day signing limit. It simply refreshes the Xcode-signed build before or after it expires.

Recommended setup:

1. Pair the iPhone with Xcode once.
2. Confirm the Mac can see the iPhone:

```bash
xcrun devicectl list devices
```

3. Build the app with Xcode command line tools:

```bash
xcodebuild \
  -project /path/to/GalaxyBridge/ios/sync.xcodeproj \
  -scheme sync \
  -configuration Release \
  -destination "generic/platform=iOS" \
  -derivedDataPath /tmp/GalaxyBridgeBuild \
  build
```

4. Install the built app to the paired iPhone:

```bash
xcrun devicectl device install app \
  --device YOUR_DEVICE_ID \
  /tmp/GalaxyBridgeBuild/Build/Products/Release-iphoneos/sync.app
```

5. Put those commands in a local script and run it with `launchd`, `cron`, or any scheduler you trust.

A safe automation should:

- Keep device IDs, Apple team IDs, and local paths outside the public repo.
- Check that the iPhone is `connected` before building.
- Avoid reinstalling too often, for example skip if the last successful install was less than 24 hours ago.
- Prefer Wi-Fi installation when `devicectl` can see the phone wirelessly, with USB as a fallback.
- Log failures so signing/profile issues are visible.

Do not commit your personal auto-sign script if it contains device identifiers, local filesystem paths, Apple team IDs, or other machine-specific details.

### iOS Permissions

The iOS app needs:

- HealthKit capability enabled in Xcode.
- `com.apple.developer.healthkit` entitlement in the signed app.
- User-granted HealthKit permission to write sleep analysis.
- Local network access to reach the Android device.
- Bluetooth permission if you want to use BLE-assisted discovery.

If the app reports a missing HealthKit entitlement, rebuild and install it through Xcode or a signing profile that preserves HealthKit. Generic sideload resigning may remove this entitlement.

## Error Handling

The apps include basic error reporting for the most common failure modes:

- Android server not running or unreachable.
- Missing Android Health Connect permission.
- Missing Android or iOS Bluetooth permission for BLE discovery.
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

If both IP addresses and Bonjour are inconvenient, use `Auto Discover` in the iOS app. BLE discovery should find the Android phone's current LAN IP and port, then normal sync continues over HTTP.

## Security

The local API is protected only by a shared token and is intended for trusted home LAN use. Do not expose it to the public internet without adding stronger authentication and TLS.

BLE discovery intentionally does not include the bridge token or health data. It only reveals the Android device's local HTTP endpoint. Treat the LAN and nearby Bluetooth environment as semi-trusted, and keep the token private.

## Known Limitations

- This is a local LAN sync tool, not a cloud service.
- HTTPS is not enabled by default.
- Bonjour discovery depends on the local router and Wi-Fi configuration.
- BLE discovery requires Bluetooth permissions on both phones and only discovers the endpoint; data transfer still requires both devices to be on the same reachable local network.
- BLE discovery is primarily a foreground convenience feature on iOS. Background sync should rely on the saved endpoint once discovery has populated it.
- iOS HealthKit writing requires a valid HealthKit entitlement.
- Free Apple developer signing expires after 7 days.
- The Android server currently focuses on Samsung Health staged sleep data.
