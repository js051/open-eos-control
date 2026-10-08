#!/usr/bin/env bash
set -euo pipefail

# Diagnostic branch only. Normal Gradle builds still default to probe off.
# Run from android/, as the unchanged full API34/API36 emulator jobs do.
helper="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../diagnostics/measure-writer-probe" && pwd)/package-ci-evidence.py"
pre_identity="$(python3 "$helper" prepare)"
read -r invocation pre_hash <<< "$pre_identity"
host="app/build/eos-measure-writer-host/$invocation"
printf 'EOS measure-writer pre-run invocation=%s pre_run_manifest_sha256=%s\n' "$invocation" "$pre_hash"

# Preserve the actual Gradle status independently of tee or evidence helper errors.
set +e
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.requireSimulator=true \
    -PeosMeasureWriterProbe=true "-PeosMeasureWriterInvocation=$invocation" \
    2>&1 | tee --output-error=warn "$host/build.log"
results=("${PIPESTATUS[@]}")
set -e
test_result="${results[0]}"
log_result="${results[1]}"
package_result=0
python3 "$helper" package --invocation "$invocation" \
    --pre-run-sha256 "$pre_hash" --gradle-exit "$test_result" --log-exit "$log_result" || package_result=$?

if (( test_result != 0 )); then
    # Existing bounded synthetic tag only; no other logcat data is collected.
    printf '\nEOS date-filter failure diagnostics (synthetic test tag only):\n' || true
    timeout 10s adb logcat -d -v brief 'EOSDateFilterDiag:V' '*:S' || true
    exit "$test_result"
fi
# Green instrumentation without complete current evidence is not a valid probe run.
exit "$package_result"
