---
title: "Simplify explicit approved Spring JDBC proxy wiring"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
   - "integrations/spring/mazewall-spring-boot/src/main/java/io/mazewall/spring/SpringOperationBinder.java"
   - "integrations/spring/mazewall-spring-boot/src/test/java/io/mazewall/spring/JdbcProxyFactoryTest.java"
   - "integrations/spring/mazewall-spring-boot/src/test/java/io/mazewall/spring/PublicBindingApiTest.java"
   - "integrations/spring/mazewall-spring-boot/src/test/java/io/mazewall/spring/ReviewedJdbcBindingTest.java"
   - "integrations/spring/mazewall-spring-boot/S1-S2-contract.md"
   - "integrations/spring/mazewall-spring-boot-starter/README.md"
   - "demos/spring-boot-sandbox/src/main/java/demo/boot/ReportConfiguration.java"
   - "demos/spring-boot-sandbox/README.md"
   - "tests/consumers/spring-jdbc/src/main/java/consumer/spring/NativeJdbcConsumer.java"
target_symbols:
  - "SpringOperationBinder"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Simplify explicit approved Spring JDBC proxy wiring

**Context:**
Approved inline Plan A: remove mechanical four-advisor assembly while preserving application bootstrap, genuine resources, exact singleton approval and existing JDBC ownership. No automatic scanning, baseline, approval or proxy replacement. Implementation authorized; merge/publication separate.

**Needed:**
1. A1 RED then GREEN: add typed createJdbcProxy(contract,target,authorization), exact retained JDBC guard and public consumer tests; A2 adopt in ordinary demo/published JDBC consumer, preserve unbound and native retirement controls, update guides and qualify exact-source/full build.

## Inline execution

- [x] A1 typed factory, public API and host behavior tests.
- [x] A2 published/demo adoption, unchanged negative controls and guides.
- [x] Exact-source acceptance, full build and one independent final review.

Pre-flight: A2 consumes A1's `createJdbcProxy(Class<T>, T, Advisor)`; existing
`Approval`/`bind`/`validateRequiredBindings` remain authoritative and unchanged.
Base: `efa6d9ec11e1c573c6d84c8f6ab34ecc9e9449a2`.
Ruling: use this scoped record for progress, not plan files, worktrees or a separate
journal — preserves the operator's explicit workflow. Codanna blast-radius is
unavailable (INDEX_MISSING); exact callers were inspected instead.

A1 RED: real foreign-package Java compilation fails its assertion before the helper
exists. GREEN: public API and four real Spring/H2 factory cases pass, including
unapproved zero-entry rejection, exact retained advisors/target, JDBC commit,
wrong singleton/loader/generation and post-bind advisor/singleton drift. The full
Spring module has 117 tests with zero skips/failures/errors. Demo and published
JDBC consumer now use the helper; all deliberate unbound/unsupported/cancellation
controls remain. Common-domain JDBC host coverage also uses it.

Ruling: keep helper-specific rejection checks in `JdbcProxyFactoryTest` rather
than duplicating the existing manual-path `OperationBindingTest` fixture. Both
paths remain tested. Final expensive acceptance/build gates cover both independent
changes once; their results are still required, not inferred from these host tests.

Final review: no Critical/Important findings initially; the reviewer identified a
plain-JDK-proxy input accepted by assembly and rejected only by later binding.
Re-graded as an important approved-contract gap (not an execution bypass): the new
factory promises non-proxy inputs and should fail at that boundary. Added a real
`Proxy.newProxyInstance` regression: RED is expected IllegalArgumentException but
nothing thrown. Rejecting `Proxy.isProxyClass` closes that case without broadening
the existing binding or core contract. This is the single final review fix pass.

GREEN: Spring build (all 117 tests, zero skipped/failed) and configured static/
coverage checks pass with configuration-cache reuse after correction. Initial
exact-source aggregate passed 9m19s on `d6eb4fc014a3230936d0a91737ceaf510fdc8daf`,
including the 117 Spring tests, ordinary-JAR JDBC/Security, both Boot host tests,
four finished-Boot presentations and unchanged ownership matrix; the initial full
build passed 5m41s. Final-source aggregate/build are rerun after the correction.

Final code producer `34bb6a89d848e88108b78dacb49d827a44781afc` passes exact-source
optional acceptance in 9m53s (one executed, zero skipped/failed), then the full build
in 5m20s. No gates, policies, resource limits or existing test budgets were relaxed.
The independent review's only input-validation gap is fixed with RED→GREEN and
the complete Spring suite; no deferred review findings remain. Engineering work is
complete locally; separate scoped PR/CI, operator integration and publication are
not performed by this change. Keep the issue open until approved landing evidence.

---
<!-- id: issue-20261025-223935-632-simplify-explicit-approved-spring-jdbc-proxy-wirin  file: issue-20261025-223935-632-simplify-explicit-approved-spring-jdbc-proxy-wirin.md -->
