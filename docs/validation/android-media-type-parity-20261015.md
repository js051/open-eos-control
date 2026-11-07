# Android media classification and strict automatic JPEG admission

Status: implemented locally, not PR-ready. Base: 2b0ed22583881789c8e337c84506ea26d2f3762a.

## Implemented

- A shared pure classifier combines literal filename extension, normalized kind and MIME without changing ID/name bytes. Photo, video, audio, other, unknown and conflict families are distinct.
- Photo/video filters, review intent, gallery counts and image/video routes use the shared family. ALL/recent preserve ordering; existing generic manual file delivery remains available. Unknown/conflicting rows do not enter thumbnail/image/video decoders.
- Existing transport/preview/decoder capability gates remain. MIME-only video classification does not create a new transport capability. Existing video-poster thumbnail paths remain supported.
- Exact MIME aliases are shared by CCAPI and Bridge. Null, empty, whitespace, malformed and explicit generic declarations are preserved; differing raw aliases are rejected. CCAPI keeps literal IDs/names; absent fresh MIME is absent and absent kind derives from the canonical requested filename.
- Automatic JPEG qualification requires exact kind=image, one literal basename with no slash/backslash and a nonempty prefix before the final dot, literal JPG/JPEG suffix, compatible photo metadata, and absent/empty or image/jpeg MIME. Fresh metadata must retain exact ID/name and qualify on both sides. Positive stable-size, two-baseline inventory, queue limits, cancellation/ownership and one-attempt transfer behavior remain.
- HTTP stream MIME contradictions are rejected before returning a playback handle. USB thumbnail/preview/stream paths recheck existing object metadata without introducing extra camera requests.

## Verification

- 30 actual Kotlin/JUnit tests pass: all 71 classification vectors, 15 fresh-metadata vectors, 4 review-selection vectors, 19 MIME-alias vectors, existing foreground policy tests, and actual runner tests including zero original requests for rejected fresh metadata.
- 44 actual Kotlin date/review-evidence regression tests pass. One old date fixture incorrectly declared VIDEO.MP4 as kind=image; only that fixture was corrected to kind=video. Date parsing, sorting and review-evidence logic were not rewritten.
- 80 combined source tests pass in scripts/ci/tests, scripts/validation/tests and scripts/contracts/tests. One existing PytestReturnNotNoneWarning remains for test_export_ios_ui_evidence.py::test_tree.
- 24 edited Kotlin/KTS files pass the existing PSI syntax parser. This is syntax-only, not Android type checking.
- The exact bridge-cwd Ruff command and git diff --check pass.
- Current media-type JVM and PSI hashes match the final sources. The earlier 44-test date/review and 80-test combined-source passes were retained without rerunning after the final automatic-JPEG basename-only guard and fixed-fixture count update; their earlier receipts remain unchanged. The final fixed contract SHA-256 is d24efab5bb73d5edc1d222395322f4295c4c31a3ae180d8b3d9293ce4e4b7d14.

## Explicitly NOT_RUN and retained gap

The org.json/resource loader, Gradle resource mapping, full Android SDK build, native adapter HTTP/USB tests, Compose/ViewModel UI, MediaStore/Files integration and physical-camera acceptance were not run. The bounded JVM harness substitutes fixed DATA only and compiles the real production logic, exact hashed model section and actual test bodies. Existing pinned Kotlin/JUnit tools were used, with a 768 MiB compiler bound; no install/download was attempted.

Existing original-byte validation is unchanged. These checks do not establish actual JPEG-original format validation, decoder compatibility or physical-camera reliability. No extra post-retention camera GET was added to local-owned Files delivery. No LUT implementation, CI configuration, credentials or security settings were changed.

## Evidence boundaries

Fixed vectors and production test bodies are committed with this candidate. Exact local
compiler/test/PSI output and source checksums are retained in the cumulative validation
receipt. A data-only fixture provider does not execute the Android org.json resource loader.

This corrective cohort has patch impact; cumulative development work has minor impact
relative to v0.12.0 Development Preview (baseline checked 2026-10-15T03:54:34Z).
No push, new CI run, merge, ready-state change or release is authorized by this document.
