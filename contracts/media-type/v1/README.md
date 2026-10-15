# Media classification and automatic JPEG qualification v1

Fixed synthetic cross-platform data, independent of production implementations.
Classification does not confer transport/decoder support or authorize delivery.

## Classification

Require a nonblank name; otherwise unknown. Preserve original name and ID bytes.
Normalize kind by trimming surrounding whitespace and ASCII lowercase. Kind families:
photo: image, photo, jpeg, jpg, raw; video: video, movie; audio: audio;
other: text, document. Other/unrecognized kind values are neutral.

Normalize MIME by taking text before first semicolon, trimming and ASCII lowercasing.
Absent/empty/whitespace MIME and application/octet-stream are neutral for display.
A nonempty normalized MIME must match token/token, with tokens containing only
ASCII a-z, 0-9, !#$&^_.+-; invalid MIME or raw MIME containing any ISO control (U+0000–001F, U+007F–009F) makes classification
unknown and cannot qualify for automatic JPEG delivery. image/* is photo,
video/* video, audio/* audio, all other valid MIME types are other.

Use the final literal filename suffix, lowercase, without trimming the name:
photo: jpg jpeg png gif bmp webp heic heif tif tiff avif cr2 cr3 crw dng;
video: mp4 mov m4v avi mkv;
audio: wav mp3 aac m4a flac ogg opus aif aiff wma.
Unrecognized suffixes are neutral. If strong families disagree return conflict;
otherwise the sole family wins; if no evidence exists return unknown. RAW and
ordinary images share photo family, but RAW never thereby gains JPEG eligibility.

Gallery photo/video filters and capture-review intents select only exact photo /
video family. All/recent retain camera ordering and unknown/conflict items without
claiming they are photo or video. Keep known-ID exclusion and bounded retry rules.
Photo full-preview requires photo plus existing preview/capability gates.
Preserve previously supported photo/video poster thumbnail endpoints only with
existing image-payload checks; do not newly enable another transport;
video routing/streaming require video plus existing transport support gates.
Unknown/conflict/audio/other must not enter image/video decode paths. A generic
file/manual-delivery path may remain where already supported. Classification
alone cannot set unsupported transport availability to true.

## Strict automatic JPEG admission and fresh metadata

This is deliberately stricter than display classification. Preserve the
intersection of previous safety qualifications, never broaden them for parity:
- nonblank ID/name; exact literal kind `image` (not normalized aliases/case);
- final literal filename suffix jpg/jpeg, case-insensitive, with a nonempty prefix
  before the final dot (a dotfile named .jpg/.jpeg alone does not qualify);
  automatic names are single literal basenames, with no slash or backslash;
- classification photo, without conflicting/malformed metadata;
- MIME absent or exactly empty, or normalized image/jpeg with optional parameters.
  Nonempty whitespace MIME, image/jpg, generic MIME and non-JPEG types are rejected.

Fresh metadata must be explicitly fresh, retain exact original ID and name, and
both original and returned items must satisfy the same strict gate. Recheck after
metadata refresh and before transfer; never transfer stale conflicting kind/MIME.
Keep existing complete two-baseline inventory, immutable old-ID exclusion,
positive stable size reads, ownership/session/foreground/cancel limits, queue and
history accounting. Unready size must follow the existing bounded wait behavior,
not be fabricated as success. No shutter/REC/AF or unsolicited rescan/resend.

Vectors are a shared data contract; tests must exercise actual production code,
not replace behavior with a fixture-derived result. Real native resource/adapter/
UI and physical-camera acceptance remain distinct from pure/source verification.

## Raw metadata aliases

Adapters recognize content_type, contentType, contenttype, mimeType, mimetype.
Absent and explicit null are represented as absent MIME; empty/raw strings remain
unchanged. Every present alias must be string or null and match every other
present alias exactly. Different case, parameters, null versus empty or any
other conflicting declarations fail closed; never choose the convenient alias.
No accepted key on a successful fresh info response means genuinely absent MIME,
not a cached or guessed type. Explicit generic MIME must not become absent.
Invalid/failed fresh reads retain the existing zero-size/generic cache invalidation.
