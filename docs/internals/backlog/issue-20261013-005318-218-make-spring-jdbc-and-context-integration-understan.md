---
title: "Make Spring JDBC and context integration understandable on stage"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "demos"
target_modules: []
target_files:
  - "demos/spring-boot-sandbox"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make Spring JDBC and context integration understandable on stage

**Context:**
Finished Boot/JDBC and separate ordinary-JAR Security proofs exist, but adoption requires manual measurement, external fixture review and many developer commands. The Boot guide links a historical Task13 recipe; the Security-free Boot demo must not claim ordinary-JAR Security evidence.

**Needed:**
1. Expose the existing finished-JAR and ordinary-JAR fixture scenarios as readable presentation-tagged JUnit tests in their independent consumer builds, showing real report/template effects, H2 commit/rollback, virtual-caller/platform-worker handoff, separately labeled Admin/Bob context restoration and zero-entry failures. Keep exact published artifacts and external fixture approval; do not wrap scripts as an opaque green test.

**Acceptance:** Finished Boot and ordinary-JAR Security remain separately named and source-bound. Real database rows, exact allowed report bytes, ungranted sibling denial, restored caller/worker contexts, zero-entry unsupported/missing-review failures and captured worker retirement are visible. Existing fixture approval stays external and explicitly test-only. Never equate JDBC rollback with undoing file/network effects or claim that the Security-free Boot app proves Security semantics.

---
<!-- id: issue-20261013-005318-218-make-spring-jdbc-and-context-integration-understan  file: issue-20261013-005318-218-make-spring-jdbc-and-context-integration-understan.md -->
