---
title: "Prove event-correlated inline Java paths with a deterministic operation corpus"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless/fixtures/java/InlineCorpusFixture.java"
  - "experiments/hotspot-agentless/internal/corpus/**"
  - "experiments/hotspot-agentless/tests/integration/corpus_test.go"
  - "experiments/hotspot-agentless/scripts/run-demo.sh"
  - "experiments/hotspot-agentless/Makefile"
  - "experiments/hotspot-agentless/README.md"
  - "experiments/hotspot-agentless/results/README.md"
  - "docs/internals/backlog/README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Prove event-correlated inline Java paths with a deterministic operation corpus

**Context:**
Run 1000 unique marked opens per path after controlled C2 warmup, validate compiler inline evidence, and pair exact operation tokens with candidate frames.

**Needed:**
1. Add manifest and compiler-evidence rejection tests before implementing the fixture and live gate.
2. Preserve exact operation-token pairing, partial collection health and unresolved
   application segments; reject wrong branch/process, ordering, duplicate and
   unobserved-token cases.
3. Require successful active C2 nmethods and matching compiler task IDs proving
   sharedLeaf inline success in both roots, from the captured JVM's compiler log.

**Implemented locally; review pending:**
One-command `make inline-corpus` compiles the fixture using the pinned JDK as the
ordinary caller UID and captures 1,000 unique operations per branch after 3,000
unmarked warmup operations per branch. No JVM agent or reference profiler is used.
Expected operations are predeclared in a `(run,worker,sequence,branch,filename)`
manifest; pairing is by the exact captured path, never by timestamps or stack IDs.

Fresh gate `build/capture.GSwcah/` passed: all 2,000 intended operations observed,
1,962 reconstructed compiled application segments (981 per branch), 38 explicitly
unavailable segments, zero wrong attribution/order and zero unobserved tokens.
Target compiler log proves successful active C2 roots 104/105 inlined sharedLeaf.
Raw captures, compiler log, manifest, target completion log, machine report and
source/class/environment provenance remain in the artifact directory.
`make verify`, original recovery, backlog validation and `./gradlew build` passed
after the focused corpus checks. The issue remains open for review.

**Limitations:** this is a controlled diagnostic C2 configuration, not default
runtime completeness or an overhead benchmark. Inline scope metadata and the
compiler log validate the selected application segment; a complete stack or
instruction-boundary-valid BCI is not claimed. Whole-session kernel loss remains
unknown/partial. USER_NOTIF reference comparison, concurrent/lifecycle/unloading
matrix, deeper chains and default-runtime inline fidelity are separate gates.

---
<!-- id: issue-20260930-231643-171-prove-event-correlated-inline-java-paths-with-a-de  file: issue-20260930-231643-171-prove-event-correlated-inline-java-paths-with-a-de.md -->
