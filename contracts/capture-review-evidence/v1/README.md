# Capture review observation and read evidence v1

Synthetic, fixed cross-platform vectors. No camera, network or private identifiers.
The result has two independent axes: observation and evidence of completed reads in
one bounded invocation. These vectors are not a camera provenance guarantee.

- observation: `found`, `no-candidate-observed`, or `unread`.
- successfulReads / failedReads: nonnegative counts from this invocation only.
- lastCompletedRead: `success`, `failure`, or null when nothing completed.
- candidateId: the first eligible ID in camera listing order, or null.
- Success means a valid parsed listing, never just HTTP 200. `malformed` represents
  a rejected/missing items-list response at the transport/adapter boundary.
- `empty`: valid empty list. `known-only`: [OLD_A, OLD_B]. Both IDs are photographs
  already in previousIds. `wrong-kind`: one NEW_VIDEO for photo intent or one
  NEW_PHOTO for video intent. `found`: [OLD_A, NEW_PHOTO, NEW_VIDEO, NEW_PHOTO_2,
  NEW_VIDEO_2]; choose only the requested kind and exclude previousIds.
  Photos use kind jpeg, image/jpeg, .JPG; videos kind video, video/mp4, .MP4.
  Names and dates cannot substitute for ID/type guards or reorder the camera list.

Every exhausted invocation makes four reads, with 250/750/1500 ms pauses between
reads; finding an eligible item stops early. Failed reads count but never become
an empty successful list. All 16 S/F patterns, three no-candidate list shapes,
and both photo/video intents are literal expected vectors. Found and malformed
responses add boundary vectors. UI retry reuses the original known-ID snapshot,
never sends shutter, REC, AF or any other camera mutation.

Mixed successful/failed reads must retain both facts. A final failed read after an
earlier success means no candidate was observed AND current list visibility is
unconfirmed; show an explicit last-read warning. A final successful no-candidate
read may show the ordinary pending observation while earlier failure counts remain.
No successful reads means unread, not evidence that no new media exists. Keep
capture/record-stop acknowledgement independent from this enrichment outcome.

Cancellation and owner/session replacement retire the invocation: do not publish
partial or late results/evidence. A retry preempted with zero completed reads keeps
the prior terminal outcome/evidence. A new capture with no prior terminal result
must not fabricate a read failure. Counts reset for each invocation; previous
successful checks never mask a fresh all-failed retry. UI searching is transient,
not a new terminal result. Immutable terminal evidence survives retired work.

Fixture expectations are fixed data, not generated at test time by production
code. Native resource/JSON integration is a separate gate from a transparent
pure-JVM data-only fixture provider. Native/runtime/physical evidence must be
reported separately from source-level checks.
