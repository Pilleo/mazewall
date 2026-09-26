# Kotlin-owned rootful CPU perf and allocation paths — 2026-09-26

Two independent bounded rootful Docker runs exercised the Kotlin trustworthy
runner after it was changed to **export → validate all host evidence and
provenance → retire exact resources**. Both used one fork, zero warm-ups, one
100-operation measured batch, `MAZEWALL_TIER_E_CALIBRATION=false`, distinct
physical CPUs `0,2`, and the `mazewall-tier-e-rootful:perf-20260919-external-profiler`
image. Each run had seven fork outputs (baseline, four Tier E variants and
two USER_NOTIF variants), one passing terminal, zero missing declared
operations, complete evidence for every profiled arm, and
`policy_eligibility=false`. `REJECT_DEFAULT_PROMOTION` stands.

**CPU perf:** The rootful guest emitted nonempty `perf.data`, perf report,
PID/TID and timestamp streams. The host-side Kotlin processor bound Tier E
collector/workload PIDs exactly; baseline and USER_NOTIF arms retained their
recorded single target PID instead of inventing a collector. All **11/11**
required JIT maps were present and nonempty. The raw perf recording is
845,904,636 bytes and remains in the caller-owned temporary directory, not
Git.

**Allocation perf:** A run-specific `probe_libc:mazewall_malloc_*` probe name
was bound to the exact trusted run ID and shared with its analysis container.
Probe creation was checked before deletion authority was acquired; the exact
event was absent after both stages. Nonempty allocation data and event rows
produced a 345-entry lower-bound libc malloc summary. A probe belonging to
another operator is never deleted. The caller-owned raw recording and event
text remain outside Git (106,841,351 and 138,822,548 bytes).

Failure injection verified that a missing required JIT map keeps a stopped
runner and its source volume for recovery, with the original failure retained.
For both successful runs, Docker inspection independently found **neither**
their exact runner nor their exact artifact volume after host validation.

`manifest.json` records raw paths, byte lengths, SHA-256 hashes, image/run
identities, verdicts and local scope. The small exported manifests, terminal
records, process attribution and probe identity are bound by `SHA256SUMS`.
The large raw files are **host-local**; this record does not present them as
portable archived benchmark evidence or qualify a release slowdown decision.
