#!/usr/bin/env bash
set -euo pipefail

ANDROID_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
serial=""

usage() {
    cat <<'EOF'
Usage: android/tools/run-health-sync-experiment.sh [--serial DEVICE]

Builds and installs the debug app and instrumentation APK, grants the
Health Connect permissions required by HealthSyncExperimentTest, and runs it.
EOF
}

while (($#)); do
    case "$1" in
        --serial|-s)
            if (($# < 2)); then
                echo "Missing device serial after $1." >&2
                exit 2
            fi
            serial="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if ! command -v "$ADB" >/dev/null 2>&1; then
    echo "adb was not found; install Android platform-tools or set ADB." >&2
    exit 1
fi

mapfile -t connected_devices < <(
    "$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }'
)

if [[ -z "$serial" ]]; then
    if ((${#connected_devices[@]} == 0)); then
        echo "No authorized Android device is connected." >&2
        exit 1
    elif ((${#connected_devices[@]} > 1)); then
        printf 'More than one device is connected; select one with --serial:\n' >&2
        printf '  %s\n' "${connected_devices[@]}" >&2
        exit 1
    fi
    serial="${connected_devices[0]}"
fi

if ! "$ADB" -s "$serial" get-state >/dev/null 2>&1; then
    echo "Device '$serial' is not connected and authorized." >&2
    exit 1
fi

api_level="$("$ADB" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ ! "$api_level" =~ ^[0-9]+$ ]]; then
    echo "Could not determine the Android API level for '$serial'." >&2
    exit 1
fi

echo "Building debug app and instrumentation APKs..."
"$ANDROID_DIR/gradlew" -p "$ANDROID_DIR" \
    :app:assembleDebug :app:assembleDebugAndroidTest

app_apk="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
test_apk="$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [[ ! -f "$app_apk" || ! -f "$test_apk" ]]; then
    echo "Expected app or instrumentation APK was not produced by Gradle." >&2
    exit 1
fi

echo "Installing on $serial (Android API $api_level)..."
"$ADB" -s "$serial" install -r "$app_apk"
"$ADB" -s "$serial" install -r -t "$test_apk"

permissions=(
    android.permission.health.WRITE_EXERCISE
    android.permission.health.WRITE_POWER
    android.permission.health.WRITE_DISTANCE
    android.permission.health.WRITE_EXERCISE_ROUTE
)

if ((api_level >= 34)); then
    for permission in "${permissions[@]}"; do
        "$ADB" -s "$serial" shell pm grant io.radrower "$permission"
    done
else
    cat <<'EOF'
On Android 13 and earlier, Health Connect permissions must be granted in its UI.
On the phone, open Health Connect > App permissions > Radrower and allow:
Exercise, Distance, Power, and Exercise route.
EOF
    if [[ ! -t 0 ]]; then
        echo "Run this script from a terminal after granting those permissions." >&2
        exit 1
    fi
    read -r -p "Press Enter after granting all four permissions... "
fi

echo "Running HealthSyncExperimentTest on $serial..."
"$ADB" -s "$serial" shell am instrument -w -r \
    -e class io.radrower.HealthSyncExperimentTest \
    -e runExperiment true \
    io.radrower.test/androidx.test.runner.AndroidJUnitRunner
