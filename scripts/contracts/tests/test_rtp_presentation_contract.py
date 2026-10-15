"""Shared evidence-only contract; no claim of native decoder/render execution."""
import json
import math
from pathlib import Path

import unittest

ROOT = Path(__file__).resolve().parents[3]
CASES = json.loads((ROOT / "contracts/rtp-presentation/v1/monitor-vectors.json").read_text())["cases"]


def evaluate(value):
    required = ("active", "sameOwner", "sameRenderer", "stopPending")
    live = all(value.get(key) is True for key in required) and value.get("terminal") is False
    foreground = value.get("foregroundRequired") is False or (
        value.get("foregroundRequired") is True and value.get("foreground") is True
    )
    ids = [value.get(key) for key in ("sampleFrame", "currentFrame", "sampleLut", "currentLut")]
    integers = all(type(number) is int and 0 <= number <= 9007199254740991 for number in ids)
    identity = integers and ids[0] > 0 and ids[0] == ids[1] and ids[2] == ids[3]
    ages = [value.get(key) for key in ("sampleAgeMs", "frameAgeMs", "maxAgeMs")]
    finite = all(type(number) in (int, float) and math.isfinite(number) for number in ages)
    fresh = finite and 0 < ages[2] <= 5000 and 0 <= ages[0] <= ages[2] and 0 <= ages[1] <= ages[2]
    allowed = bool(live and foreground and identity and fresh)
    levels = {"decoded-output": 1, "surface-submitted": 2, "presentation-confirmed": 3}
    level = levels.get(value.get("evidence"), 0)
    return {
        "canPublishDecodedAnalysis": allowed and level >= 1,
        "canPublishSubmittedAnalysis": allowed and level >= 2,
        "canClaimPresentation": allowed and level >= 3,
    }


class RTPPresentationContractTests(unittest.TestCase):
    def test_shared_monitor_evidence_vectors(self):
        for case in CASES:
            with self.subTest(case=case["name"]):
                self.assertEqual(evaluate(case["input"]), case["expected"])

    def test_unique_names_and_evidence_levels(self):
        self.assertEqual(len({case["name"] for case in CASES}), len(CASES))
        self.assertGreaterEqual({case["input"].get("evidence") for case in CASES}, {
            "decoded-output", "surface-submitted", "presentation-confirmed", "unknown",
            "enqueue-intent", "nonempty-buffer", "pixel-copy",
        })

    def test_non_json_nonfinite_producer_values_fail_closed(self):
        for field in ("sampleAgeMs", "frameAgeMs", "maxAgeMs"):
            for number in (float("nan"), float("inf"), float("-inf")):
                with self.subTest(field=field, number=number):
                    value = dict(CASES[2]["input"])
                    value[field] = number
                    self.assertFalse(any(evaluate(value).values()))


if __name__ == "__main__":
    unittest.main()
