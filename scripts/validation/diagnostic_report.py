from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

SUPPORTED_REPORT_SCHEMA = 1
DEFAULT_EXPECTED_MODEL = "Canon EOS R6 Mark III"
MAX_REPORT_BYTES = 1024 * 1024
FUTURE_CLOCK_TOLERANCE = timedelta(minutes=5)
SUPPORTED_TRANSPORTS = frozenset(
    {
        "CCAPI_NETWORK",
        "USB_PTP",
        "DESKTOP_BRIDGE",
        "DESKTOP_BRIDGE_LIBGPHOTO2",
        "DESKTOP_BRIDGE_CCAPI",
    }
)

_FEATURE_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{1,63}$")
_SAFE_REDACTED_VALUES = {"", "[redacted]", "redacted", "unknown", "none", "null"}
_INLINE_SECRET_PATTERN = re.compile(
    r"(?i)\b(?:password|token)\s*[=:]\s*(?!\[redacted\]|unknown\b|none\b|null\b)([^\s,;&\"\\]+)"
)
_AUTHORIZATION_PATTERN = re.compile(
    r"(?i)\bauthorization\s*[=:]\s*(?!(?:bearer|basic)?\s*\[redacted\])"
    r"(?:bearer|basic)?\s*([^\s,;\"\\]+)"
)
_SENSITIVE_FIELD_PATTERN = re.compile(
    r"(?i)[\"']?(?:[a-z0-9_]*serial(?:number)?|password|token|authorization|credential|secret)[\"']?"
    r"\s*[:=]\s*[\"']?"
    r"(?!(?:\[redacted\]|redacted|unknown|none|null)(?:[\"'\s,;&}\]]|$))"
    r"([^\s,;&\"'}\]]+)"
)
_URL_USERINFO_PATTERN = re.compile(r"(?i)https?://[^/@\s]+@")
_LOCAL_USER_PATH_PATTERNS = (
    re.compile(r"(?i)\b[A-Z]:[\\/][^\s\"']+"),
    re.compile(r"(?i)\\\\[^\\\r\n\s\"']+\\[^\r\n,;\"'}\]]+"),
    re.compile(
        r"(?<![A-Za-z0-9_])/(?:Users|home|tmp|var/folders|private/var|data/user|storage/emulated|mnt/[a-z])/"
        r"[^\s\"']+"
    ),
    re.compile(r"(?i)\bfile://[^\s\"']+"),
)
_EMAIL_PATTERN = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")


class DiagnosticReportError(ValueError):
    """Raised when a report cannot be accepted as validation evidence."""

    def __init__(self, issues: Sequence[str]):
        self.issues = tuple(issues)
        super().__init__("; ".join(self.issues))


@dataclass(frozen=True)
class DeclaredValidation:
    advertised_count: int
    observed_count: int
    validated_count: int
    unverified_advertised: frozenset[str]
    observed_without_advertisement: frozenset[str]


@dataclass(frozen=True)
class DiagnosticReport:
    source_format: str
    product: str
    report_schema: int
    generated_at: datetime
    product_version: str
    camera_model: str
    transport: str
    supported: frozenset[str]
    observed: frozenset[str]
    evidence_truncated: bool
    declared_validation: DeclaredValidation
    report_sha256: str

    @property
    def validated(self) -> frozenset[str]:
        return self.supported & self.observed

    @property
    def unverified(self) -> frozenset[str]:
        return self.supported - self.observed

    @property
    def observed_without_advertisement(self) -> frozenset[str]:
        return self.observed - self.supported


@dataclass(frozen=True)
class ValidationResult:
    report: DiagnosticReport
    required_features: frozenset[str]
    physical_confirmed: frozenset[str]
    required_physical: frozenset[str]
    age: timedelta


def parse_diagnostic_report(text: str) -> DiagnosticReport:
    encoded = text.encode("utf-8")
    if len(encoded) > MAX_REPORT_BYTES:
        raise DiagnosticReportError([f"Report exceeds the {MAX_REPORT_BYTES}-byte limit."])
    if "\x00" in text:
        raise DiagnosticReportError(["Report contains a NUL byte."])
    stripped = text.strip()
    if not stripped:
        raise DiagnosticReportError(["Report is empty."])

    _validate_privacy(stripped)
    digest_input = stripped.replace("\r\n", "\n").replace("\r", "\n").encode("utf-8")
    report_sha256 = hashlib.sha256(digest_input).hexdigest()
    if stripped.startswith("{"):
        return _parse_json_report(stripped, report_sha256)
    return _parse_key_value_report(stripped, report_sha256)


def validate_diagnostic_report(
    report: DiagnosticReport,
    *,
    expected_model: str = DEFAULT_EXPECTED_MODEL,
    expected_transports: frozenset[str] = frozenset(),
    required_features: frozenset[str] = frozenset({"CAMERA_IDENTITY"}),
    physical_confirmed: frozenset[str] = frozenset(),
    required_physical: frozenset[str] = frozenset(),
    max_age: timedelta | None = timedelta(days=30),
    allow_truncated_evidence: bool = False,
    now: datetime | None = None,
) -> ValidationResult:
    issues: list[str] = []
    now = now or datetime.now(UTC)
    if now.tzinfo is None:
        now = now.replace(tzinfo=UTC)
    now = now.astimezone(UTC)
    generated_at = report.generated_at.astimezone(UTC)
    age = now - generated_at

    if report.report_schema != SUPPORTED_REPORT_SCHEMA:
        issues.append(
            f"Unsupported reportSchema {report.report_schema}; expected {SUPPORTED_REPORT_SCHEMA}."
        )
    if _normalize_model(report.camera_model) != _normalize_model(expected_model):
        issues.append(f"Camera model is {report.camera_model!r}; expected {expected_model!r}.")
    if report.product_version.strip().lower() in _SAFE_REDACTED_VALUES:
        issues.append("productVersion must identify the tested build.")
    if report.transport.upper() not in SUPPORTED_TRANSPORTS:
        issues.append(
            f"Transport {report.transport!r} is not a supported physical-camera report transport."
        )
    elif expected_transports and report.transport.upper() not in expected_transports:
        issues.append(
            f"Transport {report.transport!r} is not one of: {', '.join(sorted(expected_transports))}."
        )
    if generated_at - now > FUTURE_CLOCK_TOLERANCE:
        issues.append("generatedAt is more than five minutes in the future.")
    elif max_age is not None and age > max_age:
        issues.append(
            f"Report is {age.days} days old; maximum accepted age is {max_age.total_seconds() / 86400:g} days."
        )
    if report.evidence_truncated and not allow_truncated_evidence:
        issues.append("capabilityEvidenceTruncated is true; a complete discovery report is required.")
    if not report.supported:
        issues.append("supported is empty; this is not a connected-camera validation report.")

    expected_unverified = report.unverified
    expected_observed_without = report.observed_without_advertisement
    declared = report.declared_validation
    if declared.advertised_count != len(report.supported):
        issues.append("advertisedFeatureCount does not match the supported feature set.")
    if declared.observed_count != len(report.observed):
        issues.append("observedFeatureCount does not match observedFeatures.")
    if declared.validated_count != len(report.validated):
        issues.append("validatedAdvertisedFeatureCount does not match supported intersect observedFeatures.")
    if declared.unverified_advertised != expected_unverified:
        issues.append("unverifiedAdvertisedFeatures does not match supported minus observedFeatures.")
    if declared.observed_without_advertisement != expected_observed_without:
        issues.append("observedWithoutAdvertisement does not match observedFeatures minus supported.")
    if expected_observed_without:
        issues.append(
            "Observed features were not advertised by the active backend: "
            + ", ".join(sorted(expected_observed_without))
            + "."
        )

    for feature in sorted(required_features):
        _validate_feature_name(feature, issues)
        if feature not in report.supported:
            issues.append(f"Required feature {feature} was not advertised by the active backend.")
        elif feature not in report.observed:
            issues.append(f"Required feature {feature} was advertised but not observed successfully this session.")

    for feature in sorted(physical_confirmed | required_physical):
        _validate_feature_name(feature, issues)
        if feature not in report.validated:
            issues.append(f"Physical confirmation {feature} requires an advertised and observed feature first.")
    for feature in sorted(required_physical - physical_confirmed):
        issues.append(f"Required physical outcome {feature} was not operator-confirmed.")

    if issues:
        raise DiagnosticReportError(issues)
    return ValidationResult(
        report=report,
        required_features=required_features,
        physical_confirmed=physical_confirmed,
        required_physical=required_physical,
        age=age,
    )


def render_summary(result: ValidationResult) -> str:
    report = result.report
    lines = [
        f"PASS: {report.camera_model} diagnostic report",
        f"product={report.product}",
        f"productVersion={report.product_version}",
        f"transport={report.transport}",
        f"generatedAt={_format_datetime(report.generated_at)}",
        f"validatedAdvertisedFeatures={len(report.validated)}/{len(report.supported)}",
        f"requiredFeatures={_format_features(result.required_features)}",
        f"physicalConfirmed={_format_features(result.physical_confirmed)}",
        f"reportSha256={report.report_sha256}",
    ]
    return "\n".join(lines)


def render_json(result: ValidationResult) -> str:
    report = result.report
    body = {
        "accepted": True,
        "cameraModel": report.camera_model,
        "evidenceTruncated": report.evidence_truncated,
        "generatedAt": _format_datetime(report.generated_at),
        "observedFeatures": sorted(report.observed),
        "physicalConfirmed": sorted(result.physical_confirmed),
        "product": report.product,
        "productVersion": report.product_version,
        "reportSchema": report.report_schema,
        "reportSha256": report.report_sha256,
        "requiredFeatures": sorted(result.required_features),
        "requiredPhysical": sorted(result.required_physical),
        "sourceFormat": report.source_format,
        "supportedFeatures": sorted(report.supported),
        "transport": report.transport,
        "unverifiedAdvertisedFeatures": sorted(report.unverified),
        "validatedAdvertisedFeatures": sorted(report.validated),
    }
    return json.dumps(body, indent=2, sort_keys=True, ensure_ascii=False)


def render_markdown(result: ValidationResult) -> str:
    report = result.report
    features = sorted(report.supported | report.observed | result.required_features | result.physical_confirmed)
    rows = []
    for feature in features:
        advertised = feature in report.supported
        observed = feature in report.observed
        confirmed = feature in result.physical_confirmed
        if confirmed:
            status = "Observed and operator-confirmed"
        elif observed and advertised:
            status = "Observed"
        elif advertised:
            status = "Advertised only"
        else:
            status = "Unavailable"
        rows.append(
            f"| `{feature}` | {_yes_no(advertised)} | {_yes_no(observed)} | "
            f"{_confirmed_label(confirmed)} | {status} |"
        )

    return "\n".join(
        [
            f"# {report.camera_model} Device Validation Evidence",
            "",
            "Automated report validation: **Passed**",
            "",
            f"- Product: `{report.product}` `{report.product_version}`",
            f"- Transport: `{report.transport}`",
            f"- Generated: `{_format_datetime(report.generated_at)}`",
            f"- Report schema: `{report.report_schema}`",
            f"- Sanitized report SHA-256: `{report.report_sha256}`",
            "",
            "## Capability Evidence",
            "",
            "| Capability | Advertised | Observed this session | Physical outcome | Result |",
            "| --- | --- | --- | --- | --- |",
            *rows,
            "",
            "## Interpretation",
            "",
            (
                "`Observed this session` means the active product backend returned a successful command or valid data "
                "payload. It is stronger than advertisement but does not, by itself, prove an external physical effect."
            ),
            "",
            (
                "`Operator-confirmed` is an explicit human attestation that the expected camera-side result was seen. "
                "Capabilities without that label must not be described as physically validated."
            ),
            "",
            (
                "The source diagnostic report passed schema, capability-set consistency, freshness, model, and privacy "
                "checks. Raw endpoints, camera serials, credentials, local paths, and raw status payloads are "
                "intentionally omitted."
            ),
            "",
            "The SHA-256 value identifies the exact private input report; it is not a signature or remote attestation.",
        ]
    )


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Verify a redacted Open EOS Control diagnostic report and emit auditable evidence."
    )
    parser.add_argument("report", help="Diagnostic report path, or - to read from standard input.")
    parser.add_argument("--expect-model", default=DEFAULT_EXPECTED_MODEL)
    parser.add_argument(
        "--expect-transport",
        action="append",
        default=[],
        help="Accepted transport value. Repeat to accept multiple transports.",
    )
    parser.add_argument(
        "--require",
        action="append",
        default=[],
        metavar="FEATURE[,FEATURE...]",
        help="Require each feature to be both advertised and observed. CAMERA_IDENTITY is always required.",
    )
    parser.add_argument(
        "--physical-confirmed",
        action="append",
        default=[],
        metavar="FEATURE[,FEATURE...]",
        help="Record an operator-confirmed camera-side outcome. Repeat as needed.",
    )
    parser.add_argument(
        "--require-physical",
        action="append",
        default=[],
        metavar="FEATURE[,FEATURE...]",
        help="Fail unless the feature is also supplied through --physical-confirmed.",
    )
    parser.add_argument(
        "--max-age-days",
        type=float,
        default=30,
        help="Maximum report age in days. Use 0 to disable the age limit (default: 30).",
    )
    parser.add_argument("--allow-truncated-evidence", action="store_true")
    parser.add_argument("--format", choices=("summary", "json", "markdown"), default="summary")
    parser.add_argument("--output", type=Path, help="Write the generated evidence to this path.")
    parser.add_argument("--force", action="store_true", help="Replace an existing output file.")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_argument_parser()
    args = parser.parse_args(argv)
    try:
        if not math.isfinite(args.max_age_days) or not 0 <= args.max_age_days <= 36_500:
            raise DiagnosticReportError(["--max-age-days must be between 0 and 36500."])
        text = _read_report(args.report)
        report = parse_diagnostic_report(text)
        required = frozenset({"CAMERA_IDENTITY"}) | _parse_cli_features(args.require)
        confirmed = _parse_cli_features(args.physical_confirmed)
        required_physical = _parse_cli_features(args.require_physical)
        expected_transports = frozenset(value.strip().upper() for value in args.expect_transport if value.strip())
        max_age = None if args.max_age_days == 0 else timedelta(days=args.max_age_days)
        result = validate_diagnostic_report(
            report,
            expected_model=args.expect_model,
            expected_transports=expected_transports,
            required_features=required,
            physical_confirmed=confirmed,
            required_physical=required_physical,
            max_age=max_age,
            allow_truncated_evidence=args.allow_truncated_evidence,
        )
        rendered = {
            "summary": render_summary,
            "json": render_json,
            "markdown": render_markdown,
        }[args.format](result)
        _write_output(rendered, args.output, args.force)
        return 0
    except DiagnosticReportError as error:
        for issue in error.issues:
            print(f"ERROR: {issue}", file=sys.stderr)
        return 2


def _parse_json_report(text: str, report_sha256: str) -> DiagnosticReport:
    try:
        body = json.loads(text)
    except json.JSONDecodeError as error:
        raise DiagnosticReportError([f"Invalid JSON report: {error.msg}."]) from error
    if not isinstance(body, dict):
        raise DiagnosticReportError(["JSON report root must be an object."])
    _validate_json_sensitive_fields(body)

    capabilities = _require_dict(body, "capabilities")
    evidence = _require_dict(capabilities, "evidence")
    validation = _require_dict(body, "validation")
    info = _optional_dict(body.get("info"))
    camera = _optional_dict(body.get("camera"))
    supported = _parse_feature_array(capabilities.get("supported"), "capabilities.supported")
    observed = _parse_feature_array(evidence.get("observedFeatures"), "capabilities.evidence.observedFeatures")
    transport = str(camera.get("engine") or "DESKTOP_BRIDGE").strip().upper()
    if transport != "DESKTOP_BRIDGE":
        transport = f"DESKTOP_BRIDGE_{transport}"

    return DiagnosticReport(
        source_format="json",
        product=_required_text(body, "product"),
        report_schema=_required_int(body, "reportSchema"),
        generated_at=_parse_datetime(_required_text(body, "generatedAt")),
        product_version=_required_text(body, "productVersion"),
        camera_model=str(info.get("model") or camera.get("model") or "").strip(),
        transport=transport,
        supported=supported,
        observed=observed,
        evidence_truncated=_required_bool(evidence, "truncated"),
        declared_validation=DeclaredValidation(
            advertised_count=_required_int(validation, "advertisedFeatureCount"),
            observed_count=_required_int(validation, "observedFeatureCount"),
            validated_count=_required_int(validation, "validatedAdvertisedFeatureCount"),
            unverified_advertised=_parse_feature_array(
                validation.get("unverifiedAdvertisedFeatures"), "validation.unverifiedAdvertisedFeatures"
            ),
            observed_without_advertisement=_parse_feature_array(
                validation.get("observedWithoutAdvertisement"), "validation.observedWithoutAdvertisement"
            ),
        ),
        report_sha256=report_sha256,
    )


def _parse_key_value_report(text: str, report_sha256: str) -> DiagnosticReport:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines or "=" in lines[0]:
        raise DiagnosticReportError(["Text report must begin with its Open EOS Control report title."])
    if not lines[0].startswith("Open EOS Control") or "diagnostic report" not in lines[0].lower():
        raise DiagnosticReportError(["Unrecognized diagnostic report title."])

    fields: dict[str, str] = {}
    issues: list[str] = []
    for line in lines[1:]:
        if "=" not in line:
            issues.append(f"Malformed report line: {line!r}.")
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not key:
            issues.append("Report contains an empty key.")
        elif key in fields:
            issues.append(f"Report contains duplicate key {key!r}.")
        else:
            fields[key] = value.strip()
    if issues:
        raise DiagnosticReportError(issues)

    _validate_key_value_sensitive_fields(fields)
    supported = _parse_feature_text(_required_text(fields, "supported"), "supported")
    observed = _parse_feature_text(_required_text(fields, "observedFeatures"), "observedFeatures")
    return DiagnosticReport(
        source_format="key-value",
        product=lines[0].removesuffix(" diagnostic report").strip(),
        report_schema=_required_int(fields, "reportSchema"),
        generated_at=_parse_datetime(_required_text(fields, "generatedAt")),
        product_version=_required_text(fields, "productVersion"),
        camera_model=_required_text(fields, "camera"),
        transport=_required_text(fields, "transport").upper(),
        supported=supported,
        observed=observed,
        evidence_truncated=_required_bool(fields, "capabilityEvidenceTruncated"),
        declared_validation=DeclaredValidation(
            advertised_count=_required_int(fields, "advertisedFeatureCount"),
            observed_count=_required_int(fields, "observedFeatureCount"),
            validated_count=_required_int(fields, "validatedAdvertisedFeatureCount"),
            unverified_advertised=_parse_feature_text(
                _required_text(fields, "unverifiedAdvertisedFeatures"), "unverifiedAdvertisedFeatures"
            ),
            observed_without_advertisement=_parse_feature_text(
                _required_text(fields, "observedWithoutAdvertisement"), "observedWithoutAdvertisement"
            ),
        ),
        report_sha256=report_sha256,
    )


def _validate_privacy(text: str) -> None:
    issues: list[str] = []
    if _INLINE_SECRET_PATTERN.search(text):
        issues.append("Report contains an unredacted password or token.")
    if _AUTHORIZATION_PATTERN.search(text):
        issues.append("Report contains an unredacted Authorization value.")
    if _SENSITIVE_FIELD_PATTERN.search(text):
        issues.append("Sensitive field is not redacted.")
    if _URL_USERINFO_PATTERN.search(text):
        issues.append("Report contains URL user information.")
    if any(pattern.search(text) for pattern in _LOCAL_USER_PATH_PATTERNS):
        issues.append("Report contains a machine-local user path.")
    if _EMAIL_PATTERN.search(text):
        issues.append("Report contains an email address.")
    if issues:
        raise DiagnosticReportError(issues)


def _validate_json_sensitive_fields(value: Any, path: str = "root") -> None:
    issues: list[str] = []

    def visit(candidate: Any, candidate_path: str) -> None:
        if isinstance(candidate, dict):
            for key, child in candidate.items():
                child_path = f"{candidate_path}.{key}"
                normalized_key = re.sub(r"[^a-z]", "", str(key).lower())
                if (
                    "serial" in normalized_key
                    or normalized_key in {"password", "token", "authorization", "credential", "secret"}
                ) and not _is_redacted_value(child):
                    issues.append(f"Sensitive field {child_path} is not redacted.")
                visit(child, child_path)
        elif isinstance(candidate, list):
            for index, child in enumerate(candidate):
                visit(child, f"{candidate_path}[{index}]")

    visit(value, path)
    if issues:
        raise DiagnosticReportError(issues)


def _validate_key_value_sensitive_fields(fields: dict[str, str]) -> None:
    issues: list[str] = []
    for key, value in fields.items():
        normalized_key = re.sub(r"[^a-z]", "", key.lower())
        if (
            "serial" in normalized_key
            or normalized_key in {"password", "token", "authorization", "credential", "secret"}
        ) and not _is_redacted_value(value):
            issues.append(f"Sensitive field {key} is not redacted.")
    if issues:
        raise DiagnosticReportError(issues)


def _is_redacted_value(value: Any) -> bool:
    if value is None:
        return True
    if not isinstance(value, str):
        return False
    return value.strip().lower() in _SAFE_REDACTED_VALUES


def _parse_feature_array(value: Any, field: str) -> frozenset[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise DiagnosticReportError([f"{field} must be an array of feature names."])
    return _validated_feature_set(value, field)


def _parse_feature_text(value: str, field: str) -> frozenset[str]:
    if value.strip().lower() in {"", "none"}:
        return frozenset()
    return _validated_feature_set((part.strip() for part in value.split(",")), field)


def _validated_feature_set(values: Any, field: str) -> frozenset[str]:
    normalized = [str(value).strip().upper() for value in values if str(value).strip()]
    issues: list[str] = []
    for feature in normalized:
        _validate_feature_name(feature, issues, field)
    if len(normalized) != len(set(normalized)):
        issues.append(f"{field} contains duplicate feature names.")
    if issues:
        raise DiagnosticReportError(issues)
    return frozenset(normalized)


def _validate_feature_name(feature: str, issues: list[str], field: str = "feature") -> None:
    if not _FEATURE_PATTERN.fullmatch(feature):
        issues.append(f"Invalid {field} name {feature!r}.")


def _parse_datetime(value: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise DiagnosticReportError([f"generatedAt is not a valid ISO-8601 timestamp: {value!r}."]) from error
    if parsed.tzinfo is None:
        raise DiagnosticReportError(["generatedAt must include a UTC offset."])
    return parsed.astimezone(UTC)


def _required_text(mapping: dict[str, Any], key: str) -> str:
    if key not in mapping:
        raise DiagnosticReportError([f"Missing required field {key}."])
    value = mapping[key]
    if not isinstance(value, str) or not value.strip():
        raise DiagnosticReportError([f"{key} must be a non-empty string."])
    return value.strip()


def _required_int(mapping: dict[str, Any], key: str) -> int:
    if key not in mapping:
        raise DiagnosticReportError([f"Missing required field {key}."])
    value = mapping[key]
    if type(value) is int:
        parsed = value
    elif isinstance(value, str) and re.fullmatch(r"[0-9]+", value.strip()):
        parsed = int(value.strip())
    else:
        raise DiagnosticReportError([f"{key} must be an integer."])
    if parsed < 0:
        raise DiagnosticReportError([f"{key} must not be negative."])
    return parsed


def _required_bool(mapping: dict[str, Any], key: str) -> bool:
    if key not in mapping:
        raise DiagnosticReportError([f"Missing required field {key}."])
    value = mapping[key]
    if isinstance(value, bool):
        return value
    if isinstance(value, str) and value.lower() in {"true", "false"}:
        return value.lower() == "true"
    raise DiagnosticReportError([f"{key} must be true or false."])


def _require_dict(mapping: dict[str, Any], key: str) -> dict[str, Any]:
    if key not in mapping or not isinstance(mapping[key], dict):
        raise DiagnosticReportError([f"{key} must be an object."])
    return mapping[key]


def _optional_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _normalize_model(value: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"(?i)^canon\s+", "", value.strip())).lower()


def _format_datetime(value: datetime) -> str:
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _format_features(features: frozenset[str]) -> str:
    return ", ".join(sorted(features)) or "none"


def _yes_no(value: bool) -> str:
    return "Yes" if value else "No"


def _confirmed_label(value: bool) -> str:
    return "Operator-confirmed" if value else "Not recorded"


def _parse_cli_features(values: Sequence[str]) -> frozenset[str]:
    features = [part.strip().upper() for value in values for part in value.split(",") if part.strip()]
    return _validated_feature_set(features, "command-line feature")


def _read_report(path: str) -> str:
    if path == "-":
        return sys.stdin.read(MAX_REPORT_BYTES + 1)
    try:
        if Path(path).stat().st_size > MAX_REPORT_BYTES:
            raise DiagnosticReportError([f"Report exceeds the {MAX_REPORT_BYTES}-byte limit."])
        return Path(path).read_text(encoding="utf-8")
    except OSError as error:
        raise DiagnosticReportError([f"Cannot read report {path!r}: {error}."]) from error


def _write_output(value: str, output: Path | None, force: bool) -> None:
    if output is None:
        print(value)
        return
    if output.exists() and not force:
        raise DiagnosticReportError([f"Output {str(output)!r} already exists; use --force to replace it."])
    try:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(value + "\n", encoding="utf-8", newline="\n")
    except OSError as error:
        raise DiagnosticReportError([f"Cannot write output {str(output)!r}: {error}."]) from error


if __name__ == "__main__":
    raise SystemExit(main())
