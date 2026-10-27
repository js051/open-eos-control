# iOS picker public-controller identity diagnostic

## Scope

The synthetic picker fixture records public UIKit identity relationships at the
existing `presentation-completed`, `selected` / `cancelled`, and `completion-end`
boundaries. This is diagnostic instrumentation, not a lifecycle fix. Admission,
retirement, security-scoped access, Stop / ACK behavior, UI assertions and waits
are unchanged.

UIKit may forward a presentation request to an ancestor rather than the object
that received `present`. Therefore retaining the requested host alone does not
establish which controller actually presented the picker. See Apple's
[presentation documentation](https://developer.apple.com/documentation/uikit/uiviewcontroller/present(_:animated:completion:))
and [`presentingViewController` semantics](https://developer.apple.com/documentation/uikit/uiviewcontroller/presentingviewcontroller).

## Fixed flags

Each boundary is followed by these nine v2 journal stages in order. All use the
existing Boolean `flag`; no object address, class name, arbitrary label or device
identifier is recorded.

| Stage | `flag=1` means |
| --- | --- |
| `identity-picker-matches` | The observed controller is the expected picker. |
| `identity-requested-host-alive` | The weak requested-host reference is nonnil. |
| `identity-requested-host-attached` | Its already-loaded view has a window. |
| `identity-original-presenter-known` | A nonnil actual presenter was observed at the first presentation completion. |
| `identity-original-presenter-alive` | That original weak reference is still nonnil. |
| `identity-current-is-requested-host` | The current actual presenter is the requested host. |
| `identity-current-is-host-ancestor` | It matches one of at most 32 inspected parent ancestors. |
| `identity-ancestry-complete` | A live host's ancestry walk reached its end within that bound. |
| `identity-current-is-original-presenter` | It matches the original actual presenter. |

The original-presenter observation is captured once, including an initial nil.
Nil references never count as identity matches. An incomplete ancestry walk makes
a negative ancestor-match result inconclusive. Weak references do not keep any
controller alive; `viewIfLoaded` does not load a provider view.

## Bounds and interpretation

The probe exists only for an enabled synthetic fixture ticket. Ticket generation
still requires the test build, allowed fixture scenario and explicit language.
The closed v2 schema, 128-line / 16 KiB journal caps, 16 fixed opening slots and
artifact limits remain unchanged. Three snapshots add 27 records; all nine records
of each snapshot share one timestamp. An interrupted write can leave a partial
snapshot, which must not be interpreted as a complete observation.

These public relationships cannot identify the private document-service proxy,
prove XPC quiescence, or establish a remote deallocation receipt. A passing run
only establishes non-reproduction on that run; instrumentation can affect timing.
The existing real-picker UI scenarios remain the runtime acceptance gate.

Release impact: `none` (fixture diagnostics and tests only). Physical-device
validation and a product lifecycle repair are not claimed.
