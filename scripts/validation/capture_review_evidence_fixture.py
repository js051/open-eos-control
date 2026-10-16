"""Emit fixed Kotlin DATA only; does not replace production review or its JUnit tests."""

import hashlib
import json
from pathlib import Path


def emit_capture_review_evidence_provider(root: Path, output: Path) -> tuple[Path, dict]:
    source = root / "contracts/capture-review-evidence/v1/review-evidence-vectors.json"
    data = source.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if len(data) != 25745 or digest != "aab83ace169997c2f9788ea45f2863513f0a0d99e3211143c0e60475eb7ec7b1":
        raise ValueError("Unexpected fixed review evidence contract")
    fixture = json.loads(data)

    def q(value):
        return "null" if value is None else json.dumps(value, ensure_ascii=True).replace("$", "\\$")

    def values(items, constructor="listOf"):
        return constructor + "(" + ", ".join(q(value) for value in items) + ")"

    def expected(value):
        return (f"CaptureReviewEvidenceExpected({q(value['observation'])}, {value['successfulReads']}, "
                f"{value['failedReads']}, {q(value['lastCompletedRead'])}, {q(value['candidateId'])})")

    cases = [
        f"CaptureReviewEvidenceVector({q(v['id'])}, {q(v['intent'])}, {values(v['reads'])}, {expected(v['expected'])})"
        for v in fixture["cases"]
    ]
    retries = [
        f"CaptureReviewEvidenceRetryVector({q(v['id'])}, {values(v['first'])}, "
        f"{values(v['second'])}, {expected(v['expected'])})"
        for v in fixture["retryCases"]
    ]
    retirements = [
        f"CaptureReviewEvidenceRetirementVector({q(v['id'])}, {v['completedReads']}, "
        f"{str(v['publish']).lower()}, {str(v['preservePrevious']).lower()})"
        for v in fixture["retirementCases"]
    ]
    output_path = output / "OfflineCaptureReviewEvidenceProvider.kt"
    output_path.write_text("\n".join([
        "// Fixed DATA provider only. Formal org.json/resource loader is NOT_RUN.",
        "package dev.openeos.control.ui",
        "internal fun loadCaptureReviewEvidenceFixture() = CaptureReviewEvidenceFixture(",
        f"schemaVersion = {fixture['schemaVersion']}, previousIds = {values(fixture['previousIds'], 'setOf')},",
        "retryDelaysMillis = listOf(" + ", ".join(f"{v}L" for v in fixture["retryDelaysMillis"]) + "),",
        f"maximumReads = {fixture['maximumReads']},",
        "cases = listOf(\n" + ",\n".join(cases) + "),",
        "retryCases = listOf(\n" + ",\n".join(retries) + "),",
        "retirementCases = listOf(\n" + ",\n".join(retirements) + "),", ")", "",
    ]))
    return output_path, {
        "sha256": digest, "cases": len(cases), "retries": len(retries), "retirements": len(retirements),
    }
