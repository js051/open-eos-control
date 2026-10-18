# gPhoto2 upload destination inventory validation

## Scope and result

Baseline: integration commit `8130d099`. This is a backward-compatible upload correctness fix, with a proposed `patch` impact if included in a later Development Preview. It does not change the product version, merge or publish a release.

The existing upload path used permissive legacy media-list parsing both before writing and after subprocess success. A synthetic successful listing that declared one destination file but omitted its row was accepted as an empty destination. The existing exact-name synthetic file was then replaced and the upload returned a successful receipt. The new regression fails on the baseline with `DID NOT RAISE`.

Upload admission and receipt verification now use strict UTF-8 decoding and parsing through a private media-list path. They require an explicit section for the selected storage base directory, validate each declared folder count, and reject empty/unrecognized output, unexpected nonempty lines, malformed or headerless file rows, repeated folder sections and duplicate file identities. A file-row name containing folder-header text cannot be interpreted as a header. Invalid output is not installed as a new media cache.

An unavailable camera listing also prevents upload capability advertisement and admission. An existing host capture, or support for host captures, cannot substitute for a camera destination inventory. Ordinary legacy browsing keeps its permissive parsing behavior.

## CLI evidence and compatibility boundary

The official [gPhoto2 `list_files_action` implementation](https://raw.githubusercontent.com/gphoto/gphoto2/master/gphoto2/actions.c), inspected on 2026-10-18, prints a counted section for each visited folder, including a zero-file section (lines 214–254). The official [CLI usage example](https://gphoto.sourceforge.io/doc/manual/using-gphoto2.html) likewise includes empty root and child folders. This supports representing empty visited storage roots in the shared synthetic runner. That runner now emits one grouped, counted section per folder instead of repeating a section for every uploaded file.

The source URL above tracks upstream `master`; it is not pinned-source or physical-device evidence. No whole-device completion footer is assumed. A camera/driver response that omits the selected storage root fails closed, even when other folders are listed successfully. This change cannot detect an entirely omitted unrelated folder and does not claim atomicity against an independent writer changing the camera between listing and upload.

The pre-existing exact-byte receipt limitation is separate: upstream ordinary file-list output rounds sizes to KB, while `--show-info` provides exact bytes. The inventory-only patch does not establish that receipt; the same integrated delivery adds the separately verified [exact-size receipt correction](gphoto-upload-size-receipt-2026-10-18.zh-TW.md).

## Verification

All checks used the existing Bridge validation interpreter. No camera, package installation, full-suite run or CI rerun was used.

- Baseline red: one new incomplete-destination regression fails.
- Initial green: the same regression passes after strict parsing.
- Final focused set: 47 upload cases pass, covering the new 27-case inventory/capability suite and existing upload, HTTP route and retirement cases.
- Listing compatibility: five selected existing strict-count, legacy and host-capture cases pass.
- Ruff and `git diff --check`: clean.

Commands, run from `bridge/`:

```text
python -m pytest -q tests/test_gphoto_upload_inventory.py
python -m pytest -q tests/test_gphoto_upload_inventory.py tests/test_media_upload_outcomes.py tests/test_upload_session_retirement.py tests/test_gphoto2.py tests/test_api.py -k upload
python -m pytest -q tests/test_gphoto_listing.py -k 'incomplete or inconsistent or legacy or ordering or hosts or host_captures'
ruff check open_eos_bridge/gphoto2.py tests/fakes.py tests/test_gphoto_upload_inventory.py
git diff --check
```

Evidence: [`evidence/gphoto-upload-inventory-20261018/`](evidence/gphoto-upload-inventory-20261018/).

These are synthetic provider/API results only. Actual-camera upload support, destination behavior and physical readback remain unvalidated. Status: implemented locally; no PR-ready, main-accepted or released claim.
