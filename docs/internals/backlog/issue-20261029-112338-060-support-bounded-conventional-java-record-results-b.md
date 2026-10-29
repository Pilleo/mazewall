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

## Inline progress

- [ ] Task10 bind-time shape/accessor/deployment checks and negative tests.
- [ ] Worker inside-transaction bounded value validation and actual JDBC rollback.
- [ ] Published record consumer, docs, focused/static/native/full checks and review.

Pre-flight: existing scalar/String/byte[] results remain compatible. Records are
strict data shapes from the reviewed deployment; reject custom accessors/hooks.
The guard must validate before transaction commit, not after dispatcher delivery.
JDK22 floor and existing Spring ASM dependency are preserved. This later milestone
is separate from the primary String-returning pitch application path.

---
<!-- id: issue-20261029-112338-060-support-bounded-conventional-java-record-results-b  file: issue-20261029-112338-060-support-bounded-conventional-java-record-results-b.md -->
