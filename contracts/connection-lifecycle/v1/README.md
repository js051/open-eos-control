# Connection attempt retirement

`session-creation-rejection-vectors.json` is the shared PC/iOS/Android classification
fixture for responses to session creation at the expected Desktop Bridge endpoint.
A recognized rejection means no new registered session was returned; it is not
physical-camera cleanup evidence. Network loss, malformed success, unknown server
errors and explicit unconfirmed-control errors retain an unknown outcome.

Connecting cancellation must revoke UI publication immediately. Pending backend
work and cleanup remain owned by the captured endpoint, authentication context and
session/resource identity. A retired owner cannot publish through a newer owner.
Shared backends wait for teardown before admitting another explicit connection;
editing settings, settling an old request, or returning to the foreground cannot
queue or automatically start another connection.

PC coverage executes production JavaScript functions under controlled host-side
transport completions in `bridge/tests/connection-guidance.test.js`. This fixture
and those tests do not establish browser termination, native-device or physical
camera acceptance.
