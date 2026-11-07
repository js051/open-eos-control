# Media delivery observation vectors v1

`outcome-vectors.json` is a bounded, synthetic cross-platform acceptance vocabulary.
It is a test input, not a persisted-history format, an interoperable transfer queue,
or permission to resume a save. Native status names intentionally remain different.
Each `expected` object contains assertions for that scenario; omitted fields make no
claim. `targets` limits applicability. There are 26 vectors. The shared ceilings are 32 vectors, 12 events
per vector, 100 selected rows, and 32 KiB of UTF-8 JSON. Native retention byte limits
and storage formats remain platform-specific.

## Evidence boundaries

- `saveConfirmed: true` means the route's observed external commit checkpoint only:
  browser/SAF stream close, or Android Gallery close plus successful publication.
  It does not mean fsync, future existence, cloud sync, or a byte-for-byte readback.
- `prepared` means an iOS app-private copy, independently of external delivery.
- A Blob/anchor click means `handoff_requested`. Browser download completion is not
  observable through that API; the user checks the browser's Downloads UI.
- A full unique iOS Files callback means `provider_reported` at batch scope. It
  cannot establish per-input mapping, individual saved bytes, or provider cloud sync.
- Cancellation describes the observed control result. It is not proof that no
  external copy exists. A commit confirmed after cancellation remains confirmed.
- A rejected PC writer close remains uncertain even after a successful abort.
  A failed cleanup also leaves possible output. Android separately records the
  original failed/cancelled result and `cleanupUnconfirmed`; these do not have to
  share PC's enum spelling.
- Persisted receipts cannot grant source/destination access. Cold in-progress
  attempts project unconfirmed; never-started rows stay queued. Reading history
  performs no source request, picker presentation, or automatic resumption.
- Selected-source worklist continuation needs fresh source mapping, a current owner/epoch, and
  fresh acknowledgement when any linked attempt is uncertain. A completed linked
  family blocks another continuation. Same display names never establish identity.
  Manual iOS Files observations have no continuation mapping: after inspecting
  Files, a newly selected export is a separate explicit operation. The dedicated
  `cold_picker_pending` and `picker_terminal_write_failed_cold` vectors reflect this.
- A confirmed clear prevents late callbacks from rebuilding history. It does not
  delete external destination files. Native private-copy lease cleanup is separate.

## Production and regression mapping

### PC

- `bridge/open_eos_bridge/static/app.js`: `downloadMedia`, `chooseMediaWritable`,
  `saveMediaBlob`; single-file Blob handoff vs writer-close feedback, uncertain close
  and abort, and silent-owner retirement. The save picker runs synchronously in the
  user gesture; its chosen handle stays local. Durable history admission must succeed
  after picking but before createWritable/original GET (before GET on the Blob route).
  Failed or unconfirmed admission stops before I/O; terminal history write failures
  never change a confirmed output or cause another attempt.
  Cold single-file observations have no worklist or continuation action.
- `media-batch.js`, `media-batch-destination.js`, and `transferMediaBatchItem`:
  sequential selected saves, exact-owner checks, close after cancellation, ambiguous
  creation/close/abort, and the boundary before starting the next row.
- `media-transfer-history.js`: atomic bounded rosters, display-only origins, cold
  unknown projection, clear epoch and explicit fresh-mapping continuation. Snapshot
  schema 4 also admits standalone `browser-download` / `handoff-requested` and
  `browser-file` / `close-confirmed` pairs, without treating either as directory rows.
  Handoff always retains outcomeUnknown; Blob can never become completed. Schemas
  1–3 remain readable without rewriting; mutations migrate atomically and preserve
  v3 origins. Older clients reject schema 4 before mutation. All attempts share
  the existing 100-row/100-KiB bounds and never retain URLs, handles or source IDs.
  `bytesTransferred` on a handoff records source bytes consumed, not saved bytes.
- `bridge/tests/capture-review.test.js` consumes this exact fixture for the
  single-file handoff, close, late cancellation and uncertain cleanup cases.
- `bridge/tests/media-transfer-history.test.js` and `media-batch-owner.test.js`
  cover cold queued/unknown rows, whole-plan admission, clear/late callbacks,
  capacity, linked completion, and explicit continuation.

### Android

- `ui/CameraViewModel.kt`: `downloadMedia` confirms only after SAF stream close;
  `downloadMediaBatchInternal` uses admitted whole rosters and explicit mappings.
- `ui/CameraMediaGalleryStore.kt`: validates positive written bytes, successful
  close, expected sizes, and one-row `IS_PENDING=0` publication before confirming.
- `ui/MediaOutputFinalization.kt`: `confirm` survives cancellation/observer errors;
  `protect` performs owned cleanup once and preserves a typed cleanup failure.
- `ui/DownloadHistoryTransfer.kt`: completion belongs to the commit callback;
  cancellation/failure cannot downgrade a confirmed receipt. Ordinary transport
  retries remain inside one admitted receipt, never reconstructed from history.
- `data/DownloadHistoryStore.kt` and `DownloadHistoryFileStorage.kt`: cold
  `IN_PROGRESS -> UNCONFIRMED`, bounded local rows, linked-family eligibility,
  strict validation, clear epoch and persistence availability.
- Existing tests: `MediaOutputFinalizationTest`, `DownloadHistoryTransferTest`,
  `PlannedMediaDownloadBatchTest`, `DownloadHistoryStoreTest`, and
  `DownloadHistoryContinuationStoreTest`. In particular:
  `admittedOutputFinalizedBeforeIoReturnSurvivesJobCancellation`,
  `failedTerminalJournalWriteLeavesSavedBytesAndReloadsPendingAsUnconfirmed`,
  `coldReaderShowsFiveCompletedOneUnconfirmedAndFortyFourQueuedWithoutResuming`,
  `clearBetweenContinuationRowsStopsWithoutUntrackedTransfer`, and
  `continuationPersistsOnlyLocalAssociationAndSanitizedNewLabels`.
- Android DOWNLOAD batches now stop on the first terminal row failure, including
  unconfirmed cleanup. Earlier confirmations survive; untouched rows retain no
  receipt and display NotStarted. Metadata/mutation batches keep their existing
  continue-on-failure policy. `MediaDownloadBatchPolicyTest` and the updated
  planned-batch/history/UI journey tests cover this distinction.
- A local portable JUnit check compiles the real finalization primitive plus
  unchanged extracted production batch/DTO declarations; it consumes fixture
  expectations and the permanent policy tests. This does not replace full Android
  SDK, provider, Compose, or disk-history execution.

### iOS

- `MediaBatchDelivery.swift` and `MediaDeliveryStore.swift`: private preparation,
  bounded retained originals/leases, persisted rosters and fresh mapping.
- `MediaFilesExport.swift`: explicit picker, source revalidation, batch callback
  observations, duplicate/partial callbacks, cancellation and teardown.
- `MediaFilesExportTests`, `MediaDeliveryStoreTests`, and
  `MediaBatchDeliveryTests` are the corresponding native test locations. Manual
  external-attempt persistence is being integrated separately; the PC fixture's
  existence does not establish that the iOS consumer has run or passed.

## Intentional platform differences and remaining evidence

The reviewed Android baseline continued even after an unconfirmed cleanup; PC and
iOS stopped at the failed row. The accompanying Android change adopts the safer
stop-at-first-terminal-failure policy for DOWNLOAD only. Existing bounded transport
retries within the same safely cleaned-up attempt are unchanged. PC may require an uncertainty acknowledgement for a queued row because another tab
could still own the roster; a cold reader cannot prove that other tab has stopped.

All fixture results are synthetic or local execution evidence. Full native
provider/UI execution, real camera behavior, exact-SHA CI, merge and release are
separate gates. Do not label this contract physical-device validation or a completed
cross-platform release.
