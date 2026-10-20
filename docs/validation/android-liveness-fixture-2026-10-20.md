# Android liveness fixture correction

The published fourth-attempt head `1a589b91a14f5ce1f93007f09db821fc5c527728`
failed Android host run [39416734426](https://github.com/js051/open-eos-control/actions/runs/39416734426):
1,976 tests completed, one failed. The failure was
`LiveViewLivenessTest.onlyCompleteVclPicturesRefreshInputProgressNotParameterSetsOrSei`,
line 23, `Required value was null`.

The fixture emitted complete SPS/PPS/SEI packets with sequence numbers 0, 1, 2,
then jumped to IDR sequence 4. The new cross-access-unit loss protection correctly
rejects that gap-containing access unit. This fixture intends to distinguish NAL
roles, not authorize acceptance after packet loss.

The isolated correction changes its following sequence numbers to 3, 4, 5.
Every original `hasPicture` assertion remains. A separate negative test explicitly
requires rejection of an IDR containing a sequence gap, followed by a complete
picture retaining discontinuity evidence. Production code is unchanged.

The first bounded offline validation on 2026-10-20 passed: all 74 production data
Kotlin files and ten test classes compiled; 170 JUnit cases (including all 17
`LiveViewLivenessTest` cases) and 24 source guards passed. Commands took 63.55
seconds in total, with Java heap capped at 768 MiB and two active processors.
All tested source hashes stayed unchanged. The existing coroutine-test dependency
was included unchanged; no network, Gradle, emulator, or native codec ran.

This focused result does not establish full 1,976-case host acceptance, native color
acceptance, or the separately running device-suite outcome.
The original failed host evidence is retained. This delta is not part of the
published fourth-attempt commit and has not been pushed.
