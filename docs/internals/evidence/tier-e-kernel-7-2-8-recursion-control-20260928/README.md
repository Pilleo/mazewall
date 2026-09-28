# Clean Tier E XanMod 7.2.8 recursion-control stop — 2026-09-28 UTC

**`INCOMPLETE`, first of three predeclared clean control-only targets.**
Committed source `6e0d5dd6`, one source fingerprint, exact rootful Docker
image/native agent and target CPU set `0,1` were pinned with the original
10,000-operation/1,000/s full+unique workload. Running kernel was
`7.2.8-x64v3-xanmod1`, matching XanMod source tag
`ca927d28dad3d7cdaad121df660728a67439dad0`; `/proc/sys/kernel/bpf_stats_enabled`
remained `0`. The predeclared schedule was exactly three no-noise controls
with stop at first incompleteness. **Controls two and three and unique mode
did not run.** This cannot be combined with the prior `7.2.4` failure or
with a noisy arm as one kernel/source-matched comparison.

The first full-stream fork had a verified target-authored 10,000-ID
manifest and complete matching version-five sidecar. Raw observed
**9,957/10,000** declared IDs on CPU **1**; main exact submitted
**9,958/10,000**. The complete rows show **43 raw-only missing IDs, 42
main-only missing IDs, and zero IDs missed by both**. Raw/main pre- and
post-session totals were 9,957/9,958, with zero explicit diagnostic
argument-read/array-lookup failures. Kernel exact and ring-ready records
both stopped at 29,815/30,000 with zero ring loss. The sidecar and the
original manifest—not the first-16 console samples—preserve every ID.

The exact installed program IDs were **raw 968, main 967**. After a
pre-arm baseline of six misses each, Linux reported *program-wide*
recursion-miss deltas **raw 222, main 4,957** over the armed interval,
even though optional run-count/time statistics remained unavailable.
The matching tagged kernel's `__bpf_trace_run` skips a program and
increments its miss counter when its per-program recursion guard denies
entry. These nonzero totals make that mechanism testable, but **do not
attribute any miss to any declared ID** or establish it as this failure's
cause: both programs are attached system-wide.

**CPU-provenance limitation:** the manifest requests workload CPU `0`
and collector CPU `1`, but all 9,957 raw target observations were on CPU
`1`. In the sustained workload path the target JVM starts under the
collector's CPU-1 affinity and `TierEKernelSmoke.pinTargetToCpu` later runs
`taskset -pc 0` on its leader only. The measured workload threads' CPU-0
affinity is thus not established, and this run cannot be treated as a
CPU-0 target result. This does not by itself explain the missing IDs;
direct-raw attachment is system-wide. Correcting launch affinity would
create a new experiment contract, not repair this terminal.

The exact runner
`tier-e-trustworthy-88c0f7d1-b178-48f1-9c08-ba6f61d3878f-runner`
was verified stopped with exit code 1 and its `-artifacts` volume retained.
`raw-control.tar.gz` contains the predeclared plan, clean preflight,
source/image/program identities, original target manifest, all 10,000
rows, first-fork log, fail-closed terminal and recovery receipt.
`SHA256SUMS` seals the report and raw archive; host originals remain at
`/tmp/tier-e-recursion-clean-1790587170*`. `policy_eligibility=false`,
`REJECT_DEFAULT_PROMOTION`; neither the five unaudited pairs nor the
native timing gate is qualified.
