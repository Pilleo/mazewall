# Tier E calibrated split run with collector resource metrics

This is a second, independent five-fork run of the calibrated seven-mode matrix. The only fixture addition was a benchmark-only `/proc` sample of the privileged collector after its terminal report existed and before shutdown. No BPF, native capture, wire, or collector policy path was changed. The exact command was:

```bash
TIER_E_SPLIT_MATCHED_FORKS=5 \
  TIER_E_SPLIT_WORKLOAD_PROFILE=calibration_cpu_heavy \
  TIER_E_SPLIT_MATCHED_OUT=build/tier-e-split-matched-calibrated-metrics-20260922 \
  bash scripts/run_tier_e_split_matched_benchmark.sh
```

All 35 forks completed, `calibration_target=met`, and all 20 Tier E terminal reports were `COMPLETE`. Dictionary/full-stream and inline/full-stream each recovered 2,600/2,600 declared operation IDs in every fork with `event_history_verdict=PASS`. USER_NOTIF's independent oracle matched 2,600/2,600 per fork in both modes. The selected capture domain remains observed `sys_enter`, not all syscall attempts.

| Mode | Batch median | Paired fork median vs baseline | Target peak RSS median | Collector peak RSS median | Collector CPU median |
| --- | ---: | ---: | ---: | ---: | ---: |
| Baseline | 116.60 ms | 1.00x | 79,964 KiB | n/a | n/a |
| Dictionary/full-stream | 138.56 ms | 1.14x | 86,432 KiB | 362,952 KiB | 5.41 s |
| Dictionary/unique-edge | 132.99 ms | 1.12x | 85,152 KiB | 360,900 KiB | 4.70 s |
| Inline/full-stream | 138.04 ms | 1.14x | 86,672 KiB | 546,872 KiB | 7.37 s |
| Inline/unique-edge | 133.90 ms | 1.07x | 86,152 KiB | 549,528 KiB | 5.99 s |
| USER_NOTIF/no-stack | 176.18 ms | 1.57x | 380,308 KiB | n/a | n/a |
| USER_NOTIF/stack | 208.15 ms | 1.55x | 414,356 KiB | n/a | n/a |

Collector CPU is process ticks converted using host `CLK_TCK`, accumulated from daemon start through terminal publication; collector peak RSS is Linux `VmHWM` at the same boundary. These are not per-syscall costs and include attach, drain, dictionary, and terminal work. The target CPU/RSS and exact per-fork values are in `fork-results.jsonl`. The prior calibrated run without collector sampling is preserved separately. The two runs differ materially in timing and their five-fork intervals overlap widely. Do not infer that inline is faster; the repeat run instead shows a substantial collector memory premium for inline on this fixture, consistent with keeping dictionary/full-stream as the default.

Raw logs, terminal reports, operation manifests, oracles, and the collector metric samples are in `raw-five-fork.tar.zst`; the archive checksum, image ID, kernel, and binary hashes are adjacent. Receipt p99 and phase-level serialization/publication time are still unavailable from this fixture. The 25% historical release-regression gate has no matching prior-release baseline, so `runtime_overhead=UNVERIFIED` for that gate. Long 75,000-operation inline 72-path coverage remains `INCOMPLETE` at the native 64 MiB bound. `policy_eligibility=false` throughout.
