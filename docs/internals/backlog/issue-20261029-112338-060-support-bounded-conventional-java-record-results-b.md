---
title: "Support bounded conventional Java record results before Spring transaction commit"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
  - "integrations/spring/mazewall-spring-boot/src/main/java/io/mazewall/spring/SpringRecordResults.java"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Support bounded conventional Java record results before Spring transaction commit

**Context:**
Approved later pitch Task10 additive library extension: concrete reviewed same-deployment Java records with1-16 scalar/wrapper/String components, aggregate UTF8 strings<=65536 bytes, conventional accessors only, no arrays/collections/nesting/lazy entities/Kotlin data classes/custom serialization. Bind-time shape validation and worker/inside-transaction value checks must cause rollback on invalid output. Existing scalar/String/byte[] contract and JDK22 floor preserved; reuse Spring ASM, no new parser dependency.

**Needed:**
1. Inspect callers/design/impact, write shape/bounds/custom-accessor and real JDBC rollback tests RED->GREEN, implement minimal closed result contract inside diagnosis before commit, reject incompatible rollback rules, add published record consumer and verify focused/static/native/full gates.

## Outcome

- Bind-time checks require reviewed same-deployment records, conventional accessor
  bytecode and no Kotlin metadata; accessors are not executed during validation.
- Admission requires the qualified JDBC guard and physical-transaction propagation.
  Value validation precedes commit, including caught nested invalid-output failures.
- Null records/reference components and existing scalar/String/byte[] behavior stay
  compatible. The JDK22 floor and existing Spring ASM dependency are unchanged.
- Host shape/bounds tests and the published `verifyRecordNative` consumer prove
  real SQL rollback, recovery and physical retirement; see
  [shared qualification](issue-20261029-112338-058-qualify-copied-pitch-consumers-measurements-and-ho.md).

Open pending managed landing. This is not general DTO/JPA/reactive/Kotlin support
or a hostile-code boundary; the headline HTTP report remains String-returning.

---
<!-- id: issue-20261029-112338-060-support-bounded-conventional-java-record-results-b  file: issue-20261029-112338-060-support-bounded-conventional-java-record-results-b.md -->
