# Optional Canon EDSDK Provider

Open EOS Control has an SDK-neutral provider boundary for a separately installed Canon EDSDK integration. The public repository and release artifacts do not contain Canon libraries, headers, documentation, samples, constants, ABI declarations, or a working EDSDK provider.

Canon lists the EOS R6 Mark III as supported from EDSDK 13.20.10. As checked on 2026-10-18, Canon's official industrial-imaging page publicly links the 258-page **EDSDK 13.20.11 API Programming Reference**, including signatures, ownership, callbacks, properties, and error contracts. API-document availability is therefore not itself a blocker. The separately application-delivered, version-matched headers and native libraries are still required to implement and verify a binding. Canon's Asia SDK list currently names standard EDSDK 13.20.21; the public 13.20.11 reference must not be silently treated as the complete contract for that newer package. References: [Canon official SDK page](https://industrial.imaging.canon.de/integration/sdk/), [Canon-linked API reference](https://canon-industrial-imaging-production.fra1.digitaloceanspaces.com/wp-content/canon_b2b/uploads/2026/09/15132330/EDSDK_API_EN-4.pdf), [Canon SDK list](https://asia.canon/en/campaign/developerresources/sdk), [Canon EDSDK release notes](https://asia.canon/en/campaign/developerresources/camera/cap/edsdk-eos-digital-camera-sdk-release-note).

The 13.20.11 reference lists Windows 10/11, macOS 14/15/26, and Linux configurations including x64 Ubuntu 20.04 and specified ARM hosts. Linux x64 is not categorically excluded, but an arbitrary Linux distribution is not thereby validated. The older CAP overview's April-2025 macOS 13–15 list is not the current 13.20.11 platform matrix. Public documentation and supported-platform lists do not grant SDK redistribution rights, verify this repository's ABI, or establish physical-camera support.

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
- A returned provider session that does not match the requested camera is rejected. The Bridge retains the exact returned object while attempting its first cleanup. A successful first `close()` clears that rejected-owner reservation as before; it does not independently verify physical-camera cleanup.
- If the first rejected-session cleanup fails, is interrupted, or has no callable `close`, that owner remains quarantined for this Bridge process. Further local connections and discovery return `CAMERA_BUSY`, and health reports EDSDK unavailable without querying the quarantined provider. Polling, reconnect and shutdown never retry that close or clear the quarantine. The `close() -> None` contract defines no retry-after-failure release receipt: a later normal return could be a no-op. Check the camera/provider state before restarting Bridge; a restart is not evidence that native cleanup succeeded.
- If `provider.open()` raises without returning an object, cleaning partial resources remains the provider's responsibility.
- Rejected-open quarantine does not change existing registered sessions' commands or Stop/disconnect paths. In particular, it must not prevent an existing Bulb or recording owner from releasing its control. This boundary does not establish general cross-session command serialization or recovery after a normal registered session's failed close.
- Provider loading and health failures use bounded generic diagnostics; exception text is not copied into the public API.

Public CI verifies provider absence, incompatible contracts, load failures, engine routing, session cleanup, cross-engine USB exclusion, mobile client routing, and that the standalone bundle reports the optional engine. It cannot validate Canon ABI compatibility or physical-camera behavior. A real provider and R6 Mark III validation remain research work, not a supported feature.
