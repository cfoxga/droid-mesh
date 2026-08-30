# KioskUpdater

A standalone, single-purpose Android 10 updater utility in Kotlin targeting `me.jxl.kiosk_satellite` on unrooted display devices (Meta Portal 10", Portal Mini, Portal Plus, Echo LineageOS).

## Key Characteristics

- **Target SDK**: `29` (Android 10), **Min SDK**: `28` (Android 9 Pie), **Compile SDK**: `34`
- **Target App**: `me.jxl.kiosk_satellite`
- **Package**: `com.cfox.kioskupdater`
- **Headless HTTP Trigger**: Embedded server listening on port `2325`
- **Auto-Install Engine**: Automated `AccessibilityService` interacting with AOSP / Google Package Installer dialogs
- **Storage Strategy**: Scoped-storage compliant downloads via `getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)` and `FileProvider`

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

Run these commands once when installing KioskUpdater on the target Meta Portal or unrooted display device:

```bash
# 1. Install KioskUpdater APK
adb install -r KioskUpdater.apk

# 2. Grant Install Unknown Packages permission
adb shell appops set com.cfox.kioskupdater REQUEST_INSTALL_PACKAGES allow

# 3. Enable the Accessibility Service permanently
adb shell settings put secure enabled_accessibility_services com.cfox.kioskupdater/com.cfox.kioskupdater.service.AutoInstallService
adb shell settings put secure accessibility_enabled 1

# 4. Whitelist from battery optimizations (Doze mode)
adb shell dumpsys deviceidle whitelist +com.cfox.kioskupdater

# 5. Start the background Foreground Service (launches HTTP server on :2325)
adb shell am startservice -n com.cfox.kioskupdater/.service.UpdaterForegroundService
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

### 4. Fetch Recent Logs
```bash
curl -X GET http://<portal-ip>:2325/logs
```
