# Tier E native live snapshot admission, 2026-09-23

**Decision:** Keep opt-in 100 ms native MZSE snapshot admission and defer incremental
dictionary transport. Dictionary/full-stream remains the default;
`policy_eligibility=false`. This measurement does not overturn the separate
hybrid default-promotion rejection in `tier-e-hybrid-20260923/decision-final.json`.

## Five-fork short profile comparison

The split-topology target was non-root and the collector privileged. Three
warm-ups and ten 200-operation measured batches per fork ran on the same
calibrated fixture. Each dictionary emission mode ran five rotated terminal
and live forks on repeated stacks (`syscall_dense`), `stack_diverse`, and
`class_churn`: **60/60 terminal reports COMPLETE**, every opted-in live arm
admitted native snapshots, and no native retention exhausted.

| Profile, dictionary emission | Terminal publication CPU | Live publication CPU | Terminal bytes | Live bytes |
|---|---:|---:|---:|---:|
| Repeated/full | 1.28 ms | 11.84 ms | 0.82 MB | 6.60 MB |
| Repeated/unique | 1.21 ms | 11.62 ms | 0.82 MB | 5.98 MB |
| Stack-diverse/full | 1.28 ms | 12.83 ms | 0.89 MB | 6.64 MB |
| Stack-diverse/unique | 1.39 ms | 11.42 ms | 0.89 MB | 6.18 MB |
| Class-churn/full | 1.57 ms | 12.83 ms | 1.00 MB | 7.10 MB |
| Class-churn/unique | 2.32 ms | 15.53 ms | 1.00 MB | 9.44 MB |

Medians are per-fork totals from dedicated instrumented runs. The largest
observed publication-CPU/collector-CPU fraction was **0.34%**, well below the
10% incremental-transport reopening trigger. Combined native retained peaks
were 0.84 MiB (repeated), 0.96 MiB (stack-diverse), and 1.13 MiB
(class-churn) in both terminal and live variants; temporary snapshot buffers
and collector retention remain separately budgeted.

## Sustained ring-loss comparison

For each dictionary mode, five paired 75,000-operation oracle forks compared
terminal-only dictionary admission with live MZSE admission. All **20/20**
recovered 72/72 stack paths, including every deliberately ring-dropped
singleton. Transport and event-history evidence stayed incomplete, while
`recovery_verdict=COMPLETE`. The median of each fork's *maximum dictionary
waiting time* fell from 62.34 to 0.25 s (full-stream) and from 62.41 to
0.24 s (unique-edge). The largest per-fork live dictionary wait was 2.29 s;
waiting is not the dependency-relative discovery gate. The greatest per-fork
dependency-relative p99 upper bounds were 1.5 ms and 4.3 ms for live full and
unique respectively, below 500 ms.

An earlier ten-run live diagnostic correctly ended `recovery_verdict=INCOMPLETE`:
charging every decoded invocation at a flat 256 bytes exhausted collector
retention. The replacement charges 128 bytes per invocation plus each stack's
frame/text estimate, with a separate bounded transient snapshot reservation.
The repeated long live pilot and the five paired long live forks then completed.
Later collector-retention probes recorded a 7.7 MiB peak for class-churn and
55.3 MiB for the sustained fault fixture, below the 64 MiB collector limit.
The kernel recovery map remains a separately reported 4,096-entry hash
(24-byte key and 96-byte value: at most 480 KiB payload plus kernel hash
overhead). Its allocation does not repair inline native retention exhaustion.

## Provenance

- `short-profiles-five.tar.zst`: per-fork target and collector logs, binary
  identities, operation oracles, process CPU/RSS, native publication phases,
  and JSONL from `scripts/run_tier_e_hybrid_live_comparison.sh`.
- `sustained-live-five.tar.zst` and `sustained-terminal-five.tar.zst`: rootful
  ring-drop fixtures from `scripts/run_tier_e_hybrid_fault_matrix.sh`, with
  seeds 23063–23067, 75,000 declared operations/fork, terminal reports,
  manifests, logs, and discovery timings.
- `retention-diagnostic.tar.zst`: the initial explicit bounded failure,
  corrected pilot, and separate collector-retention probes.
- `SHA256SUMS`: archive digests; each raw run manifest also binds source diff,
  image and native/JAR identities. These artifacts are diagnostic evidence,
  not policy inputs.

A live snapshot is a dependency-complete prefix, **not** terminal producer
closure. Session exit, kernel quiescence, and closed terminal MZSE remain
mandatory. The existing native snapshot path sufficed on these workloads;
there is no incremental native-to-collector protocol in this delivery.
