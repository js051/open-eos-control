# Android shared device-failure repair candidate

## Baseline and boundaries

Both API 34 and API 36 at `575eb20d006072a133bbb8df6f7f95b3975a6985`
executed 459 cases: 437 passed, 22 failed, zero skipped/errors, with the same
22 failing names. Device run: https://github.com/js051/open-eos-control/actions/runs/39376896373
Host companion: https://github.com/js051/open-eos-control/actions/runs/39376896358

All four orientation, six OS SAF, and five download-history SAF cases already
passed on both APIs. This batch does not relabel them untested or rerun them.
The already-corrected invalid import is not part of this repair.

This is a source/host-validated candidate, not corrected-device acceptance.
No additional emulator/KVM action was launched. Existing assertions, test cases,
original JPEG corpus and RTP pixel tolerances remain required. Emulator/protocol
fixtures are never physical-camera evidence.

## Shared causes and candidate changes

1. Native RTP/LUT (one case): original limited-range BT.709 fixture is valid;
   the goldfish host converter produces BT.601 RGB despite BT.709 metadata.
   The independent formula reproduces all 12 observed channel bytes exactly.
   A shared production/acceptance decoder factory prefers an available reference
   AVC decoder only for the exact goldfish codec on goldfish/ranchu hardware.
   Other defaults are untouched; unavailable alternatives retain the default.
   See [full evidence and fallback semantics](android-native-rtp-color-2026-10-20.md).
   Corrected Surface/GPU pixels still require native execution.
2. JPEG entropy (two cases): platform decoding can conceal missing sequential
   MCUs. A bounded streaming Huffman/block-coverage gate now precedes native
   decoding. Original structure-only validation, exact byte count/digest,
   cancellation, native decode and progressive limitations remain intact.
   Four previously accepted premature-entropy vectors are rejected without
   changing the frozen corpus or positive images.
3. Non-JPEG Gallery (two cases): synthetic MP4 copies retained image kind.
   Fixtures now declare video kind. Production format-conflict rejection is
   unchanged and reciprocal image/video conflict assertions were added.
4. Bridge Bulb (four cases): fixtures used obsolete unowned Start/Stop endpoints
   and generic shutter state. They now exercise strict saved-owner receipts,
   non-replay, same-owner retries, no legacy fallback, and A-only cleanup before
   B reconnects. This exposed product defects: persistent owned release guidance
   disappeared on refresh, and explicit Live View refresh/restart could bypass
   the owned Bulb duty. Warning presentation and admission/publication guards
   now preserve that duty. Large-font fit/no-ellipsis/no-overlap checks remain,
   including after refresh. Background and Bulb-toggle safety Stops are tested
   explicitly instead of incorrectly classified as forbidden commands.
5. Recording journeys (three cases): choosing VIDEO now edits a draft; tests
   omitted Apply. They now assert the mode remains PHOTO until explicit Apply,
   then retain recording ACK/readback, delayed media review, playback and exact
   original-save assertions.
6. Connection recovery (one case): the Connect button reused Bridge-scan's
   teardown guard. It now permits the existing explicitly queued Connect during
   cleanup while still blocking an actual Bridge operation, unknown retirement
   and all Connect safety interlocks. No automatic connection is added.
7. Focus/Live View recovery (two cases): tests equated remote Stop obligation
   with locally healthy running pixels. Production now retires presentation
   immediately; assertions distinguish retired local presentation from retained
   remote Stop duty. Failed AF release must still prevent remote view Stop and
   every new camera command until the explicit release succeeds.
8. Scoped setting error (one case): a rejected ISO write now belongs to its
   typed receipt, not the generic error banner. The test verifies unknown write/
   unavailable readback, no replay, retained previous-camera warning, then an
   independent read-only status failure to exercise generic dismissal ordering.
9. Upload local failure (one case): upload's outcome catch discarded typed
   staging failures. Current-owner app errors now retain their localizable token
   and MEDIA ownership while dispatch/receipt alone still determines NOT_SENT,
   CONFIRMED or UNCERTAIN. No retry or inventory-based success was introduced.
10. Batch session retirement (one case): a cancellable HTTP operation may finish
    locally before a remote MockWebServer response gate opens. The test no longer
    requires that incidental delay. It still requires synchronous cancellation,
    no second A delete, no B mutations and no stale UI publication after
    releasing the old peer. Waiting for unfinished local work is checked only
    when cancellation has not already completed; this is not deterministic
    local-finalizer/join coverage. Production still explicitly joins retired
    work before admitting the replacement connection.
11. Lazy value selectors (two cases): options outside a LazyRow are not composed.
    Tests now scroll the correct value row before asserting visibility/clicking;
    selected raw values and explicit Apply remain checked.
12. Gallery callback ownership (one case): switching an offline fixture to
    connected mode did not advertise MEDIA_PREVIEW. The connected fixture now
    advertises only that tested capability; retired callbacks still produce zero
    effects and the replacement's own callback produces exactly one.
13. Dialog language boundary (one case): Compose Dialog creates its Android root
    from LocalView's context, so a caller-localized resource context is not the
    same as the new window context. The viewer explicitly carries its caller's
    resource context into its content. The unchanged test still requires both
    languages, untranslated provider detail and six deliberate retries.
    Reference: [AndroidX Dialog implementation](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/window/AndroidDialog.android.kt).

## Local evidence

The focused JPEG batch compiled intact sources and passed 26/26 JUnit cases,
including eight new adversarial tests. Frozen corpus results remain 71 structure,
34 MIME and six header vectors; strict admission keeps all 11 positive byte
vectors. Compile 8.90 seconds; total 9.51 seconds; 384 MiB heap and two CPUs.

The consolidated host batch compiled all 72 current data sources, including both
new decoder helpers, and the intact ViewModel/model dependency closure. Eight
classes passed 75/75 tests: decoder selection 6, Gallery formats 3, app-error tokens
4, connection recovery 8, recording review 38, shutter recovery 8, Live View
recovery 3, and upload contract 5. Resource symbols were regenerated from current
source. An initial harness compile omitted existing helpers; one initial upload
run omitted its frozen fixture classpath. Both harness omissions were corrected,
with failure logs preserved and no source/test weakening. Exact source hashes,
commands and accepted results are retained. This is not a full Compose/Android
APK build.
Native ImageDecoder/MediaStore, Compose semantics/layout, and the replacement
AVC Surface/GPU path still require the next explicitly approved device run.

## Release assessment

Impact: patch candidate for existing Android behavior. No version, main merge,
release or delivery-state promotion is performed. Required full host/CI and
native acceptance remain gates; no assertion/threshold was lowered to obtain a
passing result. Physical-camera validation remains pending.
