from open_eos_bridge.models import (
    CameraModelFamily,
    CameraModelPriority,
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
