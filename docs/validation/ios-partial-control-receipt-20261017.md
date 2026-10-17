# iOS physical-control partial receipt boundary

## Scope and source evidence

Baseline: `fd29c906071e15e219a680c5d1e3287ef385b06c`.

The direct CCAPI generic response validator accepted every 2xx response. Raw Bulb press/release (`sendOwnedShutterCommand`), held AF (`performHeldAutofocusStart`), pulse AF/manual shutter (`performShortControl`), and ordinary JPEG/RTP Live View Start/Stop call this validator. Consequently, a complete transport exchange carrying HTTP 206 or a Content-Range field could previously acknowledge a partial control reply and clear its captured release/Stop obligation. The separate shooting-setting integrity check did not cover these raw control paths.

The patch adds a check after the existing non-2xx rejection, for exactly these existing method/path suffix pairs:

- POST `/shooting/control/shutterbutton`
- POST or PUT `/shooting/control/shutterbutton/manual`
- POST `/shooting/control/af`
- POST or DELETE `/shooting/liveview`
- POST `/shooting/liveview/rtp`
- DELETE `/shooting/liveview/multipart`

HTTP 206 or any Content-Range header, including an empty value and mixed-case field name, fails these control acknowledgements. Header lookup already normalizes names. AF operation selection is POST-only; a broader generic safe-stop allowlist is not evidence of AF PUT support.

Existing 200/204 empty, plain-body, and JSON acknowledgements remain accepted. This does not introduce JSON requirements, new routes, deadlines, redirects, retries, ownership rewrites, or broader media/read validation. Multipart reader DELETE remains best-effort cleanup; only the captured general Live View Stop can discharge the Live View obligation. REC and other endpoints retain their separate existing contracts.

## State transitions

- Bulb/held AF/short control register the captured release before dispatch. An incomplete press follows the existing compensation path. If compensation is also incomplete, release-required and release-unconfirmed remain set. An explicit later retry sends only the stored endpoint/method/payload; a full acknowledgement discharges it.
- A held AF Stop with a different UUID remains ignored. No partial response can clear that owner through the new gate.
- JPEG and RTP Start register the Stop obligation before dispatch. Incomplete Start plus incomplete compensation retains it and blocks another Start. Incomplete Stop after a full Start also retains the obligation, with local readers closed. Only a later full response to the original explicit Stop clears it.
- A direct still-capture partial acknowledgement fails without replaying capture.

## Verification status

- Independent source review: production guard and helper scope reviewed with no blocker; reviewer requested and received the full HTTP 200 JSON compatibility case. RTP fake session and factory signatures were checked against `CCAPIRTP.swift`; this is source inspection, not conformance compilation.

- `CCAPIPartialControlReceiptTests.swift`: AUTHORED, compile NOT_RUN, runtime NOT_RUN. This environment has neither `swift` nor `xcodebuild`; no toolchain was downloaded or installed and no CI or new runner was started.
- Native tests exercise all eight policy pairs; partial Bulb/AF/manual-control/JPEG/RTP receipts and exact stored recovery; wrong held-AF owner; no capture replay; 200/204 empty/plain/JSON compatibility; preserved non-2xx error; excluded GET/media route policy and unrelated discovery GET behavior. Existing image-read full-response validation is retained, not relaxed.
- `python -m unittest discover -s scripts/validation/tests -p test_ios_partial_control_receipt_source.py -v`: PASS (5 supplementary source guards). These checks inspect source and test wiring only; they are not Swift compilation, runtime red/green, network, or physical-camera evidence.
- Broader existing iOS supplementary source suite (`test_ios_*_source.py`): PASS (96 source checks, including the five above). This is not native acceptance.
- Physical camera, iPhone, native Live View rendering and EOS hardware validation: NOT_RUN.

## Delivery limits

Implemented locally only. Native compile/runtime acceptance remains pending on the project's supported Swift/macOS runner. No main merge, release, or physical-device support claim is implied. Release impact: patch-level correction to existing control acknowledgement behavior; release promotion remains the parent integration's decision and exact-SHA gate.
