# Tier E rootful Docker gate evidence

Date: 2026-09-13
Source commit: `d0a5f6d3631f84744fef6ec9417565168f005f90`
Image: `mazewall-tier-e-rootful:latest`
Kernel: `Linux 7.2.4-x64v3-xanmod1 #0~20260907.g48995b0 SMP PREEMPT_DYNAMIC Mon Sep 7 19:39:22 UTC 2026 x86_64`

## Commands

```text
DOCKER_HOST=unix:///var/run/docker.sock TIER_E_CONTAINER_ENGINE=docker ./scripts/run_tier_e_rootful_gate.sh
```

The gate ran the native CMake/CTest suite, the three-sample benchmark gate, and:

```text
./gradlew :profiler:test :profiler:integrationTest :profiler:integrationTestFreshJvm --no-daemon --rerun-tasks --stacktrace
```

Raw retained artifacts:

- `build/tier-e-evidence/native-ctest.log`
- `build/tier-e-evidence/benchmark-gate.log`
- `build/tier-e-evidence/profiler-tests.log`
- `build/tier-e-evidence/run-metadata.txt`
- `profiler/build/test-results/integrationTestFreshJvm/TEST-io.mazewall.profiler.TierEEndToEndIntegrationTest.xml`

## Results

- Native CTest: `10/10` passed, including seccomp boundary reporting and retransformation.
- Native-backed end-to-end fixture: executed, `skipped=0`, `failures=0`.
- Full-stream benchmark medians: `get_stack_trace_ns=1477743`, `raw_key_ns=269091`, `logical_metadata_ns=6790043`, total `8536877`.
- Unique-edge benchmark medians: `get_stack_trace_ns=1408337`, `raw_key_ns=616045`, `logical_metadata_ns=699845`, total `2724227`.
- Benchmark threshold: each phase and mode total passed the configured `125%` limit.
- Rootless Podman fixture: `INCOMPLETE`, not authoritative for privileged evidence.

## Machine-readable verdict dimensions

```text
capture_domain_validity=PASS
transport_completeness=PASS
attribution_coverage=PASS
dictionary_integrity=PASS
lifecycle_validity=PASS
runtime_overhead=PASS
```

The seccomp-denied-attempt fixture remains explicitly out of capture-domain scope and reports `attempt_missed=1`, `observed_sys_enter=0`, `verdict=INCOMPLETE`; this is a boundary diagnostic, not a fabricated captured event.
