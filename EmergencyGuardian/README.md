# Emergency Guardian — Android

A native Android app (Java) that propagates SOS alerts between phones using Bluetooth Low Energy (BLE) mesh networking — no internet required.

---

## Features

- **BLE Mesh relay** — SOS packets hop between Android phones automatically when there is no internet
- **Internet fallback** — when the triggering device has internet, the SOS is uploaded directly to a server; no mesh broadcast is initiated
- **Press-and-hold SOS button** — 5-second countdown prevents accidental triggers
- **ESP32-C3 hardware integration** — connects to a physical emergency button over BLE GATT; alerts the app via BLE notification
- **Server-side deduplication** — prevents duplicate records if the same packet arrives via multiple mesh paths
- **Location tagging** — captures the last known GPS fix and embeds it in the SOS packet
- **Dark / Light mode** — respects system theme via Material DayNight

---

## Required Software

| Tool | Version | Purpose |
|---|---|---|
| Android Studio | Hedgehog (2023.1.1) or newer | IDE, AVD manager |
| Android Gradle Plugin | 8.2.2 | Build system |
| Gradle | 8.4 | Build tooling |
| JDK | 17 | Required by AGP 8.x |
| Android SDK Platform | API 34 | `compileSdk` and `targetSdk` |
| Android SDK Build-Tools | 34.x | `aapt`, `d8`, `apksigner` |
| Target device / emulator | API 29+ (Android 10+) | `minSdk 29` |

---

## Project Structure

```
EmergencyGuardian/
├── gradlew                     ← Unix build script (run this for CI)
├── gradlew.bat                 ← Windows build script
├── gradle/wrapper/
│   ├── gradle-wrapper.jar      ← Gradle bootstrap binary
│   └── gradle-wrapper.properties
├── build.gradle                ← Root build file (AGP plugin declaration)
├── settings.gradle             ← Project name + module list
├── local.properties            ← SDK path for local dev (NOT committed to git)
├── .gitignore
├── .github/workflows/
│   └── android.yml             ← GitHub Actions CI workflow
└── app/
    ├── build.gradle            ← Module dependencies + compile options
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/emergencyguardian/
        │   ├── MainActivity.java         — Screen, UI, lifecycle orchestration
        │   ├── SOSManager.java           — 5-second countdown + trigger callback
        │   ├── PermissionManager.java    — Runtime permission requests (BT, Location)
        │   ├── BluetoothManager.java     — Bluetooth adapter on/off state check
        │   ├── LocationManager.java      — GPS cache + passive update subscription
        │   ├── BLEManager.java           — ESP32-C3 BLE GATT client + reconnect
        │   ├── EmergencyMessenger.java   — Emergency message builder + dialog
        │   ├── NetworkManager.java       — Internet connectivity check + SOS log
        │   ├── ServerManager.java        — HTTP POST SOS upload + deduplication
        │   └── mesh/
        │       ├── MeshConstants.java    — Shared BLE UUIDs and config constants
        │       ├── SOSPacket.java        — Data model (JSON serialisation/deserialisation)
        │       ├── RelayManager.java     — Dedup cache, relay decision, internet check
        │       ├── DeviceDiscoveryManager.java  — BLE advertiser + scanner
        │       ├── ConnectionManager.java       — GATT server + GATT client pool
        │       └── MeshManager.java             — Public facade, wires all sub-managers
        └── res/
            ├── layout/activity_main.xml
            ├── values/
            │   ├── colors.xml
            │   ├── strings.xml
            │   └── themes.xml
            ├── values-night/themes.xml
            ├── drawable/
            │   ├── sos_button_background.xml
            │   ├── ic_launcher_background.xml
            │   └── ic_launcher_foreground.xml
            └── mipmap-anydpi-v26/
                ├── ic_launcher.xml
                └── ic_launcher_round.xml
```

---

## Build Instructions

### Option A — Android Studio (recommended for development)

1. Unzip the project and open the `EmergencyGuardian/` folder in Android Studio.
2. Let Gradle sync (downloads dependencies — requires internet, ~2 min on first run).
3. Connect a physical device (API 29+) or create an AVD via **Device Manager → Create Device**.
4. Press **▶ Run** (`Shift+F10`).

### Option B — Command line (Linux / macOS)

```bash
# From the EmergencyGuardian/ directory:
./gradlew assembleDebug
```

The debug APK is written to:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Option C — Command line (Windows)

```bat
gradlew.bat assembleDebug
```

### Prerequisites for command-line builds

- `JAVA_HOME` must point to a JDK 17 installation.
- `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) must point to an Android SDK installation that contains:
  - `platforms/android-34`
  - `build-tools/34.x.x`

  Alternatively, set `sdk.dir` in `local.properties`.

---

## APK Generation

After a successful `assembleDebug`, the unsigned debug APK is at:

```
app/build/outputs/apk/debug/app-debug.apk
```

To install directly onto a connected device:

```bash
./gradlew installDebug
```

To build a release APK (requires a signing keystore):

```bash
./gradlew assembleRelease
```

---

## GitHub Actions Compatibility

The project ships with a ready-to-use GitHub Actions workflow at `.github/workflows/android.yml`.

**What the workflow does:**

1. Checks out the repository.
2. Installs JDK 17 (Temurin).
3. Caches Gradle packages for fast subsequent runs.
4. Runs `./gradlew assembleDebug`.
5. Uploads the resulting `app-debug.apk` as a downloadable workflow artifact (retained 7 days).
6. Runs `./gradlew lintDebug` in a parallel job and uploads the lint HTML report.

**No configuration needed** — the `ubuntu-latest` GitHub Actions runner includes a pre-installed Android SDK with `ANDROID_HOME` set automatically. `local.properties` is not required in CI.

**Triggers:** Push or pull request to `main` / `master`.

---

## Mesh Architecture

### BLE roles — every Android phone plays both

```
┌──────────────────────────────────────────┐
│              Each Android phone          │
│                                          │
│  PERIPHERAL role          CENTRAL role   │
│  (GATT Server)            (GATT Client)  │
│  • Advertises SERVICE_UUID               │
│  • Accepts incoming WRITE requests       │
│  • NOTIFYs connected centrals            │
│                           Scans for UUID │
│                           Connects GATT  │
│                           WRITEs packets │
│                           Enables NOTIFY │
└──────────────────────────────────────────┘
```

### SOS relay flow

```
Phone A  →  Phone B  →  Phone C  →  Phone D (internet)
(origin)    relay        relay       uploads to server
```

Each relay node:
1. Receives an `SOSPacket` (JSON bytes via GATT WRITE or NOTIFY).
2. Passes it to `RelayManager.evaluate()`.
3. **New packet** → increments `hopCount`, appends own device ID to `relayHistory`, forwards to all GATT peers.
4. **Duplicate** (same `emergencyId` seen within 30-min TTL) → dropped silently.
5. **Hop limit reached** (> 20 hops) → dropped to prevent loops.
6. **Internet available** → calls `ServerManager.uploadSOS()` directly; stops relaying.

### SOS origin routing

```
onSOSTriggered()
    │
    ├─ Internet available? ──YES──► buildSOSPacket() + ServerManager.uploadSOS()
    │                               (mesh NOT started — direct upload only)
    │
    └─ No internet ──────────────► MeshManager.sendSOS()
                                   (broadcasts to BLE peers; relay nodes upload)
```

---

## BLE UUIDs

| Name | UUID |
|---|---|
| Mesh Service | `0000E701-0000-1000-8000-00805F9B34FB` |
| SOS Characteristic | `0000E702-0000-1000-8000-00805F9B34FB` |
| CCCD Descriptor | `00002902-0000-1000-8000-00805F9B34FB` |
| ESP32 Service | `00001000-0000-1000-8000-00805F9B34FB` |
| ESP32 Emergency Char | `00001001-0000-1000-8000-00805F9B34FB` |

> All UUIDs use the Bluetooth base UUID form `0000xxxx-0000-1000-8000-00805F9B34FB`.
> Only valid hexadecimal characters (0–9, A–F) are used in the 16-bit portion.

---

## SOSPacket Fields

| Field | Type | Description |
|---|---|---|
| `emergencyId` | String (UUID v4) | Unique ID — used for deduplication |
| `timestamp` | long (epoch ms) | Creation time at origin device |
| `originDeviceId` | String | Android ID of the triggering phone |
| `hopCount` | int | Number of relay hops (max 20) |
| `relayHistory` | `List<String>` | Ordered list of relay device IDs |
| `location` | String | Human-readable GPS string or `"Not Available"` |
| `latitude` | Double (nullable) | Raw GPS latitude at origin |
| `longitude` | Double (nullable) | Raw GPS longitude at origin |
| `sensorTriggered` | boolean | `true` if triggered by ESP32 hardware button |

Serialised as UTF-8 JSON; fits within the 512-byte negotiated GATT MTU.

---

## Key Logcat Tags

Filter in Logcat: `tag:MeshManager OR tag:DeviceDiscoveryMgr OR tag:ConnectionManager OR tag:RelayManager OR tag:SOS OR tag:BLEManager OR tag:ServerManager`

| Tag | Class | Example message |
|---|---|---|
| `MeshManager` | MeshManager | `Mesh started. Searching for nearby devices...` |
| `DeviceDiscoveryMgr` | DeviceDiscoveryManager | `Peer discovered: AA:BB:CC:DD:EE:FF (RSSI=-65 dBm)` |
| `ConnectionManager` | ConnectionManager | `Relaying SOS to peer AA:BB:CC:DD:EE:FF (148 bytes)` |
| `RelayManager` | RelayManager | `Duplicate SOS Ignored — id=<uuid>` |
| `RelayManager` | RelayManager | `Internet Available. SOS Ready To Upload. id=<uuid>` |
| `SOS` | SOSManager | `SOS Triggered` |
| `BLEManager` | BLEManager | `Connected to ESP32-C3. Discovering services...` |
| `ServerManager` | ServerManager | `SOS uploaded successfully. id=<uuid> HTTP 200` |

---

## Troubleshooting

### Gradle sync fails / "SDK not found"

- Open **File → Project Structure → SDK Location** in Android Studio and point it to your Android SDK installation.
- Alternatively, set `sdk.dir=/path/to/android/sdk` in `local.properties`.

### `./gradlew: Permission denied`

```bash
chmod +x gradlew
```

### BLE mesh does not discover peers

1. Ensure **Bluetooth is enabled** on both devices.
2. Ensure **Location Services are enabled** (required for BLE scanning on API 29–30).
3. Ensure the app has been granted **BLUETOOTH_SCAN**, **BLUETOOTH_CONNECT**, **BLUETOOTH_ADVERTISE**, and **ACCESS_FINE_LOCATION** at runtime.
4. Check Logcat for `DeviceDiscoveryMgr` messages.

### ESP32-C3 not connecting

1. Verify that the UUIDs in `BLEManager.java` (`ESP32_SERVICE_UUID`, `ESP32_EMERGENCY_CHAR_UUID`) match the values in your ESP32 firmware exactly.
2. Ensure the ESP32 is powered on and advertising.
3. Check Logcat for `BLEManager` messages.

### SOS not reaching server

1. Confirm the device has internet access (check `NetworkManager` Logcat tag).
2. Verify that `ServerManager.SERVER_URL` is set to your actual server endpoint (replace the placeholder).
3. Ensure the server returns a 2xx HTTP status code.

### Build fails: `UUID.fromString() IllegalArgumentException`

This is caused by invalid hex characters in UUID strings. All UUIDs in `MeshConstants.java` have been corrected to use only valid hex digits (`0–9`, `A–F`).

---

## Server Configuration

Update `ServerManager.SERVER_URL` before hardware testing:

```java
// app/src/main/java/com/emergencyguardian/ServerManager.java
public static final String SERVER_URL = "https://your-server.example.com/api/sos";
```

The server must:
- Accept `HTTP POST` with `Content-Type: application/json`
- Return `2xx` on success

---

## Not Yet Implemented (Future Sprints)

- SMS emergency contact relay
- Firebase Cloud Messaging / push notifications
- Background service (SOS relay without foregrounding the app)
- GPS continuous high-frequency tracking
- Release APK signing configuration
- Unit tests / instrumented tests
