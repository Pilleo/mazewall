---
title: "Add SO_RCVTIMEO to daemon session socket (bounded recv blocking)"
severity: "LOW"
status: "resolved"
priority: low
component: "tier-e"
target_modules:
  - "profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEClientSession.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEKotlinDaemon.kt"
effort: "small"
autonomy: "supervised"
open_questions: false
dependencies: []
paperclip_issue_id: "e68a1beb-6f8b-4015-93d1-354534bbabb3"
paperclip_identifier: "MAZ-1387"
---

# 🟢 [Severity: LOW]: Add SO_RCVTIMEO to daemon session socket

**Context:** A controller that connects but never sends data blocks the session thread's
recv() indefinitely, holding the single-session slot and preventing any other controller
from connecting.

**Needed:** Set the equivalent Java socket receive timeout to 30 s after accept().
On timeout, `readUTF()` raises `SocketTimeoutException`; the session treats it as
an idle event, rechecks `stopRequested`, and loops. No `PosixFfi` exists in the
current profiler implementation, so no native socket dependency is introduced.

## Rootful verification evidence (resolution complete; CI retention deferred)

Implementation verified against the authoritative rootful Docker gate retained in [tier-e-rootful-gate-20260913.md](/home/leanid/Documents/code/java/jseccomp/docs/internals/evidence/tier-e-rootful-gate-20260913.md), source commit `d0a5f6d3631f84744fef6ec9417565168f005f90`, with the follow-up evidence commit `e238775c`.

Verification command:

```text
DOCKER_HOST=unix:///var/run/docker.sock TIER_E_CONTAINER_ENGINE=docker ./scripts/run_tier_e_rootful_gate.sh
```

The gate retained native CTest output, profiler unit/integration/fresh-JVM output, benchmark samples, run metadata, and the native-backed Tier E fixture result. Native CTest passed `10/10`; the fresh-JVM Tier E fixture executed with `skipped=0` and `failures=0`; the three-sample full-stream and unique-edge benchmark gates passed their `125%` limits. Machine-readable dimensions are `capture_domain_validity=PASS`, `transport_completeness=PASS`, `attribution_coverage=PASS`, `dictionary_integrity=PASS`, `lifecycle_validity=PASS`, and `runtime_overhead=PASS`. Rootless Podman remains `INCOMPLETE`.

## Resolution (local rootful evidence)

This record is resolved for the current branch using the successful rootful Docker verification retained in [tier-e-rootful-gate-20260913.md](/home/leanid/Documents/code/java/jseccomp/docs/internals/evidence/tier-e-rootful-gate-20260913.md). The evidence is tied to implementation commit `d0a5f6d3631f84744fef6ec9417565168f005f90` and documentation commit `e238775c`; CI retention is explicitly deferred and does not block this local delivery.
