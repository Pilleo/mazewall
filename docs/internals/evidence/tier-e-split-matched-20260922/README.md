# Tier E split-privilege matched workload, 2026-09-22

This is local rootful Docker evidence, not a CI claim or a policy input. The non-root target ran separately from the privileged collector. The saved command was:

```bash
TIER_E_SPLIT_MATCHED_FORKS=5 \
  TIER_E_SPLIT_MATCHED_OUT=build/tier-e-split-matched-five-fork-20260922 \
  bash scripts/run_tier_e_split_matched_benchmark.sh
```

The seven modes were rotated across five JVM forks each. Every fork used three warm-up batches, ten measured batches, 200 operations per batch, target CPU 0, and collector CPU 1. The image ID, kernel, JAR/native hashes, exact per-fork commands/results, and raw logs are retained alongside this file and in `raw-five-fork.tar.zst`. Archive checksums are in `archive-sha256.txt`.

| Mode | Complete forks | Measured-batch median | Paired fork-median ratio to baseline (bootstrap 95%) |
| --- | ---: | ---: | ---: |
| Baseline | 5/5 | 0.715 ms | 1.00 |
| Dictionary/full-stream | 5/5 | 6.784 ms | 9.12 [8.02, 15.12] |
| Dictionary/unique-edge | 5/5 | 6.423 ms | 8.39 [7.42, 10.44] |
| Inline/full-stream | 5/5 | 6.076 ms | 8.13 [7.27, 9.60] |
| Inline/unique-edge | 5/5 | 6.140 ms | 8.37 [7.36, 9.21] |
| USER_NOTIF/no-stack | 5/5 | 59.203 ms | 77.52 [50.46, 209.60] |
| USER_NOTIF/stack | 5/5 | 100.738 ms | 93.06 [55.29, 241.44] |

These are measured workload batch durations, not whole-process startup or terminal-publication cost. Every dictionary/full-stream and inline/full-stream fork recovered all 2,600 target-declared operation IDs with zero duplicates or background IDs after filtering to the timed `runRange` stack. Their terminal evidence was `COMPLETE`. Both USER_NOTIF modes independently matched 2,600/2,600 operations in every fork with zero wrong or duplicate operations. Unique-edge intentionally retains representatives, so per-operation reconciliation is not applicable; its terminal evidence was `COMPLETE` in every fork, and distinct-edge coverage must use the separate 72-path oracle.

**Performance gate: UNVERIFIED.** The shared 200-operation count produced a baseline batch under 1 ms, far below the planned 100 ms calibration target. Increasing the count to 400 and 2,000 exhausted the inline native 64 MiB snapshot budget in earlier smoke runs. The above ratios are useful directional measurements but cannot justify a production slowdown claim, a 25% regression decision, or a default-flavour switch. Native snapshot bytes dominated inline retention; the long 75,000-operation, 72-path inline coverage run also ended `INCOMPLETE` at the native budget, whereas both dictionary modes passed 72/72 in five forks. The bounded failure is correct fail-closed behavior, not a coverage pass.

The `negative-and-delay-fixtures.tar.zst` archive retains a stale registration rejection, a closed artifact published after target exit, and a **simulated** process-incarnation change. The last is not a claim that host PID reuse was forced. Earlier split-privilege evidence retains owner/group socket access and malformed/missing/symlink/unclosed/session-mismatched artifact cases. The test logs here show `./gradlew :profiler:test` successful and native CTest 36/36 successful. `scripts/test_verify_tier_e_split_operations.mjs` passed its synthetic verdict cases.

Machine-readable assessment: `capture_domain_validity=PASS` for selected observed `sys_enter` only; `transport_completeness=PASS`, `attribution_coverage=PASS`, `dictionary_integrity=PASS`, and `lifecycle_validity=PASS` for the five-fork short workload; `runtime_overhead=UNVERIFIED`; `policy_eligibility=false`. The prior 75,000-operation inline discovery workload remains `INCOMPLETE`. None of these results proves complete syscall-attempt history or future-workload coverage.

A later **calibrated** run with the same 200-operation count and post-syscall CPU work is retained at `docs/internals/evidence/tier-e-split-calibrated-20260922/README.md`. Use that run for application-batch overhead comparisons; this syscall-dense run remains historical correctness and under-calibration evidence.
