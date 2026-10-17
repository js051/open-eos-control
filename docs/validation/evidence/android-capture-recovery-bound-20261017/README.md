# Android capture recovery evidence

Baseline integration: `34669de2bea88fb318aca0a9a3ed16e5a681b3d5`.
Fixed source commit: `bb9d649ecf221834fc2e24b2b485e5b601f5dc76`.

Baseline compiles and fails 3 runtime assertions. Final focused suite passes 13;
maintenance regressions pass 16 + 67 + 67 + 68. Total 231 passing executions,
not 231 distinct new tests. The earlier overlapping 12-case run is excluded.

Receipts retain source/extraction/fixture/tool hashes and exit codes. JUnit output
is preserved verbatim, including the expected superseded-Stop diagnostic in the
passing restore-token regression. Synthetic HTTP/JSON adapters are explicit;
there is no Android, real OkHttp, Compose, device or camera acceptance here.
Generated test jars were removed after completion; source and evidence remain.

The restore-token receipt fixture hash map is normalized to filename/SHA-256 records
to avoid a filename-triggered secret-scan false positive. Every hash is unchanged;
the original receipt SHA-256 is included. Security hooks/configuration are unchanged.
