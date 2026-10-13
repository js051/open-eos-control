# iOS Bridge progressive media browsing

Implementation status: source and focused fixtures prepared. Native Swift build, XCTest, app UI tests, and physical-camera checks are **NOT_RUN** in the local preparation environment; Swift is unavailable. This is not a PR-ready or release claim.

The existing media GET opts into `application/vnd.open-eos.media-list.v1+ndjson`. The same response may still be ordinary JSON from an old Bridge or an unsupported engine. There is no probe, second-request fallback, or traversal replay. Custom transports using the default `openStream` implementation can still buffer the response; progressive display is available with the streaming URLSession transport.

Only complete ordered snapshot revisions reach the existing progress callback. UTF-8 and record framing, request/session/scope binding, safe integer sequence/revision/offset/count, a terminal record and EOF are validated. The parser bounds lines to 65,536 bytes including LF, revisions to 100,000 items and 64 MiB of wire frames, and chunks to 128 items. Bounds fail explicitly, retaining the last fully published snapshot. They never truncate a full-card read into success. Opaque IDs retain their exact bytes.

The URLSession queue is bounded only for this opt-in request: 64 buffered pieces of at most 64 KiB, with at most 1 MiB in a received delegate chunk. Overflow cancels the exact stream and fails. Listing redirects are rejected. Existing ordinary and strict transports keep their prior behavior.

Manual cancellation, replacement, scope change and disconnect retire the owner before late progress can publish. Bridge event listings have an independently cancellable task. A failed Bridge event traversal consumes its hint rather than replaying through event backoff. An event poll opened before a cancelled/failed load is conservatively retired as media demand; a new poll/event or explicit Refresh can start a new read. A Bridge gallery event uses its gallery traversal instead of launching a concurrent latest-media traversal.

Automatic Bridge gallery thumbnails wait for genuine selected-scope completion. Cancelled/failed partials stay visible without automatic reads. Visible cards resume via their existing UI task when the owned load completes; explicit preview retires an active listing first. Direct CCAPI thumbnail behavior is unchanged.

Fixture coverage: fragmented UTF-8/JSON, chunked snapshots, more than 1,000 full-scope items, exact Unicode identities, malformed binding/numbers/terminal, missing LF/EOF, oversize records, error inside an unfinished revision, progress before terminal, cancellation, same-ID session replacement, legacy JSON and no fallback; bounded queue failure versus ordinary stream; UI thumbnail/preview ownership and old-event suppression/new-event recovery. Run `swift test` in `ios/OpenEOSCore`, then the app test target on a supported Xcode environment. Existing direct CCAPI and foreground inventory suites remain required.

Completion is selected browsing scope only. It is not an atomic card snapshot or strict inventory evidence and does not enable Bridge foreground import. Server cancellation stops later scheduling cooperatively after any active camera read returns; it does not promise instantaneous camera I/O interruption. Physical-camera/USB behavior remains unverified.
