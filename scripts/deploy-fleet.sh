#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$ROOT_DIR"

if [[ -f .env.local ]]; then
  set -a
  source .env.local
  set +a
fi

TARGETS="${DROID_MESH_DEPLOY_TARGETS:-192.168.40.250:5555 192.168.40.59:5555 192.168.50.64:5555 192.168.50.124:5555 192.168.50.156:5555}"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"

export JAVA_HOME="${JAVA_HOME:-/home/cfoxga/android-tools/jdk-17.0.2}"
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> Building Release APK..."
./gradlew assembleRelease

if [[ ! -f "$APK_PATH" ]]; then
  echo "ERROR: APK not found at $APK_PATH" >&2
  exit 1
fi

echo "==> Deploying APK to fleet targets: $TARGETS"
for target in $TARGETS; do
  echo "--> Connecting to $target..."
  adb connect "$target" || true
  echo "--> Installing APK on $target..."
  if adb -s "$target" install -r -d -g "$APK_PATH"; then
    echo "--> Starting DroidMesh service on $target (background, no UI)..."
    adb -s "$target" shell am startservice -n com.cfox.droidmesh/.service.UpdaterForegroundService || true
    echo "--> OK: Deployed to $target"
  else
    echo "--> FAILED: Could not install on $target"
  fi
done

echo "==> Fleet deployment complete."
