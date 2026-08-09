# Optional Canon EDSDK Provider

Open EOS Control has an SDK-neutral provider boundary for a separately installed Canon EDSDK integration. The public repository and release artifacts do not contain Canon libraries, headers, documentation, samples, constants, ABI declarations, or a working EDSDK provider.

Canon lists the EOS R6 Mark III as supported from EDSDK 13.20.10 and describes EDSDK as a host-PC API for camera settings, shooting control, and image transfer. The public pages do not publish the function signatures, structure layouts, property values, ownership rules, callback rules, or complete error contracts needed for a reliable binding. Those materials are part of Canon's application-delivered SDK package. References: [Canon SDK list](https://asia.canon/en/campaign/developerresources/sdk), [Canon EDSDK release notes](https://asia.canon/en/campaign/developerresources/camera/cap/edsdk-eos-digital-camera-sdk-release-note).

Canon's current public CAP overview lists EDSDK host support for Windows 10/11, macOS 13-15, and Linux on ARM32, ARM64 and x64. That platform list corrects the older assumption that EDSDK is Windows/macOS-only, but it does not provide the package-delivered ABI contract or redistribute rights needed to turn this repository's provider boundary into a working implementation. Reference: [Canon CAP overview](https://asia.canon/en/campaign/developerresources/camera/cap/cap).

Canon's published application terms make the license non-transferable and region/purpose limited, restrict distribution and development-tool access, prohibit reverse engineering, and treat Canon-provided confidential information as confidential. A contributor must review the terms applicable to their Canon region and obtain any required approval before creating, publishing, or distributing a provider. Apache-2.0 does not grant rights to Canon material. Reference: [Canon SDK terms](https://asia.canon/en/campaign/developerresources/terms-conditions-for-digital-camera-software-development-kit-sdk).

## Runtime Contract

A local Python distribution may expose exactly one zero-argument factory through this entry-point group:

```toml
[project.entry-points."open_eos_control.edsdk"]
provider = "licensed_provider:create_provider"
```

The returned object implements `EdsdkProvider` from `open_eos_bridge.edsdk_contract`:

- `api_version`, currently exactly `1`
- bounded `provider_name` and `provider_version`
- `health() -> (available, safe_detail)`
- `discover() -> list[CameraDescriptor]`
- `open(camera_id, profile_hint) -> CameraEngineSession`

Provider results use only the existing Open EOS semantic models. Canon references, handles, callbacks, property identifiers, ABI structures, SDK paths, credentials, camera serials, and raw private diagnostics must not cross this boundary. Camera IDs must be opaque, stable for the current host, and begin with `edsdk-`; descriptors and sessions must report `engine=edsdk`.

The provider owns SDK initialization, event pumping, camera references, callbacks, and shutdown. It must close partially opened resources before raising an error and expose a capability only after the live camera proves the complete operation. Settings require camera-provided values plus readback; capture and downloads require completion events; Live View requires a complete start/frame/stop lifecycle and validated JPEG bytes.

## Bridge Behavior

- `/health` always lists `edsdk`. It reports `available=false` when no compatible provider is installed, while the rest of the Bridge remains usable.
- `GET /v1/cameras` aggregates available local engines and preserves each descriptor's engine.
- PC, Android, and iOS send the selected descriptor's engine when opening a session.
- `engine=edsdk` never falls back to gPhoto2. `auto` continues to default to gPhoto2 unless a selected camera ID resolves to another registered engine.
- Different local USB engines cannot own camera sessions at the same time. This prevents gPhoto2 and EDSDK from racing for the same body.
- Provider loading and health failures use bounded generic diagnostics; exception text is not copied into the public API.

Public CI verifies provider absence, incompatible contracts, load failures, engine routing, session cleanup, cross-engine USB exclusion, mobile client routing, and that the standalone bundle reports the optional engine. It cannot validate Canon ABI compatibility or physical-camera behavior. A real provider and R6 Mark III validation remain research work, not a supported feature.
