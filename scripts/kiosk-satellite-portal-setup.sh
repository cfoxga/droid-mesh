#!/usr/bin/env bash
# One-shot bootstrap for a Meta Portal device: install Kiosk Satellite Updater,
# grant the minimum ADB items it needs to run, and launch it. Portal only —
# Echo Show LineageOS ports use Device Manager mode instead and never need
# this app.
#
# This script provisions ONLY the updater (com.cfox.droidmesh).
# It does not install or grant permissions for Kiosk Satellite itself
# (me.jxl.kiosk_satellite) — once the updater is running, trigger its own
# install/update via the HTTP API documented in README.md, or use
# ~/projects/homelab/scripts/kiosk-provision.sh for Kiosk Satellite's own
# ADB grants.
#
# Requires a USB-tethered device already visible in `adb devices` (network
# ADB isn't set up yet on a fresh Portal — that's the point of this script).
#
# Usage:
#   bash scripts/kiosk-satellite-portal-setup.sh <serial> [apk-path]
#
#   <serial>     ADB serial from `adb devices` (USB transport, not an IP).
#   [apk-path]   Optional local APK to install instead of fetching the
#                latest GitHub release for cfoxga/kiosk-satellite-updater.
set -euo pipefail

PKG="com.cfox.droidmesh"
REPO_OWNER="cfoxga"
REPO_NAME="kiosk-satellite-updater"

log() { echo "[kiosk-satellite-portal-setup] $*" >&2; }
die() { echo "[kiosk-satellite-portal-setup] ERROR: $*" >&2; exit 1; }

SERIAL="${1:-}"
APK_PATH="${2:-}"
[[ -n "$SERIAL" ]] || die "usage: $0 <serial> [apk-path]"

command -v adb >/dev/null || die "adb not found on PATH"

ADB=(adb -s "$SERIAL")

state="$("${ADB[@]}" get-state 2>&1)" || die "device $SERIAL not visible to adb — plug it in via USB and check 'adb devices'"
[[ "$state" == "device" ]] || die "device $SERIAL is in state '$state' (expected 'device') — if 'unauthorized', accept the RSA key prompt on the Portal's screen"
log "device $SERIAL connected (state: device)"

resolve_apk() {
  if [[ -n "$APK_PATH" ]]; then
    [[ -s "$APK_PATH" ]] || die "given APK not found/empty: $APK_PATH"
    echo "$APK_PATH"
    return
  fi

  local url out
  # /releases/latest excludes prereleases, and every release here is marked
  # prerelease (this project hasn't earned a stable tag yet) — so list all
  # releases and take the newest instead.
  url="$(curl -sf "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print([a["browser_download_url"] for a in d[0]["assets"] if a["name"].endswith(".apk")][0])' \
    2>/dev/null)" || die "no GitHub release found for $REPO_OWNER/$REPO_NAME — cut a release, or pass a local APK path as the 2nd argument"
  [[ -n "$url" ]] || die "latest release for $REPO_OWNER/$REPO_NAME has no .apk asset"

  out="/tmp/kiosk-satellite-updater-latest.apk"
  log "downloading latest release APK: $url"
  curl -sfL -o "$out" "$url" || die "APK download failed"
  [[ -s "$out" ]] || die "downloaded APK is empty: $out"
  echo "$out"
}

APK="$(resolve_apk)"
log "installing $APK on $SERIAL"
"${ADB[@]}" install -r "$APK" || die "adb install failed"

log "granting REQUEST_INSTALL_PACKAGES (lets the updater install Kiosk Satellite APKs without a prompt)"
"${ADB[@]}" shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow \
  || log "WARNING: appops grant failed — check manually"

log "enabling the auto-install accessibility service"
"${ADB[@]}" shell settings put secure enabled_accessibility_services "$PKG/$PKG.service.AutoInstallService"
"${ADB[@]}" shell settings put secure accessibility_enabled 1

log "battery-optimization exemption (Doze whitelist) so the foreground service survives screen-off"
"${ADB[@]}" shell dumpsys deviceidle whitelist "+$PKG" \
  || log "WARNING: deviceidle whitelist failed — check manually"

log "launching Kiosk Satellite Updater (starts the foreground service + HTTP trigger on :2325)"
"${ADB[@]}" shell am start -n "$PKG/.MainActivity" || die "am start failed"

sleep 2
if "${ADB[@]}" shell pidof "$PKG" >/dev/null 2>&1; then
  log "confirmed running on $SERIAL"
else
  log "WARNING: could not confirm a running process for $PKG — check the device screen"
fi

log "setup complete."
log "Auto-update is on by default, so Kiosk Satellite itself should install on its own within"
log "one check cycle. To force it immediately instead: find the device's IP (Settings > About,"
log "or 'adb -s $SERIAL shell ip addr') and curl -X POST http://<portal-ip>:2325/update"
log "Kiosk Satellite's own ADB permission grants are a separate step, not covered by this script."
