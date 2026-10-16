# Tier E coverage discovery: retained rootful evidence

Date: 2026-09-21. Status: measured, workload-specific evidence; not a capacity guarantee.
`policy_eligibility=false` throughout.

## Outcome

The saved benchmark completed all 25 full-corpus children: five rotated seeds for baseline,
dictionary/full-stream, dictionary/unique-edge, inline/full-stream, and inline/unique-edge.
Each child ran 300 warm-up operations on separate paths and descriptors, then 75,000 measured
operations at 1,250 operations/s. The oracle was published before the gate opened.

| Configuration | Recovered paths in each fork | Common / infrequent / singleton recall | Event evidence |
| --- | --- | --- | --- |
| Baseline, no agent | Not applicable | Not applicable | Workload PASS, all five forks |
| Dictionary/full-stream | 72 / 72, five of five | 100% / 100% / 100% | PASS / COMPLETE, all five |
| Dictionary/unique-edge | 72 / 72, five of five | 100% / 100% / 100% | PASS / COMPLETE for mode, all five |
| Inline/full-stream | 0 / 72, five of five | 0% / 0% / 0% | FAILED / INCOMPLETE, all five |
| Inline/unique-edge | 0 / 72, five of five | 0% / 0% / 0% | FAILED / INCOMPLETE, all five |

Every dictionary fork reconciled 75,300 operations including warm-up and 225,900 captured
syscalls, with zero ring loss, missing expected operations, or wrongly attributed declared paths.
Unique-edge retains representatives, not full event history; its operation accounting includes
the benchmark observer's acknowledged and suppressed decisions. Its COMPLETE session verdict
must not be read as retention of every event. Across the ten dictionary forks, the declared
workload accounts for 750,000 measured operations plus 3,000 warm-ups.

Full decoded stack comparisons between dictionary modes passed for all 72 paths in every seed.
The comparison preserves frames, descriptors, BCIs, quality and flags, and normalizes only
session-local loader tags and hidden-class address suffixes. Qualification is the identical
bundle and JDK; this workload does not exercise redefinition. No incorrect exact attribution
was observed. Inline full-corpus stack equivalence is INCOMPLETE, not PASS.

The preceding reduced smoke used 2,400 measured operations and the same 72 paths. All four
Tier E configurations passed coverage and decoded-stack comparison in that smoke. Passing a
small workload therefore does not qualify inline transport for the full corpus.

## Identified limiting boundary

Every full-corpus inline child logged:

```text
definition_publication_failure=encoded_snapshot_exceeds_retained_limit
```

`profiler-native/src/agent.cpp::estimate_snapshot_wire_bytes` rejected the proposed inline
snapshot against the existing 64 MiB bound. Serialization never started, publication bytes
remained zero, and all 225,900 ring observations stayed unresolved. Ring loss was zero.
This is a definition-publication size limit, not evidence of ring transport loss or incorrect
exact attribution. Collector-side unique-edge suppression cannot reduce a snapshot that must
arrive before those edges can be resolved.
The preserved legacy event oracle labels these unresolved operations as `wrong_operations`;
that field does not establish a wrong exact stack. The separate coverage evaluator correctly
reports missing coverage as INCOMPLETE and retains an empty wrong-exact-attribution set.

The legacy `failed_publications` counter only increments in exception handlers; this explicit
preflight rejection returns false and leaves that counter at zero. The new report retains the
failure diagnostic rather than interpreting the zero counter as success. Native admission
exhaustion also remains zero: refusing an oversized encoded snapshot is a separate failure.
No limit was increased and no incomplete result was relabelled as complete.

## Resource observations and limitations

| Configuration | Median child CPU seconds | Median child peak RSS MiB | Median total elapsed seconds |
| --- | ---: | ---: | ---: |
| Dictionary/full-stream | 75.57 | 2,198.88 | 87.00 |
| Dictionary/unique-edge | 71.28 | 1,909.60 | 83.36 |
| Inline/full-stream, incomplete | 66.74 | 1,860.88 | 80.27 |
| Inline/unique-edge, incomplete | 70.46 | 1,513.98 | 82.57 |

Both dictionary modes published exactly 15,622,607 bytes per fork. Median serialization was
9.234 ms for full-stream and 9.090 ms for unique-edge. The native retained-state counter was
15,000,327 bytes in each fork. Definition-snapshot estimates and process RSS are different
metrics; these counters are not a claim that the entire process uses at most 64 MiB.

Paired unique-edge/full-stream percent changes, with deterministic 10,000-resample bootstrap
95% intervals over five seeds:

- Child peak RSS: -12.97%, interval [-15.12%, -9.93%].
- Child CPU: -3.70%, interval [-7.66%, +2.21%]. No established CPU improvement.
- Whole-child elapsed time: -7.46%, interval [-11.41%, -3.79%]. Includes setup, shutdown and reporting.

The workload is rate-limited to approximately 60 seconds, so its duration is not an unpaced
application slowdown estimate. CPU and RSS describe the workload JVM for baseline, but the
collector JVM for Tier E, excluding its workload child. Coverage-report generation is included
in child measurements. Do not divide these CPU numbers to claim application slowdown.
Collector receipt p99 histogram upper bounds were 16.78--33.55 ms for dictionary/full-stream
and 8.39--16.78 ms for dictionary/unique-edge. Native phase timing was enabled, not paired with
timing-disabled controls. No historical 25% regression gate is claimed against this new corpus.

Discovery curves use post-retention observation time since gate release. Terminal-only
publication delays exact resolution until finish. Records first seen in the final report use
that report time as an upper bound, not an exact acknowledgement timestamp or syscall time.

## Decision

- Dictionary/full-stream remains the default and is suitable for this declared workload.
- Dictionary/unique-edge is supported for coverage-only use on this workload: identical recall
  with lower measured child RSS. It is not a demonstrated stack-capture or CPU optimization.
- Inline remains opt-in diagnostics for bounded small sessions; it is not accepted for this
  75,000-operation session under the current snapshot bound.
- Do not change the ring transport, production BPF maps, attachment defaults or acknowledgement
  semantics on this evidence. Ring loss did not explain the failed coverage.
- A future inline improvement must address bounded publication, explicit preflight diagnostics
  and dependency/acknowledgement ordering. Raising memory limits is not the selected remedy.
- Kernel aggregation remains a deferred design decision, with identity, exhaustion, retry and
  closure requirements in the Tier E design. It does not fix this publication failure.

The earlier audit-only discrepancies remain unresolved observations, not conclusive host/kernel
delivery defects. The coverage harness corrects leader-only affinity by launching the workload
on its assigned CPU from startup and moving collector threads after attachment. This and the
changed workload/audit configuration prevent causal comparison with those earlier experiments.

## Provenance and reproduction

Source base: `e2167d60b3e42edf21c749049d60f64fbef7ac0f`, with implementation changes explicitly
recorded as dirty, not misrepresented as a clean commit. Immutable benchmark input fingerprint:
`520bf69154ae30acaa9023b457b72352875d296b158bcb999aee61e946efa8d2`.
Native agent SHA-256: `0247d7d7095c9898c9b16a27fe7d3fec8974f188fd0e565faa1766f4be8f2e9e`.
Image: `mazewall-tier-e-rootful:perf-20260919-external-profiler`, ID
`sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56`.
Release build, JDK 25.0.4, kernel `7.2.4-x64v3-xanmod1`, workload CPU 2, collector CPU 4.
Effective affinities were retained for every Tier E fork. AUTO selected USDT markers and direct
raw tracepoints; attachment audits were disabled. Runtime BPF counters were unavailable.

```bash
MAZEWALL_TIER_E_BENCHMARK_CPUS=2,4 bash scripts/run_tier_e_coverage_benchmark.sh smoke /tmp/tier-e-coverage-smoke-pinned-20260921
MAZEWALL_TIER_E_BENCHMARK_CPUS=2,4 bash scripts/run_tier_e_coverage_benchmark.sh full /tmp/tier-e-coverage-full-20260921
node scripts/summarize_tier_e_coverage.mjs /tmp/tier-e-coverage-full-20260921
```

The full benchmark itself emitted a successful runner-completion terminal record after all
25 children, including the ten correctly classified inline failures. The outer wrapper then
reported a shell EOF error because its reporting footer had been edited while it was running.
Raw results were intact; reporting was rerun directly without rerunning or changing measurements.
`full-runner.log` retains the incident. The saved wrapper now reports partial artifacts on a
failed child command and preserves its exit status; the dedicated wrapper tests cover both
success and failure. Do not edit an executing shell script during future measurements.

## Verification and retained artifacts

Focused JVM verification passed 114 tests: `TierECoverageOracleTest` (6),
`TierEMatchedBenchmarkJsonTest` (50), `TierEOperationOracleTest` (10),
`AttributionCollectorTest` (24), `AttributionCollectorAppendTest` (1), and
`TierEProfilerSessionLifecycleTest` (23). The attribution tests use package
`io.mazewall.profiler.internal.attribution`; workload tests use `io.mazewall.profiler.tierE.stress`.
Coverage tests include exact frequencies, singleton placement, distinct path frames, reproducible
seeds, missing repetitions, missing singleton, wrong paths/syscalls, unavailable oracle,
unresolved/unacknowledged output, retry, and duplicate acknowledgement.

```bash
./gradlew :profiler:test --tests io.mazewall.profiler.tierE.stress.TierECoverageOracleTest --tests io.mazewall.profiler.tierE.stress.TierEMatchedBenchmarkJsonTest --tests io.mazewall.profiler.tierE.stress.TierEOperationOracleTest
./gradlew :profiler:test --tests io.mazewall.profiler.internal.attribution.AttributionCollectorTest --tests io.mazewall.profiler.internal.attribution.AttributionCollectorAppendTest --tests io.mazewall.profiler.internal.attribution.TierEProfilerSessionLifecycleTest
node --test scripts/tests/tier_e_coverage_summary.test.mjs scripts/tests/tier_e_coverage_stack_identity.test.mjs scripts/tests/tier_e_coverage_runner.test.mjs
bash -n scripts/run_tier_e_coverage_benchmark.sh
./scripts/adkw check-backlog
```

Backlog validation passed. The bare `./scripts/adkw hook` invocation requires explicit harness
arguments in this checkout. Retrying with `--harness codex --file` for the design document
returned a documented skip because that hook checks Kotlin syntax, not Markdown. This is not
claimed as Markdown semantic validation; the exact outputs are retained in `checks/`.

Reporter tests retain incomplete history despite a coverage pass, publication preflight failures,
missing forks, interrupted JSONL, child timeout records, baseline separation, cumulative recall
without hiding incomplete forks, full-stack normalization, and paired-resource eligibility.

`full-summary.json` and `smoke-summary.json` provide machine-readable per-fork verdicts,
curves, resource values and comparisons. `full-raw.tar.gz` and `smoke-raw.tar.gz` retain manifests,
raw JSONL, child logs, oracle schedules, decoded stacks, environment topology, build logs and
bundle checksums. Compiled bundle binaries are not duplicated in the archive. `checks/` holds
verification logs. `checksums.sha256` covers the retained package.

No resolved backlog record was modified. The open observation-gap records remain open because
coverage of this declared corpus neither explains every earlier omission nor establishes
complete coverage of an unknown application or future execution.
