# iOS focus/coordinate acknowledgement completeness

## Scope and correction

Source baseline: `27e6e3cbba2faaaebe48923124608f47dee5410d`.

Direct CCAPI `driveFocus`, `tapFocus`, and `clickWhiteBalance` reached the generic
2xx validator before returning acceptance or recording the operation as observed.
The earlier eight-route physical-release receipt policy did not include these
three operations. A complete HTTP exchange with status 206 or any Content-Range
header could therefore produce false command acceptance. This is an iOS source
finding; native reproduction was not run in this environment.

A separate acknowledgement-only predicate now rejects those partial replies for
exactly these advertised method/path suffix pairs:

- POST `/shooting/control/drivefocus`
- PUT `/shooting/liveview/afframeposition`
- POST `/shooting/liveview/clickwb`

The guard follows the existing non-2xx HTTP rejection. Any Content-Range field,
including an empty value or mixed-case field name, is incomplete evidence.
The original eight-route release policy stays unchanged. This does not extend a
transport deadline or body policy to these focus routes.

## Compatibility and non-goals

Complete 200 empty, plain-text, and JSON acknowledgements; 204 empty; and other
legacy 2xx statuses retain their prior acceptance contract. No JSON-success schema
is introduced. Existing non-2xx status, method, URL and body errors are preserved.

No change is made to request construction, JSON parsing, redirects, cancellation,
transport deadlines, replay handling, owner checks, Live View geometry, coordinate
conversion, media reads, simulator routes or feature advertisement. Existing
coordinate owner checks still run before dispatch and after response. The patch
adds no automatic command retry and makes no physical-focus/WB completion claim.

## Authored native coverage

`ios/OpenEOSCore/Tests/OpenEOSCoreTests/CCAPIFocusReceiptTests.swift` exercises the
production `CCAPIClient` through an injected `CameraHTTPTransport`. Coordinate
cases start the production JPEG Live View lifecycle and parse complete synthetic
Canon detail packets before sending their command. They assert the original
position payload (`1600`, `3200`) and focus-drive payload (`far3`).

- Five partial replies across all three commands: empty 206, equal-length JSON
  206, equal-length JSON 200 with Content-Range, empty mixed-case Content-Range
  on 200, and Content-Range on 204. Each must throw the exact incomplete-receipt
  error, send the command once, and leave its feature unobserved. Later status and
  capability reads cannot replay the command or turn it into observed success.
- Eight complete replies across all three commands: 200 empty/plain/JSON, 204
  empty, and legacy 201/plain, 202/JSON, 205/empty and 299/plain.
- 503 with Content-Range across all three commands preserves HTTP error details
  and leaves command observation unset.
- Exact three-pair policy checks cover two advertised versions, wrong methods,
  longer path suffixes, the distinct eight-route release policy, media/settings
  reads, recording and simulator routes.
- Missing coordinate geometry still prevents dispatch.

These are authored native assertions, not executed acceptance evidence.

## Executed verification

- Parent and independent audit source/scope reviews: PASS, no source-level blocker.
  Both kept native execution explicitly pending.
- `python -m unittest discover -s scripts/validation/tests -p 'test_ios*receipt_source.py' -v`:
  PASS, 9 supplementary source checks (4 new focus checks and 5 existing release
  receipt checks).
- `python -m unittest discover -s scripts/validation/tests -p 'test_ios_*_source.py' -v`:
  PASS, 100 supplementary source checks.
- `git diff --check`: PASS.
- Swift package compilation/runtime: NOT_RUN. `swift` is absent.
- Xcode/iOS Simulator/App/UI execution: NOT_RUN. `xcodebuild` is absent.
- Wire transport and physical camera/iPhone validation: NOT_RUN.

Source-check passes inspect source structure and authored fixture coverage only.
They are not Swift compilation, native runtime red/green, real networking, or
physical-camera proof. No toolchain was installed/downloaded and no new CI runner
was started for this correction.

## Delivery and release assessment

Implemented in an isolated local branch; native acceptance remains pending on an
already authorized supported macOS/Swift runner. This correction has patch-level
impact because it repairs false acceptance in existing commands. It does not
change the version or authorize main merge, release or physical support claims.
The parent integration owns the complete release-baseline and exact-SHA gate.
