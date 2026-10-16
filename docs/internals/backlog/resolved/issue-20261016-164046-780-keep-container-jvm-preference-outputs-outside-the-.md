---
title: "Keep container JVM preference outputs outside the source checkout"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "scripts/run_containerized_tests.sh"
target_symbols: []
open_questions: false
---

# Resolved: Keep container JVM preference outputs outside the source checkout

**Context:**
PR 534 hosted native optional acceptance completed every required producer and consumer phase, then its original-checkout guard detected Groovy Java preferences under .java/.userPrefs. Run 38931163776 JUnit identifies the exact generated paths. Preserve the strict checkout guard and route container JVM preference outputs to the existing caller-owned cache.

**Needed:**
1. Reproduce Java preference output with a relative JVM home, pin an absolute caller-owned preference root for container JVMs, and verify native hosted acceptance without ignoring source changes.

**Evidence and correction:**
- Hosted JUnit from run `38931163776` names only Groovy preferences and locks under
  `.java/.userPrefs`; every native/published consumer phase completes before the
  strict original-checkout comparison fails. The comparison remains mandatory.
- Real JDK 25 RED/GREEN (`run-1e7ea1d7629f`) uses a relative `user.home`: a real
  preferences write pollutes its source working directory without configuration,
  while an absolute `java.util.prefs.userRoot` writes only to the owned cache.
- The container launcher supplies that property via `JAVA_TOOL_OPTIONS` rooted
  under its existing caller-owned `/container-gradle-home` mount. This also reaches
  nested tool JVMs; no source path is ignored, filtered, or exempted from validation.
- Full build with both optional integrations included passes
  (`run-d4a55c9d55c6`, 5m39s), including container-launcher ownership controls.
  This resolves the local correction; the PR must still pass hosted native
  acceptance and its unchanged original-checkout comparison before merging.

---
<!-- id: issue-20261016-164046-780-keep-container-jvm-preference-outputs-outside-the-  file: issue-20261016-164046-780-keep-container-jvm-preference-outputs-outside-the-.md -->
