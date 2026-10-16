---
title: "Tier E R3: golden-file protocol conformance suite"
severity: "LOW"
status: "resolved"
priority: medium
component: "tier-e"
target_modules:
  - "profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/ControlProtocol.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/ControlSessionMachine.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/ControlProtocolFixtureTest.kt"
effort: "small"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260825-023934-tier-e-wp-04-lifecycle-trust.md"
  - "issue-20260912-224640-816-extract-controlsessionmachine-pure-state-machine-f.md"
paperclip_issue_id: "add9a3d3-1443-431a-941b-aecc2fb59c25"
paperclip_identifier: "MAZ-1178"
---

# 🟢 [Severity: LOW]: R3 — Golden-File Protocol Conformance Suite

**Context:** The WP-04 dual-implementation grind showed the wire contract lived only in
prose: HELLO desync, `MARKER_*` prefix divergence and terminal-state semantics were found
by containers, not by CI. The protocol has since stabilized (strict request→reply,
terminal DEAD sessions, MARKER_-prefixed hygiene failures). Freeze it before WP-08 adds
enrichment fields.

**Needed:**

1. Record transcripts (command→reply sequences, including failure paths: BUSY,
   ALREADY_BOUND, MARKER_BUILD_ID_UNREADABLE, NOT_MAPPED_IN_TARGET, STATE) into committed
   JSON fixtures under `profiler/src/test/resources/protocol/`.
2. Unit test replays each transcript through `ControlProtocol` + `ControlSessionMachine`
   asserting byte-identical replies.
3. Any wire-visible change requires regenerating fixtures IN THE SAME PR with rationale —
   review diff becomes the protocol-change review.
4. When Kubescape enrichment fields arrive (WP-15), they extend fixtures here first.

**Acceptance:** fixture replay test wired into `:profiler:test`; docs pointer from
design doc §4 wire contract.

## Rootful verification evidence (resolution complete; CI retention deferred)

Implementation verified against the authoritative rootful Docker gate retained in [tier-e-rootful-gate-20260913.md](/home/leanid/Documents/code/java/jseccomp/docs/internals/evidence/tier-e-rootful-gate-20260913.md), source commit `d0a5f6d3631f84744fef6ec9417565168f005f90`, with the follow-up evidence commit `e238775c`.

Verification command:

```text
DOCKER_HOST=unix:///var/run/docker.sock TIER_E_CONTAINER_ENGINE=docker ./scripts/run_tier_e_rootful_gate.sh
```

The gate retained native CTest output, profiler unit/integration/fresh-JVM output, benchmark samples, run metadata, and the native-backed Tier E fixture result. Native CTest passed `10/10`; the fresh-JVM Tier E fixture executed with `skipped=0` and `failures=0`; the three-sample full-stream and unique-edge benchmark gates passed their `125%` limits. Machine-readable dimensions are `capture_domain_validity=PASS`, `transport_completeness=PASS`, `attribution_coverage=PASS`, `dictionary_integrity=PASS`, `lifecycle_validity=PASS`, and `runtime_overhead=PASS`. Rootless Podman remains `INCOMPLETE`.

## Resolution (local rootful evidence)

This record is resolved for the current branch using the successful rootful Docker verification retained in [tier-e-rootful-gate-20260913.md](/home/leanid/Documents/code/java/jseccomp/docs/internals/evidence/tier-e-rootful-gate-20260913.md). The evidence is tied to implementation commit `d0a5f6d3631f84744fef6ec9417565168f005f90` and documentation commit `e238775c`; CI retention is explicitly deferred and does not block this local delivery.
