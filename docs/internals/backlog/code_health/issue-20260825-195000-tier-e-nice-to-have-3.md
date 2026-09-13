---
title: "Add per-context event count metrics to daemon"
severity: "LOW"
status: "open"
priority: low
component: "tier-e"
target_modules:
  - "profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEbpfEngine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/engine/TierEBpfProgramBuilder.kt"
effort: "small"
autonomy: "supervised"
open_questions: false
dependencies: []
paperclip_issue_id: "657a7c1e-5ccf-4640-867f-7f5a3a1f7519"
paperclip_identifier: "MAZ-1388"
---

# 🟢 [Severity: LOW]: Per-context event count metrics

**Context:** The daemon tracks total events but not per-context-id distribution.
Useful for verifying that attribution is balanced across scopes during stress runs.

**Needed:** Add a bounded LRU hash map keyed by `context_id` in the BPF program;
increment on each attributed event; expose it through `TierEbpfEngine` and report
the deterministic summary in daemon status output. Metrics are diagnostic only and
cannot affect attribution.

## Rootful verification evidence (closure pending CI retention)

Implementation verified against the authoritative rootful Docker gate retained in [tier-e-rootful-gate-20260913.md](/home/leanid/Documents/code/java/jseccomp/docs/internals/evidence/tier-e-rootful-gate-20260913.md), source commit `d0a5f6d3631f84744fef6ec9417565168f005f90`, with the follow-up evidence commit `e238775c`.

Verification command:

```text
DOCKER_HOST=unix:///var/run/docker.sock TIER_E_CONTAINER_ENGINE=docker ./scripts/run_tier_e_rootful_gate.sh
```

The gate retained native CTest output, profiler unit/integration/fresh-JVM output, benchmark samples, run metadata, and the native-backed Tier E fixture result. Native CTest passed `10/10`; the fresh-JVM Tier E fixture executed with `skipped=0` and `failures=0`; the three-sample full-stream and unique-edge benchmark gates passed their `125%` limits. Machine-readable dimensions are `capture_domain_validity=PASS`, `transport_completeness=PASS`, `attribution_coverage=PASS`, `dictionary_integrity=PASS`, `lifecycle_validity=PASS`, and `runtime_overhead=PASS`. Rootless Podman remains `INCOMPLETE`.
