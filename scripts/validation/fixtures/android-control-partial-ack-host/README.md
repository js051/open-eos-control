# Partial control ACK host boundary

The runner extracts exact production held AF and Bulb public entries,
commandOk/requestOk, and compiles the complete release owners and bounded readers.
Six cases execute original Start, rejected Stop 503, then a retry returning 206,
200 plus Content-Range, or 200 plus an empty mixed-case Content-Range header.
An unconfirmed release must block a new Start without I/O; a later complete 204
must retry the original method/path/payload even after discovery version changes.
Completed retries do no I/O, and only then may a new operation use the new version.

Additional tests cover partial Start cleanup, five legacy success forms, typed
non-2xx errors, and strict JSON precedence. Live View/still/unrelated cases call
the exact requestOk through fixture flags; their public entry methods are not
executed by this runner. Existing Live View and still host runners provide the
separate entry/wiring regressions.

Call, Response, InputStream, discovery and status are synthetic. Header lookup is
case-insensitive and retains an empty value as a present header. This is a
control-flow/receipt test, not real OkHttp header parsing, sockets, Android or
physical-device evidence. Separate authored MockWebServer cases remain NOT_RUN.
Each execution verifies existing tool hashes and limits JVM to 256 MB / 1 CPU.
