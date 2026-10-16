# Tier E split-privilege coverage qualification (2026-09-22)

This is a paced **coverage-discovery** qualification, not an application-overhead benchmark or a proof of complete syscall history. A non-root JVM target and a privileged collector ran in separate containers with host-PID registration. The target declared 75,000 `openat` operations through 72 Java paths before the workload gate opened. Each flavour used the same five seeds, 23063–23067. The four variants each ran five forks at the declared 1,250 operations/s pace.

| Transport / emission | Forks | Exact recovered paths | Terminal evidence | Limitation |
|---|---:|---:|---|---|
| Dictionary / full-stream | 5 | 72/72 in every fork | `COMPLETE` in every fork | None observed in this corpus |
| Dictionary / unique-edge | 5 | 72/72 in every fork | `COMPLETE` in every fork | Representative retained output, not full event history |
| Inline / full-stream | 5 | 0/72 in every fork | `INCOMPLETE` in every fork | Native encoded snapshot exceeded the 64 MiB limit |
| Inline / unique-edge | 5 | 0/72 in every fork | `INCOMPLETE` in every fork | Same upstream publication limit; collector deduplication cannot repair it |

The ten dictionary forks recovered all 24 common, 24 infrequent, and 24 singleton paths without wrong attribution. Dictionary/full-stream retained between 224,995 and 225,008 resolved syscalls per fork. Dictionary/unique-edge retained 224 representative syscalls per fork. That difference is expected from the emission contract; it is not a workload-speed result. All ten inline forks had `ARTIFACT_UNAVAILABLE`, `transport_completeness=false`, and `policy_eligibility=false`. Their native logs explicitly report `definition_publication_failure=encoded_snapshot_exceeds_retained_limit`. Missing inline edges are therefore not evidence of a ring-delivery defect.

The first sustained attempt with the production 100,000-record cap recovered only 59/72 paths and marked attribution incomplete, despite a closed dictionary artifact. A benchmark-only cap of 250,000 records, still subject to the existing 64 MiB retained-byte limit, removed that artificial observation ceiling. The nonselective dictionary decoder now permits at most 300,000 records while retaining its independent 64 MiB input and decoded-size bounds. Production record defaults remain 100,000. These limits are not an indefinite-collection guarantee.

The native return/exception/`errno` preservation fixture is `agent_native_outcome_preservation` in CTest. It forces profiler capture failure around JDK `UnixNativeDispatcher.openat` and requires the original native operation, return descriptor, pending application exception, and `ENOENT` behavior to survive.

Host verification: `./gradlew :profiler:test` passed after moving the decoder-budget assertion to 300,001 records; `ctest --test-dir /tmp/jseccomp-profiler-native-build --output-on-failure` passed all 36 native fixtures. `./scripts/adkw check-backlog` passed. The rootful [negative artifact archive](negative-artifacts.tar.zst) retains five cases: missing, malformed, symlinked, unclosed, and session-mismatched registered artifacts. Every case yielded `INCOMPLETE`, `transport_completeness=false`, `attribution_coverage=false`, `lifecycle_validity=false`, and `policy_eligibility=false`. Its SHA-256 is `6ff535af9c67941996dbba1321304a6ea177b1d73ed45e04886d630ad5950a82`.

Reproduction:

```bash
TIER_E_SPLIT_COVERAGE_FORKS=5 TIER_E_SPLIT_COVERAGE_OPERATIONS=75000 \
  bash scripts/run_tier_e_split_coverage_matrix.sh build/tier-e-split-coverage-rotated-five
```

The [machine-readable manifest](manifest.json) includes per-fork seeds, verdict dimensions, oracle and artifact checksums, the source base commit, kernel, image identity, and native/profiler binary hashes. The [compressed raw archive](raw-artifacts.tar.zst) contains each fork's oracle, terminal report, coverage manifest, target/collector/control logs, verdicts, and available MZSE bytes. Its SHA-256 is `77eefe257f68723b865f1126d3fd1cf5dd78084d75e1841fa6667e63d05d2380`. Rootless execution remains `INCOMPLETE` rather than a substitute for these rootful results.

Implementation commits: `6e4cea80` (bounded collector/manifest admission) and `ccbef6fe` (rootful and native fixtures). These commits preserve the tested binary hashes in the manifest; the documentation/evidence commit follows separately.

This experiment does not measure relative application slowdown, CPU savings, receipt p99, or complete coverage of unobserved application paths. It does not resolve the separate pre-admission diagnostic issue, whose benchmark audits were disabled here.
