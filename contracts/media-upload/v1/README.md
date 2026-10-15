# Upload outcome and owner contract v1

This fixture describes the complete supported upload journey: open a local-file picker, stage one file if needed, enter at most one upload dispatch, retain a verified response, cancel or reconnect safely, and show an honest result. It does not add a transport, real-camera evidence, a remote checksum, automatic replay, or a whole-card identity scan.

## Outcome evidence

- `confirmed`: a validated successful response belongs to this exact upload attempt. Cancellation after that response does not erase it. A later library refresh failure is separate from upload completion.
- `not-sent`: upload dispatch was never entered, including cancelled local staging, source-read failure and rejected old picker results. A zero progress counter alone does not establish this outcome.
- `uncertain`: upload dispatch may have started and no valid same-attempt response is retained. Cancellation, timeout, network failure, invalid response, an empty refreshed list, or any matching filename/size in a list cannot prove whether the write happened.

Ordinary listing observations never promote an outcome. Even a newly observed ID with the same name and size is not a receipt for this operation. Explicitly starting another upload is a new attempt; no automatic retry, deletion of a matching item, or resume is authorized by this contract.

## Picker and publication ownership

Capture the connection/session generation and a single-use picker token before opening the OS/browser chooser. Admit a selected result only while that owner and token remain current, the feature is supported, and the media operation is available. Reconnect to the same model or camera still creates a different owner.

Every progress, response, refresh, error and finalizer must retain both its attempt token and owner. Old callbacks cannot write into a replacement connection or a newer attempt. A validated response can remain evidence for its original attempt without being presented as the new owner's result. Retained results, if shown after reconnect, must be clearly identified as belonging to the previous connection.

## Bridge receipt validation

The existing Bridge `POST /v1/session/{id}/media` returns HTTP 201 with a `MediaItem` after its backend's bounded verification. This exact response is stronger evidence than a separately requested list. Preserve that normal successful response and existing USB/PTP exact-handle verification.

For the shared HTTP receipt vectors:

1. Require HTTP 201 and provenance from this exact attempt. A status alone is insufficient; 200, 202, 204 and 206 are not this endpoint's completion contract.
2. Parse bounded strict JSON, reject duplicate keys and non-standard constants. Require a nonempty string `id`, a nonempty string `kind`, and a string `name`. Preserve the opaque ID without trimming, percent decoding or Unicode normalization. `kind` need not imply a particular media format.
3. Compare the returned name against the actual submitted basename using exact characters with only ASCII A-Z case folding. Non-ASCII code points remain exact; no whitespace trimming, locale case conversion, full Unicode case folding or normalization. This intentionally avoids platform-dependent identity comparisons. An unmatched name stays unconfirmed rather than guessing camera normalization.
4. `sizeBytes` must be an unsigned positive JSON integer token, within 1 through 4294967295, and exactly equal to the staged/submitted source length. Booleans, floating-point/exponent tokens, numeric strings and truncated values are not sizes.

Upload transports must refuse HTTP redirects and automatic mutation retries. In particular, HTTP 307/308 cannot authorize sending the selected file again or to a different target. The logical attempt marker is not sufficient if the HTTP library silently replays the body. A rejected redirect after the initial dispatch stays uncertain.

These checks establish an operation-bound backend acknowledgement, not cryptographic identity of stored bytes. A malformed or lost response after dispatch remains uncertain. They do not convert a local checksum into a camera-provided checksum.

## Corpus and execution boundaries

`upload-outcome-vectors.json` contains 48 outcome, 12 picker admission, 20 publication, 48 HTTP receipt and 14 full-journey cases. The `verified-receipt` journey event means the response has already passed the attempt-bound validator; a stale raw response rejected before validation is not that event.

The integrity Python test verifies corpus shape and fixed expectations only. Platform tests must call production helpers; transport/App/browser/Compose/XCUITest fixtures additionally exercise actual dispatch, cancellation and publication. A typed pure-Kotlin fixture adapter does not execute org.json or Android HTTP. Source/PSI checks do not establish Swift/Android compilation. Authored native or browser tests remain NOT_RUN until their platform actually executes them; simulator evidence does not establish physical-camera behavior.
