#!/usr/bin/env bash
set -euo pipefail

# Run from android/, as the emulator jobs did before this diagnostic wrapper.
if ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.requireSimulator=true; then
    exit 0
else
    test_result=$?
fi

# A crashed instrumentation process may leave only "Logcat of last crash" in
# Gradle output. Export this bounded synthetic fixture's tag while the emulator
# is still alive; neither missing diagnostics nor adb failure replaces the test result.
printf '\nEOS date-filter failure diagnostics (synthetic test tag only):\n' || true
timeout 10s adb logcat -d -v brief 'EOSDateFilterDiag:V' '*:S' || true
exit "$test_result"
