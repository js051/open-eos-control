# Android manual worklist continuation: source candidate

Date: 2026-10-13 UTC. Base: `cd1afe568448bc8252351054c6b05a381e865e47`.
This is a source implementation and authored-test record, not a passing build, device result, PR-ready claim, or release. [繁體中文](android-manual-worklist-continuation-2026-10-13.zh-TW.md).

## User journey

Download history now offers **Continue unfinished items** after the user connects and loads originals in the album. Opening history, reconnecting, or restarting does not fetch or save an original. A new draft has no mappings. The user explicitly associates each selected unfinished row with one original already loaded in the current connection; unselected rows are left alone. Completed rows and rows with a completed or active linked attempt are excluded. One loaded source cannot be mapped twice in the same draft.

An unconfirmed prior result or possible leftover file requires a separate acknowledgement that the previous destination was checked. This applies throughout the local association family, including when the user returns through an older queued row or sibling attempt. The user then confirms the mappings and chooses Gallery, or opens the existing Android SAF folder picker. The folder flow handles one or several originals and creates new documents only after durable admission. No old path, document URI, or grant is restored. Single-document CreateDocument remains the existing ordinary-save flow; continuation uses the folder picker so that the picker does not create an output before the new roster is durable.

The history shows a new worklist, new receipts, and the older row to which each attempt was manually linked. Older rows and their results remain intact. These local associations prevent accidental repeated attempts; they do not authenticate camera/source identity, prove source equivalence, establish a hash, or confirm that an older saved file still exists.

## Admission and ownership

- The process store serializes request-epoch, origin existence, association-family eligibility, duplicate-origin, uncertainty acknowledgement, and capacity checks with atomic snapshot admission. No continuation falls back to an unrecorded save.
- The complete new roster must be durable before any output creation/open or original GET; each row must also be durably admitted. A failed/uncertain write, a retired owner, or a cleared history stops before new transfer I/O.
- All legacy entries and worklist rows share the existing 100-row limit. Continuations neither evict old evidence nor truncate selections. The 256 KiB snapshot bound and strict versioned parser remain; schemas 1–3 are readable and new writes use schema 4.
- Only random local worklist/row IDs form persisted links. Draft mappings, camera/media identifiers, session ownership, destination paths/URIs, grants and acknowledgement capabilities are not serialized in history.
- Cancel, clear, process replacement and connection changes retire draft/picker capabilities. A late picker callback cannot become an ordinary save or overwrite a newer save UI. Clear retires transfer capabilities immediately and retains only exact-owner cleanup feedback until cancellation settles. Releasing a batch blocks further row starts while genuine already-started receipt completion retains its original owner.

No new endpoints, metadata lookup fan-out, background queue, automatic reconnect/resume, Range resumption, camera mutation, framework, or dependency is added.

## Authored verification

`DownloadHistoryContinuationStoreTest` covers cold 50-row reconstruction (5 completed, 1 unconfirmed, 44 queued), explicitly selected subsets, unchanged old results, schema/parser/privacy limits, admission write faults, capacity, clear epochs, live-process exclusion/release, and association-family completion/unknown/active gates.

`DownloadContinuationDraftTest` covers empty defaults, same-named sources, duplicate-source rejection, ordering, acknowledgement reset, and uncertain ancestors. `PlannedMediaDownloadBatchTest` adds strict continuation failure/clear/retired-owner checks.

The production App/ViewModel journey tests cover mapping and unknown confirmation before exactly two selected Gallery originals, cancellation, duplicate mappings, full history, failed admission, clear during a transfer, stale picker returns, and reconnect with reused IDs. The SAF journey uses the actual DocumentsProvider dispatch, asserts that the linked roster and pending row are on disk before document creation, and preserves a refused-cleanup warning after Clear removes the history. This locally wrapped provider test is not an OS picker, cross-UID grant, third-party provider, or physical-device result.

## Results and remaining gates

- PASS: `git diff --check`; both edited string resources parse as XML with unique names and matching continuation translation keys/placeholders.
- NOT_RUN: Kotlin compilation, focused/full JVM tests, Android lint/APK build, Android instrumentation and OS-picker runs. The available JDK alone is insufficient; no existing Gradle distribution, Android SDK, or Kotlin/dependency harness was identified for this task. Nothing was installed or downloaded, and no new CI was triggered.
- NOT_RUN: physical camera, phone storage/provider, power-loss/process-kill and transport-specific acceptance. Cold snapshot reconstruction is not evidence of an actual OS process kill.

Before PR-ready status, run the affected JVM and Android runtime suites, normal aggregate gates, and exact-head CI. Preserve failures and platform boundaries. No commit, push, main merge, version change, or publication occurred in this source batch.

Release assessment: new user-visible manual recovery is a **minor** feature when accepted for distribution. Source version is unchanged; the current published release baseline was not re-queried in this offline source task. Compilation/runtime and normal delivery gates remain open; physical compatibility remains unclaimed.
