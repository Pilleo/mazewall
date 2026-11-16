---
title: "T4 coordinate versioned native projection parsing and qualification"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - "platform"
  - "profiler-native"
target_files:
  - "platform/src/main/java/io/mazewall/ffi/internal/LiveStackBootstrap.java"
  - "platform/src/test/kotlin/io/mazewall/ffi/internal/LiveManagedLayoutTest.kt"
  - "profiler-native/src/live_stack_jni.cpp"
  - "profiler-native/CMakeLists.txt"
  - "profiler-native/src/live_stack_projection.hpp"
  - "profiler-native/tests/live_stack_projection_test.cpp"
  - "profiler-native/tests/LiveStackContextWorkload.java"
target_symbols:
  - "LiveStackBootstrap"
  - "parse_t4_v1"
open_questions: false
---

# 🟡 [Severity: HIGH]: T4 coordinate versioned native projection parsing and qualification

**Context:**
Own the distinct seven-path T4/1 Java/native projection-parser/refusal component; it is not the whole scope of issues 359, 957, or 423. The controller decision is ACCEPT_WITH_EXPLICIT_LIMITS for this bounded component, per [controller decision](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-1-native-full-build/controller-decision.md). The recorded full gate was 4,525 cases: 45 skipped, 4,480 executed, zero failures/errors; separate pinned Temurin native parser/actual-dispatch/old-negative/rollback evidence is 4/4. Preserve the recorded original-behavior REDs, early hash gaps, and prior binary export equality unproved. This acceptance does not complete Task 1/T4 qualification. Positive independent authority and no-writer evidence, exact mapped owner and conditional provenance, and final T4 qualification remain OPEN. No native Ready or guard activation is authorized. Broad dependencies: [issue 359](issue-20261103-085251-359-provide-live-pre-call-stack-context-before-user-no.md), [issue 957](issue-20261104-122636-957-establish-safe-java-reentry-handling-for-live-nati.md), and [issue 423](issue-20261108-141806-423-t4-validate-exact-managed-final-jar-layout.md). Governing scope: [masterplan](../../../docs/superpowers/plans/2026-11-08-tier-e-remaining-work.md), [fixed1 child plan](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-1-native-child-plan-fixed1.md), [native full report](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-1-native-implementation/report.md), and [independent decision](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-1-native-full-build/controller-decision.md).

**Needed:**
1. Keep implementation within the exact seven paths and preserve the existing 21-string path, its checks, and fail-closed expanded-profile refusal.
2. Retain controller limits and all qualification gaps; do not add a positive authority, Ready, or guard activation claim.
3. After implementation, record RED/GREEN results and request the separately required qualification and independent review.

---
<!-- id: issue-20261108-190054-405-t4-coordinate-versioned-native-projection-parsing-  file: issue-20261108-190054-405-t4-coordinate-versioned-native-projection-parsing-.md -->
