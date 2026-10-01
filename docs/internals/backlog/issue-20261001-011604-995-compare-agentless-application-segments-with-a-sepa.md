---
title: "Compare agentless application segments with a separate USER_NOTIF reference run"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless/fixtures/java/InlineReferenceFixture.java"
  - "experiments/hotspot-agentless/internal/corpus/reference.go"
  - "experiments/hotspot-agentless/internal/corpus/reference_test.go"
  - "experiments/hotspot-agentless/cmd/mw-reference-compare/main.go"
  - "experiments/hotspot-agentless/scripts/reference.gradle"
  - "experiments/hotspot-agentless/scripts/run-reference.sh"
  - "experiments/hotspot-agentless/scripts/run-oracle.sh"
  - "experiments/hotspot-agentless/Makefile"
  - "experiments/hotspot-agentless/README.md"
  - "experiments/hotspot-agentless/results/README.md"
  - "docs/internals/backlog/README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Compare agentless application segments with a separate USER_NOTIF reference run

**Context:**
Use the existing public profiler API in a separate JVM to validate the same sharedLeaf/pathA/pathB application segment and unique marked opens without modifying Tier E or its session protocol.

**Needed:**
1. Check reference environment and reject missing token/frame evidence before claiming a differential oracle.

**Implemented locally; review pending:**
`make oracle` runs the candidate and existing public USER_NOTIF profiler API in
separate JVMs. Both use the digest-pinned Temurin JDK as an ordinary user. The
reference's distinct files and syscall paths identify 2,000 operations, and raw
StackTraceElement frames validate sharedLeaf-before-correct-branch ordering.
No Tier E, C++ agent, InvocationId, session API or USER_NOTIF implementation changed.

`build/oracle.LvlLGg/` passed: candidate 1,986 selected application segments and
14 unavailable; reference all 2,000 segments, with 1,000 per branch, no wrong or
missing segments and complete reference coverage. The comparison explicitly
states independent runs and does not claim the same physical occurrences or BCI
equality. Reference raw stacks/profile/provenance are retained in its artifact.
`make verify`, backlog validation and `./gradlew build` passed after the comparison.

---
<!-- id: issue-20261001-011604-995-compare-agentless-application-segments-with-a-sepa  file: issue-20261001-011604-995-compare-agentless-application-segments-with-a-sepa.md -->
