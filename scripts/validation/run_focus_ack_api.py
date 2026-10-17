#!/usr/bin/env python3
"""Emit production API receipt evidence. Run with PYTHONPATH=bridge; no network."""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "bridge"))

from tests.test_ccapi_focus_ack_receipts import ROUTES, VARIANTS, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--phase",
        choices=("baseline", "current"),
        required=True,
        help="Expected result only; baseline must run against the original production source tree.",
    )
    args = parser.parse_args()
    for route in ROUTES:
        for variant in VARIANTS:
            result = run(route, variant)
            accepted = (
                variant[2] if args.phase == "current" else variant[1].status != 503
            )
            assert result["api_status"] == (200 if accepted else 502), result
            assert result["feature_observed"] is accepted, result
            assert result["mutation_calls_after_status_and_capabilities"] == 1
            print(json.dumps(result, separators=(",", ":")))


if __name__ == "__main__":
    main()
