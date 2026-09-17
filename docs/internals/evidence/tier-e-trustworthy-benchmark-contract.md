# Tier E trustworthy benchmark artifact contract

The rootful runner `scripts/run_tier_e_trustworthy_benchmark.sh` writes one JSONL artifact per
suite. Each artifact begins with `metadata`, records `fork-start`, `fork-output` and `sample`
records, and ends with exactly one `terminal` record. A successful run also writes `summary`.
An interrupted run retains completed records and writes `terminal.success=false`; it must never be
reported as a benchmark result.

The companion `manifest.json` binds a suite to its source revision and dirty-tree fingerprint,
native-agent checksum, container image ID, kernel, CPU topology, affinity, dimensions, deadlines,
and raw-log paths. The build phase runs as the local user; privileged measurement mounts the
repository read-only and uses `/tmp` as writable workload scratch space.

`workload_correctness=PASS` means the controlled file workload passed its child-local checksum
validation and declared operation-count check, Tier E observed the controlled workload stack and
unlink relationship, no wrong workload stack was admitted, no ring loss occurred, and no
unexplained resolution status remained. The shared workload uses a fresh temporary path per JVM,
so the runner deliberately does not compare its salted checksum across forks or modes. It does not
claim process-wide syscall completeness.

The high-level `application` workload retains its child-local checksum and timing evidence, but
USER_NOTIF has no stable per-operation syscall identity in that workload. Its USER_NOTIF fork
records therefore report `workload_correctness=UNVERIFIED`; only the shared `OPENAT` oracle may
claim `PASS` for USER_NOTIF operation accounting.

`whole_session_evidence` is independent. `INCOMPLETE` is expected when the fixture observes valid
background `NO_ACTIVE_INVOCATION` activity outside its native-boundary claim. The fork record
retains that count separately from `unknown_other`, which fails the workload claim. Every artifact
sets `policy_eligibility=false`.

The runner supports these suites:

- `full`: baseline, dictionary and inline Tier E flavours, plus USER_NOTIF with and without stack capture.
- `tier-e-cache-ab`: a compatibility one-cache-state Tier E comparison.
- `tier-e-cache-paired`: interleaved enabled and disabled canonical-cache forks with paired deltas.
- `tier-e-publication`: terminal and 100 ms live publication variants for every Tier E flavour.

Fork records contain phase totals once per child JVM: stack capture, raw-key work, logical metadata,
serialization, publication, definition-lock wait, cache counters, publication counts and retained
state. They also retain elapsed time, peak RSS, and CPU time when the host exposes it; unavailable
CPU time is represented as `null` with `cpu_time_available=false`, never as zero. Samples contain
only a single measured workload batch, preventing fork totals from being mistaken for per-batch
values. Metadata retains source revision, dirty-state count, JDK, OS, affinity, scope, and the Tier
E plus USER_NOTIF worker/child deadlines.

USER_NOTIF fork records also contain `user_notif_phases`: median controlled-operation duration,
time blocked before listener receipt, listener-to-ACK duration, listener-side stack-capture and
queue time, and ACK-to-return time. These fields are populated only by the shared operation oracle;
zero is an unavailable phase for another workload, not a claimed zero-cost operation.

For unique-edge fixtures, `suppressed_operations` comes only from an explicit collector
`SUPPRESSED` decision after an acknowledged edge. A missing resolved operation is recorded as
`missing_operations`, never inferred as suppression. `candidate_operations`,
`acknowledged_operations`, `unresolved_operations`, and duplicate decisions make retry and
terminal delivery accounting independently auditable.

The matched runner holds one warmed USER_NOTIF profile session across the warm-up and measured
batches of each fork. Its benchmark-only diagnostic limits default to a 480-second profiler-worker
deadline and a 540-second child deadline. These limits do not alter the public profiler default of
60 seconds plus a 5-second cleanup grace period.

The paired-cache suite emits a deterministic bootstrap 95% confidence interval around the median
per-fork cache-disabled minus cache-enabled workload delta. Positive values favor the warmed cache.

Raw logs, build logs, `results.jsonl`, the native agent binary and command environment are retained
under the supplied artifact directory. Benchmark evidence is accepted only with a rootful Docker
artifact containing the complete terminal record; rootless results remain `INCOMPLETE`.
