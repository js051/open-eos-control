# Apple lifecycle source: NOT_COMPILED / NOT_RUN

This is opt-in iOS source only, guarded by canImport(ImageCaptureCore) and os(iOS).
No Apple SDK, device, permission prompt, entitlement or product entry point was used.
Official DocC JSON used for declaration/availability checks is listed in official-sources.json.

- Factory requires main thread and creates only the port/owner, with .mainThread executor. It does not create/start a browser or request authorization.
- Browser creation, authorization request and session open are effect-driven, never exercised in this Linux environment.
- Only the first native open on an exact retained ICCameraDevice object is supported. Any attempted native open consumes that object, including failures/timeouts. UUID/name/VID/PID never define identity.
- Same-object reopening remains unsupported because current-state properties cannot establish a late delegate readiness callback's session generation.
- DeviceInfo always returns unsupportedWireContract and emits no PTP bytes.
- Open and close use their exact-request completion closures; required delegate methods do not emit duplicate request receipts. PTP events use only ptpEventHandler.
- Retire disables event/readiness admission while retaining pending open/close duties. Close timeout/failure never releases a native context. Handler is replaced with a noncapturing no-op only after confirmed close; this is not a claim that queued callbacks were drained.
- One process-lifetime strong (port, owner) lease; second make throws adapterAlreadyCreated, with no deinit/weak-owner reset. The slice cannot restart within a process.
- Independent maximumRetainedDevices=4 limits the slice to four distinct native objects for the process lifetime. Extra objects are not retained or advertised; no old object or unresolved duty is evicted. Each native object supports only its first attempted open. This is not a complete provider.
- Preflight refusals use openNotDispatched before installing any native session resources. The core clears only that request and never fabricates a native close. Contradictory ready evidence preserves quarantine. Capability/access loss uses exact sessionInvalidated, not a physical-close assertion.
- The lease/retention logic is source-only and has NOT been Apple-compiled or runtime-tested. Shared reducer/coordinator regression evidence does not exercise these Apple branches.
- SDK typechecking, Swift importer/actor/Sendable diagnostics, native callbacks, permission behavior and physical cleanup are unverified. Linux inactive-branch parsing cannot validate these.
