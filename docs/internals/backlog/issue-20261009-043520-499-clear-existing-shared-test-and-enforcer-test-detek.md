---
title: "Clear existing shared-test and enforcer test Detekt errors without weakening native verification"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "src/sharedTest/kotlin/io/mazewall/IsolatedProcessTester.kt"
target_symbols:
  - "IsolatedProcessTester"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Clear existing shared-test and enforcer test Detekt errors without weakening native verification

**Context:**
Focused descriptor-guard verification reveals17 shared-test and22 enforcer test Detekt error findings (17 overlap): exception type checks/broad catches and swallowed exceptions in isolation/JVM-floor/container fixtures, default locale/test health exception, generic test exceptions and unreachable fixture code. Exact unchanged-file evidence retained in maze-scanner-7/detekt-error-evidence.txt and XML. Aggregate warning ratchet passes; full focused command fails. Do not waive errors, alter inventories/configuration, remove checks or inflate native budgets.

**Needed:**
1. Declare exact evidence-listed file scope, preserve fatal-first acquisition/cleanup ownership, interruption/cancellation semantics and existing native assertions, clear actual error causes with focused checks and independent review before final combined acceptance.

---
<!-- id: issue-20261009-043520-499-clear-existing-shared-test-and-enforcer-test-detek  file: issue-20261009-043520-499-clear-existing-shared-test-and-enforcer-test-detek.md -->
