---
title: "Make Spring JDBC and context integration understandable on stage"
severity: "MEDIUM"
status: "resolved"
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

## Resolution evidence

Current producer `4edb465fd2cc014f5c9b1af886dd4f0931271172` passes the actual
source-bound `optionalIntegrationCheck` in 9m59s with zero owning failures/skips.
The existing named `BootPresentationTest` and `SpringSecurityPresentationTest`
execute against actual fresh Maven publications, with separate finished-Boot and
ordinary-JAR Security evidence. They assert real report/template bytes, H2 effects,
Admin/Bob context/SQL rollback/restoration, actual ready/held-draining/closed
diagnostics, optional Actuator absence and captured physical retirement. Full
finished-JAR measurement/review/ownership negatives also execute. Fixture review
remains external and explicitly test-only; no automatic production approval or
rollback of external file effects is claimed. Updated guides use fresh current
coordinates rather than operator-private historical paths.
See resolved `issue-20261016-101505-976-qualify-optional-integrations-and-expose-read-only`
for exact source and verification custody. Human usability remains a separate task.

---
<!-- id: issue-20261013-005318-218-make-spring-jdbc-and-context-integration-understan  file: issue-20261013-005318-218-make-spring-jdbc-and-context-integration-understan.md -->
