# Tier E JDK-qualified capture-profile evidence, 2026-09-17

> **Status: Historical rootful evidence.**

## Provenance

- Source revision: `051be6ab61530864e702c4e4bd3c8fc9968137b1`
- Source dirty count: `0`
- Rootful image: `mazewall-tier-e-rootful:perf-20260916`
- Image ID: `sha256:6c6a570f7a849a5ce61c7d9371990680e6cc67826887cefa52bbb76dc63496b8`
- Kernel: `7.2.4-x64v3-xanmod1`
- JVM: `25.0.4`
- Pinned CPUs: workload `0`, collector `2`
- Workload: shared `openat` operation oracle
- Configuration: three warm-ups, ten measured batches, five rotated forks

## Verdict

The no-agent baseline is `BASELINE_VERIFIED`; it has no Tier E session evidence and therefore uses
`whole_session_evidence=NOT_APPLICABLE`. Both `dictionary_full_stream_profile_full` and
`dictionary_full_stream_profile_jdk25_unix_openat0` passed all five forks with complete evidence,
valid admitted captures, zero observed loss, zero attribution failures, and an exact 1,300/1,300
operation-oracle match per fork.

The paired `full - jdk25-unix-openat0` median is `221,849 ns` per 100-operation batch, with a
bootstrap 95% interval of `15,981..767,531 ns` and no excluded pair. This supports the narrowed
profile only for JDK 25 `UnixNativeDispatcher.openat0`; it does not establish a portable overhead
claim.

```text
evidence_integrity: complete
performance_gate: not_measured
policy_eligibility: false
capture_domain_validity: complete
transport_completeness: complete
attribution_coverage: complete
dictionary_integrity: complete
lifecycle_validity: complete
runtime_overhead: host_specific_measurement
```

## Retained artifacts

- [`tier-e-capture-profile-20260917.manifest.json`](tier-e-capture-profile-20260917.manifest.json)
  SHA-256: `a8c160858bb341d963c86acbfb554b2ef03072f9ffb708c836435b3aa108a2ed`
- [`tier-e-capture-profile-20260917.jsonl`](tier-e-capture-profile-20260917.jsonl)
  SHA-256: `398cdfe018db93c0f2ff2065c7f4e77e6bc2d2a000c9d0b0efcfd6151ff9e16b`

The command retained by the runner was:

```bash
MAZEWALL_TIER_E_BENCHMARK_SCOPE=tier-e-capture-profile \
MAZEWALL_TIER_E_BENCHMARK_WORKLOAD=oracle \
MAZEWALL_TIER_E_WARMUPS=3 \
MAZEWALL_TIER_E_BATCHES=10 \
MAZEWALL_TIER_E_FORKS=5 \
bash scripts/run_tier_e_trustworthy_benchmark.sh build/tier-e-capture-profile-evidence-20260917
```
