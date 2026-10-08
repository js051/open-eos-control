#!/usr/bin/env bash
# Source-only/JVM checks. No Gradle invocation, downloads, emulator, or Android test execution.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"
: "${GRADLE_USER_HOME:?Source the existing EOS Android toolchain env.sh first}"
: "${ANDROID_HOME:?Source the existing EOS Android toolchain env.sh first}"
JAR="${1:?Pass the locally verified Compose 1.9.3 classes.jar}"
OUT="${2:-$ROOT/.codex/validation/measure-writer-probe}"
mkdir -p "$OUT/classes" "$OUT/runtime-classes" "$OUT/android-compiled" "$OUT/tmp"
cache() { find "$GRADLE_USER_HOME/caches/modules-2/files-2.1" -name "$1" -print -quit; }
AGP="$(cache gradle-api-8.6.1.jar)"; ASM="$(cache asm-9.6.jar)"
TREE="$(cache asm-tree-9.6.jar)"; ANALYSIS="$(cache asm-analysis-9.6.jar)"
ANN="$(cache annotation-jvm-1.9.1.jar)"; KOTLIN="$(cache kotlin-stdlib-2.2.21.jar)"
GRADLE_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v gradle)")")")"
for f in "$AGP" "$ASM" "$TREE" "$ANALYSIS" "$JAR"; do test -r "$f" || { echo "Missing cached input: $f" >&2; exit 1; }; done
printf '%s  %s\n' 8c8f3f1edb1916a437959b47b8d7416ec30261e24bd3205b71826365b4496a91 "$JAR" | sha256sum -c -
javac -J-XX:ActiveProcessorCount=2 -J-Xmx256m -cp "$AGP:$ASM:$GRADLE_HOME/lib/*" -d "$OUT/classes" android/buildSrc/src/main/java/dev/openeos/probe/*.java
javac -J-XX:ActiveProcessorCount=2 -J-Xmx256m -cp "$OUT/classes:$ASM:$TREE:$ANALYSIS" -d "$OUT/classes" scripts/diagnostics/measure-writer-probe/ProbeBytecodeSelfTest.java
java -XX:ActiveProcessorCount=2 -Xmx256m -Djava.io.tmpdir="$OUT/tmp" -cp "$OUT/classes:$ASM:$TREE:$ANALYSIS" ProbeBytecodeSelfTest "$JAR" "$OUT/MeasureAndLayoutDelegate.class" | tee "$OUT/bytecode-proof.txt"
javac -J-XX:ActiveProcessorCount=2 -J-Xmx256m -cp "$OUT/classes:$ASM" -d "$OUT/runtime-classes" scripts/diagnostics/measure-writer-probe/stubs/android/{os/Looper,util/Log}.java android/measureWriterProbe/java/dev/openeos/control/diagnostics/MeasureWriterRecorder.java scripts/diagnostics/measure-writer-probe/ProbeRuntimeSelfTest.java
java -XX:ActiveProcessorCount=2 -Xmx256m -Djava.io.tmpdir="$OUT/tmp" -cp "$OUT/runtime-classes:$OUT/classes:$ASM" ProbeRuntimeSelfTest | tee "$OUT/runtime-proof.txt"
unzip -p "$(cache runner-1.6.2.aar)" classes.jar > "$OUT/runner-classes.jar"
unzip -p "$(cache monitor-1.7.2.aar)" classes.jar > "$OUT/monitor-classes.jar"
unzip -p "$(cache storage-1.5.0.aar)" classes.jar > "$OUT/storage-classes.jar"
javac -J-XX:ActiveProcessorCount=2 -J-Xmx256m -cp "$ANDROID_HOME/platforms/android-35/android.jar:$OUT/runner-classes.jar:$OUT/monitor-classes.jar:$OUT/storage-classes.jar:$ANN:$KOTLIN" -d "$OUT/android-compiled" android/measureWriterProbe/java/dev/openeos/control/diagnostics/MeasureWriterRecorder.java android/measureWriterProbeAndroidTest/java/dev/openeos/control/diagnostics/*.java
javac -J-XX:ActiveProcessorCount=2 -J-Xmx256m -cp "$OUT/runtime-classes" -d "$OUT/runtime-classes" $(find scripts/diagnostics/measure-writer-probe/stubs -name '*.java') android/measureWriterProbeAndroidTest/java/dev/openeos/control/diagnostics/*.java scripts/diagnostics/measure-writer-probe/ProbeRunnerSelfTest.java scripts/diagnostics/measure-writer-probe/ProbeArtifactWriterSelfTest.java
java -XX:ActiveProcessorCount=2 -Xmx256m -Djava.io.tmpdir="$OUT/tmp" -cp "$OUT/runtime-classes" ProbeRunnerSelfTest | tee "$OUT/runner-proof.txt"
java -XX:ActiveProcessorCount=2 -Xmx256m -Djava.io.tmpdir="$OUT/tmp" -cp "$OUT/runtime-classes" ProbeArtifactWriterSelfTest | tee "$OUT/artifact-writer-proof.txt"
python3 -m unittest discover -s scripts/diagnostics/measure-writer-probe -p 'test_*.py' -v 2>&1 | tee "$OUT/evidence-verifier-tests.txt"
git diff --exit-code d8a514c23c3532846fce0f629da13c56089e3cf7 -- android/app/src/androidTest .github/workflows > "$OUT/unchanged-test-workflow.diff"
sha256sum "$JAR" "$OUT/MeasureAndLayoutDelegate.class" > "$OUT/class-hashes.txt"
printf 'PASS Java compiles against cached AGP/Android/test-runner APIs; original tests/workflows unchanged\n'
