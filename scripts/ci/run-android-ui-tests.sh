#!/usr/bin/env bash
set -euo pipefail

api_level="${ANDROID_API_LEVEL:-34}"
architecture="${ANDROID_ARCH:-x86_64}"
avd_name="${ANDROID_AVD_NAME:-open_eos_ci}"
emulator_port="${ANDROID_EMULATOR_PORT:-5554}"
serial="emulator-${emulator_port}"
system_image="system-images;android-${api_level};default;${architecture}"
log_file="${RUNNER_TEMP:-/tmp}/open-eos-android-emulator.log"

: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"

sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
avdmanager="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
emulator="$ANDROID_HOME/emulator/emulator"
adb="$ANDROID_HOME/platform-tools/adb"

for tool in "$sdkmanager" "$avdmanager" "$emulator" "$adb"; do
    if [[ ! -x "$tool" ]]; then
        echo "Required Android SDK tool is not executable: $tool" >&2
        exit 1
    fi
done

"$sdkmanager" --install "platform-tools" "emulator" "$system_image"
echo no | "$avdmanager" create avd \
    --force \
    --name "$avd_name" \
    --package "$system_image" \
    --device pixel_5

"$adb" start-server
"$emulator" \
    -port "$emulator_port" \
    -avd "$avd_name" \
    -accel on \
    -no-window \
    -no-snapshot \
    -noaudio \
    -no-boot-anim \
    -no-metrics \
    -gpu swiftshader_indirect \
    -camera-back none \
    >"$log_file" 2>&1 &
emulator_pid=$!

cleanup() {
    "$adb" -s "$serial" emu kill >/dev/null 2>&1 || true
    if kill -0 "$emulator_pid" >/dev/null 2>&1; then
        kill "$emulator_pid" >/dev/null 2>&1 || true
    fi
    wait "$emulator_pid" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

deadline=$((SECONDS + 600))
boot_completed=""
while (( SECONDS < deadline )); do
    if ! kill -0 "$emulator_pid" >/dev/null 2>&1; then
        echo "Android Emulator exited before boot completed." >&2
        tail -n 200 "$log_file" >&2 || true
        exit 1
    fi

    state=$("$adb" -s "$serial" get-state 2>/dev/null || true)
    if [[ "$state" == "device" ]]; then
        boot_completed=$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
        if [[ "$boot_completed" == "1" ]]; then
            break
        fi
    fi
    sleep 2
done

if [[ "$boot_completed" != "1" ]]; then
    echo "Android Emulator did not finish booting within 600 seconds." >&2
    "$adb" devices -l >&2 || true
    tail -n 200 "$log_file" >&2 || true
    exit 1
fi

"$adb" -s "$serial" shell input keyevent 82
"$adb" -s "$serial" shell settings put global window_animation_scale 0
"$adb" -s "$serial" shell settings put global transition_animation_scale 0
"$adb" -s "$serial" shell settings put global animator_duration_scale 0

./gradlew :app:connectedDebugAndroidTest
