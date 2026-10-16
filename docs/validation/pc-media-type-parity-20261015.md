# PC media type and fresh JPEG qualification — 2026-10-15

Local implementation is complete; this later cohort has not been pushed.

- Shared classification admits only a single coherent photo/video/audio/other family;
  conflicts, invalid MIME and unknown items remain visible in All but do not enter decoders.
  Cached rendering and delayed same-ID thumbnails recheck current type/name/session.
- Gallery/review selection keeps camera order, literal identity/name, existing transport
  capability gates, valid video-poster thumbnails and manual opaque-file delivery.
- Automatic JPEG admission requires exact image kind, literal jpg/jpeg suffix and
  nonconflicting MIME and a single basename with a prefix before the final dot; aliases,
  whitespace-only/generic MIME, dotfiles, path separators, RAW and other kinds do not qualify.
- Fresh metadata preserves raw declarations and rejects alias conflicts. Canonical request
  identity is authoritative when fields are absent; no cached or guessed type is fresh evidence.
  A successful response with genuinely absent MIME returns null. Invalid/failed reads still
  invalidate cached size/type. Zero size waits within the existing bound; positive size must
  qualify twice before an original request. The transferred record is a frozen fresh copy.
- Response-header fallback never rewrites the metadata evidence.

## Executed evidence

1,241 explicit JavaScript tests (1,181 Node-reported plus 60 custom), including 107 new
fixed type/policy tests. 586 pytest cases passed with two existing warnings. Actual CI
ruff scope, sdist and wheel build passed; eight packaged source/assets match byte-for-byte.
Fixture counts are included in these totals, never added to test counts.

Normal commit/outgoing hook results are recorded in the cumulative external receipt.
Browser/native/physical-camera execution remains NOT_RUN for this cohort.

## Remaining proof boundaries

Original transfer currently establishes HTTP status, ownership, byte length and nontext
response checks. It does not prove actual JPEG byte identity; preview validators are not
original validators. This safety limitation is retained explicitly, without claiming that
metadata qualification alone makes arbitrary payloads valid JPEG.

The separately approved 04:30 delivery/LUT snapshot is undergoing exact-head CI. This
later date/review/type cohort is not silently substituted into those runs.

Release assessment: patch-sized corrective group; cumulative development branch minor
relative to v0.12.0 Development Preview. No release or merge authorized.
