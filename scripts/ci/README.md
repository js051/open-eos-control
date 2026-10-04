# Android Lint registry compatibility

Compose 1.9 requires Lint 8.8.2 or newer. The project pins the documented
standalone Lint override in `android/gradle.properties`, while keeping AGP,
Gradle, and JDK unchanged. See the [official compatibility guidance](https://developer.android.com/develop/ui/compose/tooling/lint).

Run both checks, from the repository root, with the existing Android SDK/JDK:

```sh
bash android/gradlew -p android :app:lintDebug
python3 scripts/ci/verify_android_lint.py
```

The first command runs unrestricted product Lint. `ObsoleteLintCustomCheck`
and `LintError` are fatal, so skipped or crashed registries cannot silently
turn that gate green. Other checks retain their existing severities.

The second command proves execution with synthetic violations from four
registries: `UnusedBoxWithConstraintsScope` (foundation),
`UnrememberedMutableState` (runtime), `ModifierFactoryExtensionFunction` (UI),
and `NullSafeMutableLiveData` (lifecycle). It requires all four issue IDs at
the exact generated source path and positive line numbers, rejects registry
failures, and requires Gradle's expected error exit. It does not suppress or
restrict product checks, and does not replace the first command.

The canary adds its generated source only through an invocation-specific
Gradle init script, which refuses tasks other than `:app:lintDebug`. It places
all app outputs in a separate temporary build directory and removes both
sources and outputs when done. Normal app sources, build outputs, settings,
and future invocations are not modified. No canary APK is built. Evidence
is retained in `android/build/lint-registry-canary/`.
The script fingerprints paths and file contents under `android/app/src`
before and after Gradle, failing on additions, removals, renames, or content
changes. Symlinks are rejected without reading their targets. The verified
source fingerprint is included in the evidence.

The script supports `--offline` after dependencies are cached, and
`--gradle-runner /path/to/existing-helper.sh` for an existing environment-specific
Gradle shell helper. It does not install or alter toolchains. Its verifier
tests run with `python3 -m unittest discover -s scripts/ci/tests -p 'test_*.py'`.
