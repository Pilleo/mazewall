---
title: "Extract probe harness modes from TierEDaemon.kt main()"
severity: "LOW"
status: "resolved"
priority: low
component: "tier-e"
target_modules:
  - "profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/ProbeMain.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEKotlinDaemon.kt"
effort: "small"
autonomy: "supervised"
open_questions: false
dependencies: []
paperclip_issue_id: "ef0ff37d-ad70-4710-b849-f77958c69160"
paperclip_identifier: "MAZ-1386"
---

# 🟢 [Severity: LOW]: Extract --probe/--probe-stdin/--probe-cmdfile modes to ProbeMain.kt

**Context:** The historical `TierEDaemon.kt` main() contained ~80 lines of test-harness probe modes
mixed with production daemon startup. These are only used by the WP-04/WP-05 suite
scripts and should not ship in the production daemon artifact.

**Needed:** Keep all three probe modes (`--probe`, `--probe-stdin`, `--probe-cmdfile`)
in the separate `profiler` `ProbeMain.kt` harness. Production startup remains the
daemon-only `TierEKotlinDaemon` entry point.

## Rootful verification evidence (resolution complete; CI retention deferred)

Implementation verified against the authoritative rootful Docker gate retained in [tier-e-rootful-gate-20260913.md](/home/leanid/Documents/code/java/jseccomp/docs/internals/evidence/tier-e-rootful-gate-20260913.md), source commit `d0a5f6d3631f84744fef6ec9417565168f005f90`, with the follow-up evidence commit `e238775c`.

Verification command:

```text
DOCKER_HOST=unix:///var/run/docker.sock TIER_E_CONTAINER_ENGINE=docker ./scripts/run_tier_e_rootful_gate.sh
```

The gate retained native CTest output, profiler unit/integration/fresh-JVM output, benchmark samples, run metadata, and the native-backed Tier E fixture result. Native CTest passed `10/10`; the fresh-JVM Tier E fixture executed with `skipped=0` and `failures=0`; the three-sample full-stream and unique-edge benchmark gates passed their `125%` limits. Machine-readable dimensions are `capture_domain_validity=PASS`, `transport_completeness=PASS`, `attribution_coverage=PASS`, `dictionary_integrity=PASS`, `lifecycle_validity=PASS`, and `runtime_overhead=PASS`. Rootless Podman remains `INCOMPLETE`.

## Resolution (local rootful evidence)

This record is resolved for the current branch using the successful rootful Docker verification retained in [tier-e-rootful-gate-20260913.md](/home/leanid/Documents/code/java/jseccomp/docs/internals/evidence/tier-e-rootful-gate-20260913.md). The evidence is tied to implementation commit `d0a5f6d3631f84744fef6ec9417565168f005f90` and documentation commit `e238775c`; CI retention is explicitly deferred and does not block this local delivery.
