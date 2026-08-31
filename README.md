# DroidMesh

A lightweight, native Android P2P mesh network, headless package updater, and local fleet manager in Kotlin. DroidMesh coordinates app installations, updates, and telemetry across unrooted Meta Portal smart displays, Onn Google TV streaming boxes, and Android IoT devices.

## Key Characteristics

- **Target SDK**: `29` (Android 10), **Min SDK**: `28` (Android 9 Pie), **Compile SDK**: `34`
- **Package**: `com.cfox.droidmesh`
- **P2P UDP Mesh Discovery**: Port `23250` (Multicast/Broadcast live peer directory)
- **Headless HTTP Trigger**: Embedded REST server listening on port `2325`
- **Auto-Install Engine**: Automated `AccessibilityService` interacting with AOSP & Google TV Package Installer dialogs, with support for native Android 12+ silent package installer flows
- **Storage Strategy**: Scoped-storage compliant downloads via `getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)` and `FileProvider`
- **Background Keep-Alive**: Persistent foreground service with battery optimization whitelist (`dumpsys deviceidle`)
- **Fleet Synchronization**: Decentralized peer telemetry, hardware profiles, ADB availability, and live app version reporting

---

## Architecture Overview

```
                        ┌──────────────────────────────────────────────┐
                        │              GitHub Releases API             │
                        │    (jxlarrea/kiosk-satellite/releases/latest)│
                        └──────────────────────┬───────────────────────┘
                                               │ (OkHttp / JSON)
                                               ▼
┌───────────────────────┐      ┌───────────────────────────────┐
│ External Trigger      │      │    LocalHttpServer (:2325)    │
│  • GET  /check        ├─────►│  • /check   • /update         │
│  • POST /update       │      │  • /status  • /logs           │
└───────────────────────┘      └───────────────┬───────────────┘
                                               │
                                               ▼
                               ┌───────────────────────────────┐
                               │       UpdateCoordinator       │
                               │  • Version Comparison         │
                               │  • ApkDownloader (Resumeable) │
                               └───────────────┬───────────────┘
                                               │ FileProvider URI
                                               ▼
                               ┌───────────────────────────────┐
                               │  PackageInstallerDispatcher   │
                               │  ACTION_VIEW / apkUri         │
                               └───────────────┬───────────────┘
                                               │
                                               ▼
                               ┌───────────────────────────────┐
                               │  System Package Installer     │
                               │  (com.android.packageinstaller│
                               └───────────────┬───────────────┘
                                               │
                                               ▼
┌──────────────────────────────────────────────────────────────┐
│ AutoInstallService (AccessibilityService)                    │
│  • Detects Installer window                                  │
│  • Clicks "Install" / "Update"                               │
│  • Clicks "Done" / "Open" on finish                          │
│  • Relaunches me.jxl.kiosk_satellite to foreground           │
└──────────────────────────────────────────────────────────────┘
```

---

## One-Time Device Provisioning via ADB

`scripts/kiosk-satellite-portal-setup.sh <serial>` runs this whole sequence in one shot against a
USB-connected Portal device: installs the latest release APK, applies the four grants below, and
launches the app. With auto-update on by default, that alone ends up with Kiosk Satellite itself
installed within one check cycle — no manual `/update` call required. The manual steps, for
reference or a non-standard install:

```bash
# 1. Install DroidMesh APK
adb install -r droid-mesh.apk

# 2. Grant Install Unknown Packages permission
adb shell appops set com.cfox.droidmesh REQUEST_INSTALL_PACKAGES allow

# 3. Enable the Accessibility Service permanently
adb shell settings put secure enabled_accessibility_services com.cfox.droidmesh/com.cfox.droidmesh.service.AutoInstallService
adb shell settings put secure accessibility_enabled 1

# 4. Whitelist from battery optimizations (Doze mode)
adb shell dumpsys deviceidle whitelist +com.cfox.droidmesh

# 5. Launch the app (starts the foreground service + HTTP server on :2325)
adb shell am start -n com.cfox.droidmesh/.MainActivity
```

---

## HTTP REST Endpoints (Port 2325)

### 1. Check Status & Target Version
```bash
curl -X GET http://<portal-ip>:2325/check
```
**Response:**
```json
{
  "status": "ok",
  "targetPackage": "me.jxl.kiosk_satellite",
  "installedVersionName": "0.14.0",
  "installedVersionCode": 140,
  "latestVersionTag": "v0.15.2",
  "updateAvailable": true,
  "release": {
    "name": "v0.15.2",
    "tagName": "v0.15.2",
    "publishedAt": "2026-08-28T14:20:10Z",
    "apkAssetUrl": "https://github.com/jxlarrea/kiosk-satellite/releases/download/v0.15.2/app-release.apk",
    "apkFileName": "app-release.apk",
    "apkSize": 34819200
  }
}
```

### 2. Trigger Update Immediately
```bash
curl -X POST http://<portal-ip>:2325/update
# Or force re-installing even if versions match:
curl -X POST "http://<portal-ip>:2325/update?force=true"
```

### 3. Query Service State & Health
```bash
curl -X GET http://<portal-ip>:2325/status
```

### 4. Query Portal Mesh & Discovered Peers
```bash
curl -X GET http://<portal-ip>:2325/mesh
# Or alias:
curl -X GET http://<portal-ip>:2325/peers
```
**Response:**
```json
{
  "status": "ok",
  "count": 2,
  "meshPort": 23250,
  "peers": [
    {
      "id": "820LCM04Z1106X07",
      "ip": "192.168.40.59",
      "port": 2325,
      "deviceModel": "Portal 10 (This Device)",
      "targetInstalled": true,
      "installedVersionName": "2026.8.105",
      "installedVersionCode": 196,
      "updaterState": "IDLE",
      "updaterMessage": "Installed version is already up to date",
      "lastSeenTimestamp": 1725111111111,
      "lastSeenSecondsAgo": 0,
      "isOnline": true,
      "isSelf": true
    },
    {
      "id": "819LCM02A080PZ18",
      "ip": "192.168.40.250",
      "port": 2325,
      "deviceModel": "Portal 10",
      "targetInstalled": true,
      "installedVersionName": "2026.8.105",
      "installedVersionCode": 196,
      "updaterState": "IDLE",
      "updaterMessage": "Installed version is already up to date",
      "lastSeenTimestamp": 1725111108000,
      "lastSeenSecondsAgo": 3,
      "isOnline": true,
      "isSelf": false
    }
  ]
}
```

### 5. Fetch Recent Logs
```bash
curl -X GET http://<portal-ip>:2325/logs
```

---

## Disclaimer & License

**DroidMesh** is an independent, open-source companion utility and fleet manager.

This project is licensed under the [MIT License](LICENSE).

