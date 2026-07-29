# Device Validation

[繁體中文版](device-validation.zh-TW.md)

Automated tests prove request construction, parsing, state transitions, and UI contracts against deterministic inputs. They do not prove that a physical camera accepted an operation or produced the expected camera-side result. Open EOS Control therefore records three distinct evidence levels:

1. **Advertised**: the camera or active engine exposed the required operation.
2. **Observed this session**: the product received a successful command response or valid data payload.
3. **Operator-confirmed**: a person explicitly saw the expected camera-side result, such as a new image, focus movement, recording indicator, or deleted card item.

Only the third level is a physical-result claim. The verifier never promotes an advertised feature to observed and never treats a successful response as an operator confirmation.

## Capture A Report

Use a current build, connect the physical camera, exercise the features under test, then copy the report from **Debug > Copy diagnostic report** in Android, iOS, or the PC control UI. Save the copied report outside the repository using a name such as `diagnostic-report-r6m3.txt` or `diagnostic-report-r6m3.json`; these names are ignored by Git.

The source report must remain private. It is accepted only when it:

- uses report schema 1 and identifies a non-unknown product version;
- identifies Canon EOS R6 Mark III and has an ISO-8601 generation time;
- has internally consistent advertised, observed, validated, and difference sets;
- has complete capability evidence unless explicitly overridden;
- contains no camera serial, credential, Authorization value, email address, URL user information, or machine-local path;
- is no more than 30 days old by default and is not more than five minutes in the future.

## Verify Required Capabilities

This example accepts only a direct Android CCAPI report where identity and valid Live View data were observed:

```powershell
python scripts/validation/verify_diagnostic_report.py diagnostic-report-r6m3.txt `
  --expect-transport CCAPI_NETWORK `
  --require CAMERA_IDENTITY,LIVE_VIEW `
  --format summary
```

An advertised-but-unobserved feature fails `--require`. For example, `STILL_CAPTURE` does not pass merely because the camera published its shutter endpoint.

Use repeated `--expect-transport` options when a validation accepts multiple routes. Current report values include `CCAPI_NETWORK`, `USB_PTP`, `DESKTOP_BRIDGE`, `DESKTOP_BRIDGE_LIBGPHOTO2`, and `DESKTOP_BRIDGE_CCAPI`.

## Record Physical Outcomes

After seeing the actual result on the camera, add an explicit operator confirmation. `--require-physical` makes omission of that confirmation an error:

```powershell
python scripts/validation/verify_diagnostic_report.py diagnostic-report-r6m3.txt `
  --require STILL_CAPTURE `
  --physical-confirmed STILL_CAPTURE `
  --require-physical STILL_CAPTURE `
  --format markdown `
  --output docs/validation/eos-r6-mark-iii-still-capture.md
```

The generated Markdown contains only the model, product version, transport, timestamp, capability states, confirmation labels, and a SHA-256 identifier for the already-sanitized source report. It omits raw endpoints, raw status payloads, credentials, serials, and local paths. The hash identifies the exact private input; it is not a signature or remote attestation. Existing output files are not replaced unless `--force` is supplied.

Operator confirmation is an attestation, not an automated measurement. The validation record should describe the actual setup and any visible limitations in adjacent prose when behavior such as sustained FPS, latency, file integrity, or Wi-Fi/cellular coexistence is under test.

## Machine-Readable Output

Use `--format json` for automation. Exit code 0 means every schema, privacy, freshness, consistency, required-feature, and required-physical check passed. Invalid or insufficient evidence exits with code 2 and prints each failed condition to standard error.

Run the verifier tests locally with:

```powershell
python -m unittest discover -s scripts/validation/tests -p "test_*.py"
```

CI runs the same suite. A passing verifier test proves the evidence tooling, not a camera capability; the physical source report and explicit confirmations remain required.
