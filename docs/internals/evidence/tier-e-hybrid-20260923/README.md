# Tier E hybrid recovery qualification, 2026-09-23

**Decision: retain opt-in experimental recovery; reject default promotion.** The full-stream
configuration did not pass every resource gate. Both dictionary modes exceeded the paired
ring-receipt p99 allowance, and full-stream exceeded the combined-CPU allowance.
`policy_eligibility=false` for every arm and artifact.

## Correctness and fault evidence

- Six configurations (dictionary/full-stream and dictionary/unique-edge each with v1 ring-only,
  v2 ring-only, and v2 hybrid) passed 72/72 declared paths in all five development forks and all
  fifteen paired decision forks: **120/120 fault-free runs**, zero reported wrong edges and zero
  failed terminal evidence. A further dictionary-hybrid-only 75,000-operation oracle (24 common,
  24 infrequent, 24 singleton paths) passed 72/72 paths, including every singleton, with complete
  recovery evidence in five development and fifteen decision forks per mode (**40/40 long runs**).
  Its long-run v1/v2 ring-only controls were not rerun; performance decisions use the separate
  paired calibrated workload matrix.
- A separate controlled fault after retained-map insertion and before ring delivery ran fifteen
  paired forks per dictionary emission mode. All **30/30** recovered 72/72 paths, including every
  singleton; all singleton manifest records name `discovery_source=map`. Each terminal result keeps
  transport and attribution incomplete. Confirmed injected ring-loss recoveries total 1,110 per
  mode. The greatest per-fork dependency-relative discovery p99 upper bound was 2.3 ms in
  full-stream and 1.6 ms in unique-edge, below the 500 ms experimental target. Dictionary waiting
  is separate; its recorded maximum was 3.3 s. This target measures observation to acknowledgement
  after dependencies become available, not total observation-to-publication latency.
- Separate rootful controls retained v1/v2 marker tests, explicit v2 USDT fault recovery, and
  map pressure. A one-entry retained map reported 223 insertion failures, preserved its first
  entry, and kept the ring coverage oracle at 72/72 with `recovery_verdict=INCOMPLETE`.
- An agent emitting v1 into a collector requesting v2 produced incomplete terminal lifecycle
  and recovery evidence, not a false coverage pass. Admission-time proof of the target's selected
  marker version remains open; an ELF symbol or USDT note cannot prove emission selection.

## Paired calibrated performance

The matched split-topology runner used the saved calibrated CPU-shaped profile, 200 operations
per batch, three warm-ups, ten measured batches, fifteen rotated **uninstrumented** workload
forks, and a separate fifteen-fork instrumented phase/receipt matrix. Image, native binary, and
daemon JAR hashes match across the final matrices. Forks, not batches, are independent units.
`decision-final.json` retains the deterministic 10,000-resample percentile bootstrap bounds.
The first instrumented run retained one false-negative fixture assertion: target STATUS was read
before the collector's first active poll despite PASS oracle and COMPLETE terminal evidence.
That fork alone was rerun after changing the assertion to check the collector's terminal metrics;
both the original row and `fork-results-corrected.jsonl` are retained. The corrected matrix has
90/90 complete arms. The original `decision.json` and earlier timing archives are historical
diagnostics made before separating daemon phase clocks from uninstrumented workload forks.

| Comparison: hybrid / matching v1 ring-only | Full-stream | Unique-edge | Gate |
| --- | ---: | ---: | --- |
| Paired upper 95% workload-time ratio | 1.080 | 1.021 | ≤ 1.25: both pass |
| Paired upper 95% combined target+collector CPU ratio | **1.285** | 1.239 | ≤ 1.25: **full-stream fails** |
| Paired upper 95% fraction of permitted receipt-p99 increase | **2.47** | **1.34** | ≤ 1: **both fail** |

Receipt p99 is measured with a bounded 100 µs benchmark histogram; values are inclusive upper
bounds. Combined process CPU includes application work and is not a profiler-only CPU estimate.
The syscall-dense comparison remains distinct from this calibrated workload. These results
reject promotion of the combined hybrid mode to a default; they do not claim general application
overhead bounds.

## Dictionary publication

Five paired instrumented forks per emission mode compared native terminal snapshots with 100 ms
coalesced live snapshots under the hybrid reader. Full-stream median publication CPU rose from
1.18 ms / 0.82 MB / one snapshot to 17.92 ms / 11.30 MB / 25 snapshots. Unique-edge rose from
1.33 ms / 0.82 MB / one snapshot to 20.20 ms / 12.11 MB / 27 snapshots. Live publication CPU
remained below 0.5% of measured collector CPU in every fork; no retention exhaustions occurred.
No measured CPU or retention trigger reopens incremental transport. The target's native live
publisher was instrumented, but the split collector still consumes definitions through terminal
admission; a new incremental receiver protocol has not been implemented. Existing inline
75,000-operation retention failure remains unaffected.

## Retained artifacts and limits

`coverage-fifteen.tar.zst`, `long-five.tar.zst`, `long-fifteen.tar.zst`,
`workload-fifteen-final.tar.zst`, `instrumented-fifteen-final.tar.zst`,
`fault-fifteen-discovery.tar.zst`, and `publication-five.tar.zst` retain raw per-fork logs,
manifests, terminal reports, operation/coverage oracles, and JSONL. `rootful-controls.tar.zst`
`current-kernel-controls.tar.zst`, and `post-decision-controls.tar.zst` retain rootful controls.
`SHA256SUMS`
binds each archive and `decision-final.json`. Build artifacts were measured from the current branch
without worktrees; historical archives carry their own image and binary identities.

Host PID-reuse qualification and a profiler-only CPU denominator remain unqualified. Recovered
coverage is finite, bounded evidence for observed stack/syscall
combinations, not complete event history, syscall-attempt coverage, argument-value coverage, or
future behavior. Default promotion requires a separate decision.
