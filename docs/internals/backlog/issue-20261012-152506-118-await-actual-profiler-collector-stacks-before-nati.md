---
title: "Await actual profiler collector stacks before native custody assertion"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/PortalWorkerPolicyNativeTest.kt"
target_symbols:
  - "observeCollectors"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Await actual profiler collector stacks before native custody assertion

**Context:**
Task6d2 actual complete gate exited 1: one-shot collector snapshot observed one of two asynchronously started session threads; exact two-owner and physical-end assertions must remain mandatory.

**Needed:**
1. Bound fixture observation by actual listener-owned stack readiness, retain failure on missing collectors, verify native profile/install/JIT and request controller review before source acceptance.

---
<!-- id: issue-20261012-152506-118-await-actual-profiler-collector-stacks-before-nati  file: issue-20261012-152506-118-await-actual-profiler-collector-stacks-before-nati.md -->
