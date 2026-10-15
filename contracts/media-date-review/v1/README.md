# Media date and review contract v1

The bounded synthetic `date-review-vectors.json` is shared byte-for-byte by the
three development branches. Platform implementations live on their respective
branches; this is not combined-tree or physical-camera evidence.

## Date precision

The original metadata and IDs are never rewritten. Strict, trimmed full-string
parsing distinguishes `instant`, `floating`, `date-only`, and `unknown`.
Trimmed source strings longer than 128 characters are unknown.
Accepted Gregorian years are AD 1–9999, with real month lengths and leap years;
hours are 0–23 and minutes/seconds 0–59. Date prefixes do not rescue invalid
full values. The source grammar is:

- `YYYY-MM-DD`
- `YYYY-MM-DD[T or space]HH:mm[:ss[.1–9 fractional digits]]` with optional
  `Z`, `+HH:mm`, `-HH:mm`, `+HHmm`, or `-HHmm`
- Camera/PTP `YYYYMMDDTHHMMSS`
- An explicitly offset timestamp may additionally carry `[IANA zone]`, at most
  64 ASCII zone-name characters. The zone must be recognized and its actual
  offset at the instant must agree exactly with the explicit offset. A bracket
  cannot turn a floating value into a known instant. This retains the existing
  Android `Z[UTC]` case without allowing contradictory zone normalization.

Offsets are bounded by ±18:00; an 18-hour offset cannot include minutes.
Fractions are truncated to milliseconds for the common sortable precision;
source text is retained. Invalid fields, second 60, 24:00, extra fractions,
trailing garbage, unknown zones, or out-of-range Gregorian display years stay
unknown.

Only offset-bearing values produce `instantMillis`. The caller supplies one
viewer-zone snapshot to derive the displayed day/time for an instant.
Floating values keep the camera's literal calendar and clock fields, including
DST gaps/overlaps, and are labeled “Time zone unknown” / “時區未知”. Literal
dates never shift by timezone and never expose a fabricated midnight instant
or display a time. A platform may use a UTC `Date` solely as an internal
formatting carrier; that carrier must not enter instant sorting or provenance.

## Stable gallery order

Filtering, grouping, cards, details, and date sorting use the same viewer-zone
snapshot. Refresh it coherently when the app resumes or receives a timezone
change. Preserve the selected literal range endpoints and selected media IDs.

Newest/oldest use a total tuple order:

1. Known dates precede unknown values in both directions.
2. Displayed day follows the requested newest/oldest direction.
3. Within a day the precision buckets are always instant, floating, date-only.
4. Within one bucket compare only its real millisecond instant or floating
   clock key, in the requested direction.
5. Equal values and unknown metadata retain loaded camera order.

Do not compare a fake midnight with an instant or use a pairwise mixed-precision
comparator that can cycle. Bucket order is a presentation convention, not proof
of capture chronology. Name and rating sorts remain explicit separate modes;
camera order remains untouched.

## Review selection is independent

Use explicit recent/photo/video purposes. When a review has a pre-operation
snapshot, exclude every observed ID, then enforce photo/video type and retain
the bounded camera response order. Recent without a snapshot can include either
type. Dates, filenames, acknowledgements, and gallery filters cannot make an old
ID new or make an observed item prove that this app caused its capture.

Keep session/generation ownership, cancellation, bounded read-only search,
zero shutter/REC resend, and existing thumbnail/preview admission. Do not add a
full-card baseline scan. Foreground auto-import still requires complete inventory,
new ID, JPEG type, fresh repeated metadata and stable size independently.

## Fixed evidence

There are 48 parse vectors across UTC, Asia/Taipei, and America/Los_Angeles,
9 fixed sort cases, and 4 review cases. Expected values were authored separately
with Python standard-library Gregorian/timezone arithmetic, not any tested
JS/Kotlin/Swift parser. The checked-in file is static. Equal offset instants,
RAW/JPEG order, literal dates, DST gaps, contradictory named zones, malformed
full dates, and future-dated videos mixed into photo review are included.

Each platform must separately report execution of the actual parser, filter,
sort, group/detail formatting, and review code. Pure logic/source tests are not
real UI, SDK, simulator, transport, camera, or physical-device validation. An
offline data-only Kotlin fixture provider is not the formal org.json resource
loader and must be reported separately.
