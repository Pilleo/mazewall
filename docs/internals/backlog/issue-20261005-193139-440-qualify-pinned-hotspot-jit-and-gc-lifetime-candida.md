---
title: "Qualify pinned HotSpot JIT and GC lifetime candidates"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/hotspot-agentless/internal/qualification/jitgc"
  - "experiments/hotspot-agentless/fixtures/java/RuntimeCandidateFixture.java"
  - "experiments/hotspot-agentless/internal/candidate/runner.go"
  - "experiments/hotspot-agentless/internal/candidate/verify.go"
  - "experiments/hotspot-agentless/internal/candidate/capture_audit_test.go"
  - "docs/plans/2026-10-05-hotspot-jit-gc-evidence.md"
  - "docs/plans/2026-10-17-hotspot-upstream-generation-evidence.md"
  - "docs/plans/2026-10-17-hotspot-jitgc-native-mechanism-decision.md"
target_symbols:
  - "Evaluate"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Qualify pinned HotSpot JIT and GC lifetime candidates

**Context:**
Approved candidate qualification needs useful token oracles and compiled or concurrent reclamation proof while production runtime refusal remains intact

**Needed:**
1. Implement offline proof model and exact token fixture verifier, retain per-tuple qualification evidence

Qualification harness/model/native corpus implemented. Historical pinned C1/Serial28of40,
C2/Serial31of40 and interpreter/G126of40 useful exact application prefixes;
remaining application frames unavailable. Source shows code reclamation/compile IDs
but capture has no compiled generation lease. Keep production promotion blocked;
pressure/deopt/unloading gates await that mechanism. See
[evidence](../../plans/2026-10-05-hotspot-jit-gc-evidence.md).

2026-10-17 source audit characterizes the existing `_compile_id` capture cookie,
cache guard and fresh-read mismatch rejection; actual prepared-decoder baseline
passes and both guard-removal mutants fail. The ABI/revision/patch-keyed mirrored
audit and verified-bundle candidate sidecar describe reviewed source only, with
production qualification always false. Fresh C1/C2/G1 prefixes were31/40,31/40,
34/40; the six-case matrix exited1 on a four-carrier native task/definition identity
mismatch and its full-capture replay refused incomplete accounting. A supplementary
run with pre-acquisition Java fixture receipts passed the existing six validators
(prefixes27/40,34/40,27/40; no logical virtual identities). All acquired
owners retired, with the original failure retained. Metadata/code lifetime and concurrent
reclamation remain unqualified; deeper mismatch attribution is unverified. Keep
this issue open pending a separately reviewed native mechanism and adversarial
proof. See [source and actual-run evidence](../../plans/2026-10-17-hotspot-upstream-generation-evidence.md)
and [mechanism decision](../../plans/2026-10-17-hotspot-jitgc-native-mechanism-decision.md).

---
<!-- id: issue-20261005-193139-440-qualify-pinned-hotspot-jit-and-gc-lifetime-candida  file: issue-20261005-193139-440-qualify-pinned-hotspot-jit-and-gc-lifetime-candida.md -->
