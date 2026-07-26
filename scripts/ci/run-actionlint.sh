#!/usr/bin/env bash
set -euo pipefail

version="1.7.12"
archive="actionlint_${version}_linux_amd64.tar.gz"
sha256="8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8"
tool_dir="${RUNNER_TEMP:?RUNNER_TEMP is required}/actionlint-$version"

mkdir -p "$tool_dir"
curl --fail --location --silent --show-error \
  --output "$tool_dir/$archive" \
  "https://github.com/rhysd/actionlint/releases/download/v$version/$archive"
echo "$sha256  $tool_dir/$archive" | sha256sum --check --strict
tar -xzf "$tool_dir/$archive" -C "$tool_dir" actionlint
"$tool_dir/actionlint"
