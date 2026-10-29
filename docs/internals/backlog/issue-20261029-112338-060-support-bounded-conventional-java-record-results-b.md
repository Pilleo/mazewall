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

- [x] Task10 bind-time shape/accessor/deployment checks and negative tests.
- [x] Worker inside-transaction bounded value validation and actual JDBC rollback.
- [ ] Published record consumer, docs, focused/static/native/full checks and review.

Pre-flight: existing scalar/String/byte[] results remain compatible. Records are
strict data shapes from the reviewed deployment; reject custom accessors/hooks.
The guard must validate before transaction commit, not after dispatcher delivery.
JDK22 floor and existing Spring ASM dependency are preserved. This later milestone
is separate from the primary String-returning pitch application path.

Host shape/value/JDBC tests observed3 RED failures, then GREEN. A separate SUPPORTS
propagation regression observed RED, then GREEN with physical-transaction-only
record binding. Actual H2 rollback covers oversized aggregate output and caught
nested invalid output; scalar/String/byte[] suites remain unchanged. Accessors are
checked without executing them; existing Spring ASM is reused, no new dependency,
serializer or public symbol added. Both Spring library/starter builds pass.

An independent ordinary-JAR published consumer has observed RED against the prior
library at actual record binding. Its GREEN exact-publication/native evidence and
final review remain outstanding. Normal HTTP report still returns String.

Ruling: admit records only with the qualified JDBC guard and a physical-transaction
propagation, because value checks must precede commit. S0/no-guard semantics and
existing scalar results are unchanged. Null whole-record/reference components stay
compatible with ordinary materialized-reference semantics. Cost if wrong: a narrow
record operation fails binding, not silent commit of an unvalidated result.

Core-symbol blast-radius lookup returned PARTIAL/INDEX_MISSING; exact source/caller
reads and the complete Spring suite were used instead of treating that hint as proof.

---
<!-- id: issue-20261029-112338-060-support-bounded-conventional-java-record-results-b  file: issue-20261029-112338-060-support-bounded-conventional-java-record-results-b.md -->
