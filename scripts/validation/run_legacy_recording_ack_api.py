#!/usr/bin/env python3
"""Emit authenticated production legacy REC receipt evidence; no sockets or physical camera."""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "bridge"))

from tests.test_ccapi_legacy_recording_receipts import PARTIAL, VARIANTS, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--phase", choices=("baseline", "current"), required=True,
        help="Expected results only; baseline requires the original production source tree.",
    )
    args = parser.parse_args()
    for method in ("POST", "PUT"):
        for operation in ("start", "stop"):
            cases = [(variant, True, False) for variant in VARIANTS]
            cases += [(variant, seed, True) for variant in PARTIAL for seed in (False, True)]
            for variant, seed_cache, apply_partial in cases:
                result = run(method, operation, variant, seed_cache=seed_cache, apply_partial=apply_partial)
                accepted = variant[2] if args.phase == "current" else variant[1].status != 503
                expected_cache = operation == "start" if accepted else result["initial_cache"]
                assert result["api_status"] == (200 if accepted else 502), result
                assert result["status_recording"] is expected_cache, result
                assert result["feature_observed"] is (accepted or seed_cache), result
                if not accepted:
                    error = "CCAPI_HTTP_ERROR" if variant[1].status == 503 else "RECORDING_COMMAND_UNCONFIRMED"
                    assert result["api_result"]["error"]["code"] == error, result
                assert result["mutation_count"] == 1, result
                print(json.dumps(result, separators=(",", ":")))


if __name__ == "__main__":
    main()
