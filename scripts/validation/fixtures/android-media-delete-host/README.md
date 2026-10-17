# Media DELETE receipt host boundary

The bounded offline runner compiles unchanged full production `MediaDeleteHttp.kt` and
`MediaDeleteEvidence.kt`, and exact extracted `CcapiClient.deleteMedia` and
`CameraViewModel.withDeletedMedia`. In-memory HTTP/Okio doubles provide headers, body,
request/interceptor dispatch, and state models. A synthetic confirmation callback links
the exact transport to the exact state mutation; it is not the full ViewModel coroutine,
repository, Android lifecycle, OkHttp, socket, browser, or physical-camera execution.

Ten JUnit cases: three partial-204 regressions; empty and explicit-length-zero complete
204 controls; 206; clean and ranged 403 with unchanged rejection classification;
nonempty 204; and owner refusal before dispatch. Tests check one-shot request settings,
exact mutation count, attempt receipt, observed feature, and target/other UI preservation.
Baseline is expected to fail exactly the three partial-204 cases. Formal Android
MockWebServer tests are authored separately and NOT_RUN without Gradle dependencies.
