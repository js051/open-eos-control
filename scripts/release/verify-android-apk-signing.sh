#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "::error title=Android APK signing verification::$*" >&2
  exit 1
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  fail "Usage: $0 APK [EXPECTED_SHA256_FILE]"
fi

apk="$1"
expected_file="${2:-}"
[[ -f "$apk" ]] || fail "APK does not exist: $apk"

apksigner_path="$(command -v apksigner || true)"
if [[ -z "$apksigner_path" ]]; then
  sdk_roots=(
    "${ANDROID_HOME:-}"
    "${ANDROID_SDK_ROOT:-}"
    "/usr/local/lib/android/sdk"
  )
  candidates=()
  for sdk_root in "${sdk_roots[@]}"; do
    [[ -n "$sdk_root" && -d "$sdk_root/build-tools" ]] || continue
    while IFS= read -r candidate; do
      candidates+=("$candidate")
    done < <(find "$sdk_root/build-tools" -type f \( -name apksigner -o -name apksigner.bat \))
  done
  if [[ ${#candidates[@]} -gt 0 ]]; then
    apksigner_path="$(printf '%s\n' "${candidates[@]}" | sort -uV | tail -n 1)"
  fi
fi

[[ -n "$apksigner_path" && -f "$apksigner_path" ]] || fail "Unable to locate apksigner."

signer_output="$("$apksigner_path" verify --print-certs "$apk" 2>&1)" || fail "apksigner rejected the APK."
mapfile -t signer_digests < <(
  awk '
    BEGIN { IGNORECASE = 1 }
    /^[[:space:]]*Signer #[0-9]+ certificate SHA-256 digest:/ {
      digest = $NF
      gsub(":", "", digest)
      print tolower(digest)
    }
  ' <<< "$signer_output"
)

if [[ ${#signer_digests[@]} -ne 1 ]]; then
  echo "::group::apksigner certificate output" >&2
  printf '%s\n' "$signer_output" >&2
  echo "::endgroup::" >&2
  fail "Expected exactly one APK signer, found ${#signer_digests[@]}."
fi
actual="${signer_digests[0]}"
[[ "$actual" =~ ^[0-9a-f]{64}$ ]] || fail "apksigner returned an invalid SHA-256 certificate digest."

if [[ -z "$expected_file" ]]; then
  printf '%s\n' "$actual"
  exit 0
fi

[[ -f "$expected_file" ]] || fail "Expected fingerprint file does not exist: $expected_file"
expected="$(tr -d '[:space:]' < "$expected_file" | tr '[:upper:]' '[:lower:]')"
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || fail "Expected fingerprint is not a lowercase SHA-256 digest."
[[ "$actual" == "$expected" ]] || fail "APK signer $actual does not match pinned certificate $expected."

echo "Verified Android APK signer SHA-256: $actual"
