# Kotlin-owned real guest-host PID reuse — 2026-09-26

**Verdict: OBSERVED_AND_REJECTED.** The Kotlin split fixture, not the archived
shell fixture, drove the registered rootful Docker collector in disposable
Multipass guest `mazewall-pidtest` (Ubuntu 26.04, kernel `7.0.0-31-generic`).
The original host's PID controls were untouched. Guest
`/proc/sys/kernel/pid_max` was 4,194,304 before and after this diagnostic;
only while running the fixture was it set to 4,096 under an exit trap.

Committed source `e35f3efa5ce25fa47dbd6d24a0ca1b3c1ff80d00` was transferred
into a **new** guest directory as an immutable Git archive. A second scoped
commit, `77855cc0`, corrected JDK 25 procfs short reads for `pid_max` and
`ns_last_pid`; only its two changed tooling files were transferred, with
matching guest/host SHA-256s. The first guest attempt was rejected before
container creation because `Files.readString` returned `4` for guest
`pid_max=4096`; a buffered line reader returned the complete value. The
second attempt built the guest tooling and executed the typed
`config/testing/tier-e/real-pid-reuse.json` profile. `manifest.json` records
the source, Docker image, guest native agent/JAR, kernel and PID controls.

## Observed boundary

After a non-root target attached, the Kotlin controller paused its registered
collector, killed the target (exit **137**), then started a bounded host-PID
probe (at most 8,192 candidates, 75 seconds). The probe held the replacement
alive while the collector resumed. After **3,303** attempts, guest-host PID
**1900** was observed with `/proc` start time **25198688**, different from the
original **25197313**. The agent-authored proof says
`marker_version=v2`, `session_tag=4d5a`, and
`process_starttime=25197313`. The collector's terminal report binds to that
original incarnation, gives `PROCESS_IDENTITY_REUSED`,
`evidence_integrity=INCOMPLETE`, `lifecycle_validity=false`,
`transport_completeness=false`, `resolved_syscalls=0`, and
`policy_eligibility=false`. The unrelated UID could not access the control
socket. Independent JSON/proof assertions and exact container-cleanup checks
passed on the host after export.

`raw-fixture.tar.gz` contains the complete guest artifact directory (probe,
terminal, marker proof, control and target/collector logs, typed configuration,
and ownership receipts). The raw guest results are kept separate from the
immutable [2026-09-24 shell fixture](../tier-e-real-host-pid-reuse-20260924/README.md).
`SHA256SUMS` seals this README, manifest and archive.

This qualifies rejection in the **observed paused-collector window only**.
It does not establish unpaused-race frequency, make thread-scoped containment
a process boundary, permit policy input, or reverse `REJECT_DEFAULT_PROMOTION`.
