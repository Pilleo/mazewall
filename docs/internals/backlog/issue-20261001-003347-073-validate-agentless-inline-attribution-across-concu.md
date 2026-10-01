---
title: "Validate agentless inline attribution across concurrent platform workers"
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
  - "experiments/hotspot-agentless/scripts/run-corpus-matrix.sh"
  - "experiments/hotspot-agentless/Makefile"
  - "experiments/hotspot-agentless/README.md"
  - "experiments/hotspot-agentless/results/README.md"
  - "docs/internals/backlog/README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Validate agentless inline attribution across concurrent platform workers

**Context:**
Extend the exact-token corpus to four and sixteen platform workers with per-worker thread identity and branch coverage checks.

**Needed:**
1. Reject worker/TID mixing and run the existing controlled-C2 corpus on four and sixteen workers.
2. Repeat exact-token corpus in interpreter-only and default tiered modes without
   transferring the controlled-C2 inline proof to these configurations.

**Implemented locally; review pending:**
Nine-case matrix `build/matrix.4TpGEq/` passed at 1/4/16 platform workers for C2,
tiered and interpreter modes. All 18,000 intended operations were observed, with
zero wrong branch/order or worker/TID mixing. Every worker has application-frame
coverage on both paths; C2 additionally requires compiled segments per worker.
Availability degrades at 16 workers and remains explicit in the machine report.
`make verify`, backlog validation and `./gradlew build` passed after this matrix.

The captured worker/TID mapping is checked for consistency and one-to-one mapping;
it is not an independent JVM-to-OS thread identity oracle. Class unloading/reuse,
deep chains, cold startup and native transport-loss boundaries remain separate.

---
<!-- id: issue-20261001-003347-073-validate-agentless-inline-attribution-across-concu  file: issue-20261001-003347-073-validate-agentless-inline-attribution-across-concu.md -->
