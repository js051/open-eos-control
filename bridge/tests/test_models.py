import pytest
from pydantic import ValidationError

from open_eos_bridge.models import (
    CameraModelFamily,
    CameraModelPriority,
    CapabilityEvidence,
    DiscoveryAttempt,
    camera_profile,
)


def test_r6_mark_third_aliases_use_the_canonical_primary_profile() -> None:
    for model in (
        "Canon EOS R6 Mark III",
        "EOS R6 Mark III",
        "R6 Mark III",
        "R6m3",
        "R63",
        "Canon EOS-R6 Mark III",
    ):
        profile = camera_profile(model)
        assert profile.model_name == model
        assert profile.family is CameraModelFamily.EOS_R
        assert profile.priority is CameraModelPriority.PRIMARY
        assert profile.model_dump(by_alias=True, mode="json") == {
            "modelName": model,
            "family": "EOS_R",
            "priority": "PRIMARY",
        }


def test_other_camera_families_use_canonical_wire_values() -> None:
    cases = {
        "Canon EOS R5": (CameraModelFamily.EOS_R, CameraModelPriority.SUPPORTED),
        "Canon EOS M50": (CameraModelFamily.EOS_M, CameraModelPriority.SUPPORTED),
        "Canon EOS 5D": (CameraModelFamily.EOS_DSLR, CameraModelPriority.SUPPORTED),
        "Canon PowerShot G7 X": (CameraModelFamily.POWERSHOT, CameraModelPriority.RESEARCH),
        "Camera": (CameraModelFamily.UNKNOWN, CameraModelPriority.RESEARCH),
    }
    for model, expected in cases.items():
        profile = camera_profile(model)
        assert (profile.family, profile.priority) == expected


def test_discovery_trace_uses_bounded_camel_case_wire_fields() -> None:
    evidence = CapabilityEvidence(
        source="GET /ccapi/ver100/topurlfordev",
        discovery_trace=[
            DiscoveryAttempt(
                endpoint="GET /ccapi/ver100/topurlfordev",
                outcome="OPERATIONS",
                http_status=200,
                response_keys=["ver100"],
                protocol_versions=["ver100"],
                advertised_operation_count=17,
            )
        ],
    )

    assert evidence.model_dump(by_alias=True, mode="json")["discoveryTrace"] == [
        {
            "endpoint": "GET /ccapi/ver100/topurlfordev",
            "outcome": "OPERATIONS",
            "httpStatus": 200,
            "responseKeys": ["ver100"],
            "protocolVersions": ["ver100"],
            "advertisedOperationCount": 17,
            "truncated": False,
        }
    ]


@pytest.mark.parametrize(
    ("endpoint", "outcome", "response_keys", "protocol_versions"),
    [
        ("GET https://attacker.invalid/ccapi", "OPERATIONS", [], []),
        ("GET /ccapi", "operations", [], []),
        ("GET /ccapi", "OPERATIONS", ["password=value"], []),
        ("GET /ccapi", "OPERATIONS", [], ["1.4.0"]),
    ],
)
def test_discovery_trace_rejects_non_structural_values(
    endpoint: str,
    outcome: str,
    response_keys: list[str],
    protocol_versions: list[str],
) -> None:
    with pytest.raises(ValidationError):
        DiscoveryAttempt(
            endpoint=endpoint,
            outcome=outcome,
            response_keys=response_keys,
            protocol_versions=protocol_versions,
        )
