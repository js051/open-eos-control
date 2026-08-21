"""Validate and package the versioned Camera Import contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]
CONTRACT_DIR = ROOT / "contracts" / "camera-import" / "v1"
LOCK_NAME = "contract-lock.json"
CONTRACT_ID = "dev.openeos.camera-import"
WIRE_VERSION = "1.0"
OPEN_EOS_PROVIDER_ID = "dev.openeos.control"
MINIMUM_OPEN_EOS_PROVIDER_VERSION = (0, 5, 0)
SCHEMA_BY_PREFIX = {
    "media-": "media-descriptor.schema.json",
    "representation-request-": "representation-request.schema.json",
    "transfer-": "transfer-event.schema.json",
    "receipt-": "import-receipt.schema.json",
}
FIXED_ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
OPAQUE_ID_FIELDS = {
    "provider_id",
    "session_id",
    "media_id",
    "capture_correlation_id",
    "source_revision",
    "camera_id",
    "storage_id",
    "capture_group_hint",
    "transfer_id",
    "sanitized_evidence_id",
    "import_id",
    "asset_id",
    "capture_group_id",
}
IPV4 = re.compile(r"(?:^|[^0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?:$|[^0-9])")
SEMANTIC_VERSION = re.compile(r"^([0-9]+)\.([0-9]+)\.([0-9]+)(?:[-+][0-9A-Za-z.-]+)?$")


class ContractError(RuntimeError):
    """Raised when contract sources, fixtures, or artifacts are invalid."""


FORMAT_CHECKER = FormatChecker()


@FORMAT_CHECKER.checks("date-time", raises=ValueError)
def is_offset_date_time(value: object) -> bool:
    if not isinstance(value, str):
        return True
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed.tzinfo is not None and parsed.utcoffset() is not None


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ContractError(f"Invalid JSON in {path}: {error}") from error


def contract_artifact_version(contract_dir: Path = CONTRACT_DIR) -> str:
    version = (contract_dir / "VERSION").read_text(encoding="utf-8").strip()
    if re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) is None:
        raise ContractError(f"Invalid contract artifact version: {version!r}")
    return version


def artifact_name(contract_dir: Path = CONTRACT_DIR) -> str:
    return f"open-eos-camera-import-contract-{contract_artifact_version(contract_dir)}.zip"


def packaged_source_paths(contract_dir: Path = CONTRACT_DIR) -> list[Path]:
    paths = [
        path
        for path in contract_dir.rglob("*")
        if path.is_file() and path.name != LOCK_NAME
    ]
    if not paths:
        raise ContractError("Camera Import contract has no source files.")
    for path in paths:
        if path.is_symlink():
            raise ContractError(f"Contract source must not be a symlink: {path}")
    return sorted(paths, key=lambda path: path.relative_to(contract_dir).as_posix())


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def lock_payload(contract_dir: Path = CONTRACT_DIR) -> dict[str, Any]:
    return {
        "schema": 1,
        "contract_id": CONTRACT_ID,
        "artifact_version": contract_artifact_version(contract_dir),
        "wire_version": WIRE_VERSION,
        "files": {
            path.relative_to(contract_dir).as_posix(): {
                "sha256": digest_file(path),
                "size": path.stat().st_size,
            }
            for path in packaged_source_paths(contract_dir)
        },
    }


def write_lock(contract_dir: Path = CONTRACT_DIR) -> Path:
    output = contract_dir / LOCK_NAME
    output.write_text(
        json.dumps(lock_payload(contract_dir), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    return output


def verify_lock(contract_dir: Path = CONTRACT_DIR) -> None:
    lock_path = contract_dir / LOCK_NAME
    if not lock_path.is_file() or lock_path.is_symlink():
        raise ContractError(f"Missing regular {LOCK_NAME}.")
    expected = lock_payload(contract_dir)
    actual = load_json(lock_path)
    if actual != expected:
        raise ContractError(f"{LOCK_NAME} is stale; run the contract lock command.")


def schema_for_fixture(path: Path, schemas: dict[str, Draft202012Validator]) -> Draft202012Validator:
    for prefix, schema_name in SCHEMA_BY_PREFIX.items():
        if path.name.startswith(prefix):
            return schemas[schema_name]
    raise ContractError(f"Fixture name does not identify a schema: {path.name}")


def require_safe_semantics(payload: dict[str, Any], fixture: Path) -> None:
    for field in OPAQUE_ID_FIELDS:
        value = payload.get(field)
        if value is None:
            continue
        if not isinstance(value, str):
            raise ContractError(f"{fixture}: {field} must be a string or null.")
        if "://" in value or "/" in value or "\\" in value or "@" in value or IPV4.search(value):
            raise ContractError(f"{fixture}: {field} contains a locator or private identity.")

    filename = payload.get("filename")
    if isinstance(filename, str) and Path(filename).name != filename:
        raise ContractError(f"{fixture}: filename must be a basename.")

    if payload.get("resume_supported") is True and payload.get("range_supported") is not True:
        raise ContractError(f"{fixture}: resume requires range support.")

    if "provider_version" in payload and payload.get("provider_id") == OPEN_EOS_PROVIDER_ID:
        provider_version = str(payload.get("provider_version", ""))
        match = SEMANTIC_VERSION.fullmatch(provider_version)
        core = tuple(map(int, match.groups())) if match is not None else None
        is_minimum_prerelease = core == MINIMUM_OPEN_EOS_PROVIDER_VERSION and "-" in provider_version.split("+", 1)[0]
        if core is None or core < MINIMUM_OPEN_EOS_PROVIDER_VERSION or is_minimum_prerelease:
            raise ContractError(f"{fixture}: Open EOS provider version is below 0.5.0.")

    transferred = payload.get("bytes_transferred")
    total = payload.get("total_bytes")
    if isinstance(transferred, int) and isinstance(total, int) and transferred > total:
        raise ContractError(f"{fixture}: bytes_transferred exceeds total_bytes.")

    if payload.get("state") == "COMPLETED":
        received = payload.get("received_byte_length")
        if received != transferred or (total is not None and received != total):
            raise ContractError(f"{fixture}: completed byte lengths do not agree.")

    error_code = payload.get("safe_error_code")
    if isinstance(error_code, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{2,63}", error_code) is None:
        raise ContractError(f"{fixture}: safe_error_code contains unsafe detail.")


def load_validators(contract_dir: Path = CONTRACT_DIR) -> dict[str, Draft202012Validator]:
    validators: dict[str, Draft202012Validator] = {}
    for schema_name in SCHEMA_BY_PREFIX.values():
        schema = load_json(contract_dir / schema_name)
        try:
            Draft202012Validator.check_schema(schema)
        except Exception as error:
            raise ContractError(f"Invalid JSON Schema {schema_name}: {error}") from error
        properties = set(schema.get("properties", {}))
        required = set(schema.get("required", []))
        if properties != required:
            raise ContractError(f"{schema_name} must require its complete top-level shape.")
        validators[schema_name] = Draft202012Validator(schema, format_checker=FORMAT_CHECKER)
    return validators


def fixture_errors(path: Path, validator: Draft202012Validator) -> list[str]:
    payload = load_json(path)
    if not isinstance(payload, dict):
        return ["top-level value is not an object"]
    errors = [error.message for error in validator.iter_errors(payload)]
    try:
        require_safe_semantics(payload, path)
    except ContractError as error:
        errors.append(str(error))
    return errors


def validate_contract(contract_dir: Path = CONTRACT_DIR) -> None:
    compatibility = load_json(contract_dir / "compatibility.json")
    if compatibility.get("contract_id") != CONTRACT_ID:
        raise ContractError("Compatibility matrix has the wrong contract ID.")
    if compatibility.get("artifact_version") != contract_artifact_version(contract_dir):
        raise ContractError("Compatibility matrix has a stale artifact version.")
    if compatibility.get("wire_version") != WIRE_VERSION:
        raise ContractError("Compatibility matrix has the wrong wire version.")
    if compatibility.get("consumer_policy", {}).get("semantic_validation") != "REQUIRED":
        raise ContractError("Compatibility matrix must require semantic validation.")

    semantic_rules = load_json(contract_dir / "semantic-rules.json")
    expected_rule_ids = {
        "identity.provider_generated",
        "provider.minimum_version",
        "media.resume_requires_range",
        "representation.visual_target_only",
        "transfer.bytes_within_total",
        "transfer.completed_lengths_match",
        "transfer.terminal_evidence",
        "transfer.cancel_pending_is_not_cancelled",
        "receipt.committed_evidence",
        "receipt.no_source_mutation_authority",
        "duplicate.full_strong_hash_only",
    }
    actual_rule_ids = {rule.get("id") for rule in semantic_rules.get("rules", [])}
    if actual_rule_ids != expected_rule_ids:
        raise ContractError("Semantic rule inventory is incomplete or contains unknown rules.")

    validators = load_validators(contract_dir)
    valid_fixtures = sorted((contract_dir / "fixtures" / "valid").glob("*.json"))
    invalid_fixtures = sorted((contract_dir / "fixtures" / "invalid").glob("*.json"))
    if not valid_fixtures or not invalid_fixtures:
        raise ContractError("Both valid and invalid contract fixtures are required.")

    for fixture in valid_fixtures:
        errors = fixture_errors(fixture, schema_for_fixture(fixture, validators))
        if errors:
            raise ContractError(f"Valid fixture {fixture.name} failed: {'; '.join(errors)}")
    for fixture in invalid_fixtures:
        errors = fixture_errors(fixture, schema_for_fixture(fixture, validators))
        if not errors:
            raise ContractError(f"Invalid fixture {fixture.name} was accepted.")
    verify_lock(contract_dir)


def build_artifact(output_dir: Path, contract_dir: Path = CONTRACT_DIR) -> Path:
    validate_contract(contract_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / artifact_name(contract_dir)
    temporary = output.with_suffix(".zip.tmp")
    if output.exists():
        output.unlink()
    if temporary.exists():
        temporary.unlink()
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED) as archive:
        for path in [*packaged_source_paths(contract_dir), contract_dir / LOCK_NAME]:
            relative = path.relative_to(contract_dir).as_posix()
            info = zipfile.ZipInfo(f"camera-import-v1/{relative}", FIXED_ZIP_TIMESTAMP)
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, path.read_bytes())
    shutil.move(temporary, output)
    return output


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("lock")
    subparsers.add_parser("validate")
    build = subparsers.add_parser("build")
    build.add_argument("--output-dir", type=Path, default=ROOT / "dist")
    return parser


def main() -> None:
    args = build_parser().parse_args()
    try:
        if args.command == "lock":
            result: object = str(write_lock())
        elif args.command == "validate":
            validate_contract()
            result = {"contract": CONTRACT_ID, "version": contract_artifact_version(), "valid": True}
        else:
            output = build_artifact(args.output_dir)
            result = {"artifact": str(output), "sha256": digest_file(output)}
    except (ContractError, OSError) as error:
        raise SystemExit(str(error)) from error
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
