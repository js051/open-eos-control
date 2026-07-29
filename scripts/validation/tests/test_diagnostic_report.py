from __future__ import annotations

import json
import tempfile
import unittest
from contextlib import redirect_stderr
from datetime import UTC, datetime
from io import StringIO
from pathlib import Path

from scripts.validation.diagnostic_report import (
    DiagnosticReportError,
    main,
    parse_diagnostic_report,
    render_json,
    render_markdown,
    validate_diagnostic_report,
)

NOW = datetime(2026, 7, 30, 12, 0, tzinfo=UTC)


def android_report(**overrides: str) -> str:
    fields = {
        "reportSchema": "1",
        "generatedAt": "2026-07-30T11:30:00Z",
        "productVersion": "0.1.2",
        "camera": "Canon EOS R6 Mark III",
        "serial": "[redacted]",
        "transport": "CCAPI_NETWORK",
        "supported": "CAMERA_IDENTITY, BATTERY_STATUS, LIVE_VIEW, LIVE_VIEW_JPEG_POLLING, STILL_CAPTURE",
        "planned": "LIVE_VIEW_RTP",
        "capabilitySource": "CCAPI discovery",
        "protocolVersions": "ver100",
        "advertisedCommandCount": "4",
        "advertisedCommands": "GET /ccapi/ver100/deviceinformation | POST /ccapi/ver100/shooting/control/shutterbutton",
        "writableSettings": "iso, tv, av, wb",
        "observedFeatures": "CAMERA_IDENTITY, BATTERY_STATUS, LIVE_VIEW, LIVE_VIEW_JPEG_POLLING",
        "advertisedFeatureCount": "5",
        "observedFeatureCount": "4",
        "validatedAdvertisedFeatureCount": "4",
        "unverifiedAdvertisedFeatures": "STILL_CAPTURE",
        "observedWithoutAdvertisement": "none",
        "capabilityEvidenceTruncated": "false",
        "battery": '{"level":"full"}',
        "storage": "null",
        "lastError": "none",
    }
    fields.update(overrides)
    return "\n".join(["Open EOS Control diagnostic report", *(f"{key}={value}" for key, value in fields.items())])


def desktop_report(**overrides: object) -> str:
    body: dict[str, object] = {
        "product": "Open EOS Control Desktop",
        "reportSchema": 1,
        "generatedAt": "2026-07-30T11:30:00Z",
        "productVersion": "0.1.2",
        "bridge": {"ok": True, "authRequired": True},
        "camera": {
            "id": "gphoto2:test",
            "model": "Canon EOS R6 Mark III",
            "port": "usb:001,007",
            "engine": "libgphoto2",
        },
        "info": {
            "model": "Canon EOS R6 Mark III",
            "serial": "[redacted]",
            "api": "gphoto2",
        },
        "capabilities": {
            "supported": ["CAMERA_IDENTITY", "DESKTOP_BRIDGE", "LIVE_VIEW"],
            "planned": ["LIVE_VIEW_RTP"],
            "evidence": {
                "source": "libgphoto2",
                "observedFeatures": ["CAMERA_IDENTITY", "DESKTOP_BRIDGE", "LIVE_VIEW"],
                "truncated": False,
            },
        },
        "validation": {
            "advertisedFeatureCount": 3,
            "observedFeatureCount": 3,
            "validatedAdvertisedFeatureCount": 3,
            "unverifiedAdvertisedFeatures": [],
            "observedWithoutAdvertisement": [],
        },
        "lastError": None,
    }
    body.update(overrides)
    return json.dumps(body)


class ParseDiagnosticReportTests(unittest.TestCase):
    def test_parses_android_key_value_report(self) -> None:
        report = parse_diagnostic_report(android_report())

        self.assertEqual(report.source_format, "key-value")
        self.assertEqual(report.camera_model, "Canon EOS R6 Mark III")
        self.assertEqual(report.transport, "CCAPI_NETWORK")
        self.assertEqual(len(report.supported), 5)
        self.assertEqual(report.unverified, frozenset({"STILL_CAPTURE"}))
        self.assertEqual(len(report.report_sha256), 64)

    def test_accepts_explicitly_redacted_nested_sensitive_fields(self) -> None:
        report = parse_diagnostic_report(
            android_report(lastError='{"serial":"[redacted]","token":"[redacted]"}')
        )

        self.assertEqual(report.camera_model, "Canon EOS R6 Mark III")

    def test_parses_desktop_json_report(self) -> None:
        report = parse_diagnostic_report(desktop_report())

        self.assertEqual(report.source_format, "json")
        self.assertEqual(report.transport, "DESKTOP_BRIDGE_LIBGPHOTO2")
        self.assertEqual(report.validated, frozenset({"CAMERA_IDENTITY", "DESKTOP_BRIDGE", "LIVE_VIEW"}))

    def test_rejects_duplicate_text_keys(self) -> None:
        with self.assertRaisesRegex(DiagnosticReportError, "duplicate key"):
            parse_diagnostic_report(android_report() + "\nserial=[redacted]")

    def test_rejects_unredacted_serial(self) -> None:
        with self.assertRaisesRegex(DiagnosticReportError, "Sensitive field"):
            parse_diagnostic_report(desktop_report(info={"model": "Canon EOS R6 Mark III", "serial": "SERIAL-123"}))
        with self.assertRaisesRegex(DiagnosticReportError, "Sensitive field"):
            parse_diagnostic_report(android_report(serial="SERIAL-123"))

    def test_rejects_inline_token_and_authorization(self) -> None:
        for value in (
            "token=PRIVATE-TOKEN",
            "Authorization: Bearer PRIVATE-TOKEN",
            '{"serial":"SERIAL-OTHER"}',
            '{"serial":"unknown-camera"}',
            '{"password":"PRIVATE-PASSWORD"}',
        ):
            with self.subTest(value=value), self.assertRaises(DiagnosticReportError):
                parse_diagnostic_report(android_report(lastError=value))

    def test_rejects_user_paths_and_email_addresses(self) -> None:
        for value in (
            r"C:\dev\capture.jpg",
            "C:/dev/capture.jpg",
            r"\\PRIVATE-SERVER\private\capture.jpg",
            "/" + "Users/private/capture.jpg",
            "/private/var/mobile/Containers/capture.jpg",
            "/data/user/0/dev.openeos.control/capture.jpg",
            "file:///tmp/capture.jpg",
            "private@example.com",
        ):
            with self.subTest(value=value), self.assertRaises(DiagnosticReportError):
                parse_diagnostic_report(android_report(lastError=value))

    def test_rejects_oversized_report(self) -> None:
        with self.assertRaisesRegex(DiagnosticReportError, "exceeds"):
            parse_diagnostic_report("x" * (1024 * 1024 + 1))


class ValidateDiagnosticReportTests(unittest.TestCase):
    def test_accepts_observed_required_feature_and_operator_confirmation(self) -> None:
        report = parse_diagnostic_report(android_report())
        result = validate_diagnostic_report(
            report,
            required_features=frozenset({"CAMERA_IDENTITY", "LIVE_VIEW"}),
            physical_confirmed=frozenset({"LIVE_VIEW"}),
            required_physical=frozenset({"LIVE_VIEW"}),
            now=NOW,
        )

        self.assertEqual(result.physical_confirmed, frozenset({"LIVE_VIEW"}))
        self.assertEqual(result.report.validated, report.supported & report.observed)

    def test_advertised_only_feature_does_not_satisfy_requirement(self) -> None:
        report = parse_diagnostic_report(android_report())
        with self.assertRaisesRegex(DiagnosticReportError, "advertised but not observed"):
            validate_diagnostic_report(
                report,
                required_features=frozenset({"STILL_CAPTURE"}),
                now=NOW,
            )

    def test_missing_feature_does_not_satisfy_requirement(self) -> None:
        report = parse_diagnostic_report(android_report())
        with self.assertRaisesRegex(DiagnosticReportError, "was not advertised"):
            validate_diagnostic_report(
                report,
                required_features=frozenset({"MEDIA_DELETE"}),
                now=NOW,
            )

    def test_rejects_inconsistent_validation_counts(self) -> None:
        report = parse_diagnostic_report(android_report(advertisedFeatureCount="99"))
        with self.assertRaisesRegex(DiagnosticReportError, "advertisedFeatureCount"):
            validate_diagnostic_report(report, now=NOW)

    def test_rejects_observed_without_advertisement(self) -> None:
        report = parse_diagnostic_report(
            android_report(
                observedFeatures="CAMERA_IDENTITY, MEDIA_DELETE",
                observedFeatureCount="2",
                validatedAdvertisedFeatureCount="1",
                unverifiedAdvertisedFeatures="BATTERY_STATUS, LIVE_VIEW, LIVE_VIEW_JPEG_POLLING, STILL_CAPTURE",
                observedWithoutAdvertisement="MEDIA_DELETE",
            )
        )
        with self.assertRaisesRegex(DiagnosticReportError, "not advertised"):
            validate_diagnostic_report(report, now=NOW)

    def test_rejects_stale_future_and_truncated_reports(self) -> None:
        stale = parse_diagnostic_report(android_report(generatedAt="2026-05-01T00:00:00Z"))
        future = parse_diagnostic_report(android_report(generatedAt="2026-07-30T12:06:00Z"))
        truncated = parse_diagnostic_report(android_report(capabilityEvidenceTruncated="true"))

        with self.assertRaisesRegex(DiagnosticReportError, "days old"):
            validate_diagnostic_report(stale, now=NOW)
        with self.assertRaisesRegex(DiagnosticReportError, "future"):
            validate_diagnostic_report(future, now=NOW)
        with self.assertRaisesRegex(DiagnosticReportError, "Truncated"):
            validate_diagnostic_report(truncated, now=NOW)

    def test_rejects_wrong_model_transport_and_unknown_version(self) -> None:
        wrong_model = parse_diagnostic_report(android_report(camera="Canon EOS R5"))
        wrong_transport = parse_diagnostic_report(android_report())
        unknown_version = parse_diagnostic_report(android_report(productVersion="unknown"))

        with self.assertRaisesRegex(DiagnosticReportError, "expected"):
            validate_diagnostic_report(wrong_model, now=NOW)
        with self.assertRaisesRegex(DiagnosticReportError, "not one of"):
            validate_diagnostic_report(
                wrong_transport,
                expected_transports=frozenset({"USB_PTP"}),
                now=NOW,
            )
        with self.assertRaisesRegex(DiagnosticReportError, "productVersion"):
            validate_diagnostic_report(unknown_version, now=NOW)

    def test_rejects_non_physical_transport_and_non_integral_json_counts(self) -> None:
        non_physical = parse_diagnostic_report(android_report(transport="SIMULATOR"))
        with self.assertRaisesRegex(DiagnosticReportError, "physical-camera report transport"):
            validate_diagnostic_report(non_physical, now=NOW)

        with self.assertRaisesRegex(DiagnosticReportError, "reportSchema must be an integer"):
            parse_diagnostic_report(desktop_report(reportSchema=1.9))

    def test_physical_confirmation_requires_observed_feature(self) -> None:
        report = parse_diagnostic_report(android_report())
        with self.assertRaisesRegex(DiagnosticReportError, "requires an advertised and observed"):
            validate_diagnostic_report(
                report,
                physical_confirmed=frozenset({"STILL_CAPTURE"}),
                now=NOW,
            )


class RenderDiagnosticReportTests(unittest.TestCase):
    def setUp(self) -> None:
        report = parse_diagnostic_report(desktop_report())
        self.result = validate_diagnostic_report(
            report,
            required_features=frozenset({"CAMERA_IDENTITY", "LIVE_VIEW"}),
            physical_confirmed=frozenset({"LIVE_VIEW"}),
            now=NOW,
        )

    def test_markdown_distinguishes_observed_from_operator_confirmed(self) -> None:
        output = render_markdown(self.result)

        self.assertIn("Observed this session", output)
        self.assertIn("Operator-confirmed", output)
        self.assertIn("does not, by itself, prove an external physical effect", output)
        self.assertNotIn("usb:001,007", output)
        self.assertNotIn("{\"level\"", output)

    def test_json_contains_only_validation_summary(self) -> None:
        output = json.loads(render_json(self.result))

        self.assertTrue(output["accepted"])
        self.assertEqual(output["transport"], "DESKTOP_BRIDGE_LIBGPHOTO2")
        self.assertNotIn("camera", output)
        self.assertNotIn("bridge", output)

    def test_cli_writes_markdown_and_refuses_overwrite(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "diagnostic-report.txt"
            output = root / "evidence.md"
            generated_at = datetime.now(UTC).isoformat().replace("+00:00", "Z")
            source.write_text(android_report(generatedAt=generated_at), encoding="utf-8")

            self.assertEqual(
                main(
                    [
                        str(source),
                        "--max-age-days",
                        "0",
                        "--require",
                        "LIVE_VIEW",
                        "--format",
                        "markdown",
                        "--output",
                        str(output),
                    ]
                ),
                0,
            )
            self.assertIn("Automated report validation: **Passed**", output.read_text(encoding="utf-8"))
            with redirect_stderr(StringIO()):
                self.assertEqual(main([str(source), "--max-age-days", "0", "--output", str(output)]), 2)
                self.assertEqual(main([str(source), "--max-age-days", "nan"]), 2)


if __name__ == "__main__":
    unittest.main()
