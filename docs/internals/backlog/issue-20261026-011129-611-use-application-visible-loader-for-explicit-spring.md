---
title: "Use application visible loader for explicit Spring proxy assembly"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
   - "integrations/spring/mazewall-spring-boot/src/main/java/io/mazewall/spring/SpringOperationBinder.java"
   - "integrations/spring/mazewall-spring-boot/src/test/java/io/mazewall/spring/JdbcProxyClassLoaderTest.java"
   - "integrations/spring/mazewall-spring-boot/src/test/java/io/mazewall/spring/fixture/SharedOperation.java"
   - "integrations/spring/mazewall-spring-boot/S1-S2-contract.md"
target_symbols:
  - "SpringOperationBinder.createJdbcProxy"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Use application visible loader for explicit Spring proxy assembly

**Context:**
Authorized follow-up to ponytail review: createJdbcProxy defines the Spring proxy in contract.getClassLoader(), which fails when the interface is in a shared parent URLClassLoader without Spring and the application/Spring live in a supported child loader. The original manual default-loader path binds and executes in this same topology. Preserve exact target, four advisors, external approval, genuine owner and all existing rejection gates.

**Needed:**
1. Create a permanent ordinary-JAR parent-interface/child-application loader regression and watch the actual helper fail RED with SpringProxy not visible. Then use Spring default getProxy(), prove unbound rejection plus exact registered binding and execution GREEN, and run module/static/cache, source-bound acceptance, full build and one scoped review.

## Inline plan and progress

- [x] RED: actual helper fails for a parent-interface JAR without Spring and a
  supported ordinary child application loader containing Spring/implementation.
- [x] GREEN: use Spring's normal `getProxy()` selection, keeping approval and all
  existing rejection/ownership checks. Require exact proxy/target/advisor/manager
  identities, zero unbound body entries and successful registered invocation.
- [x] Spring suite/static/cache, committed-source acceptance, full build, scoped
  final review and reference/backlog checks.

Base: `68c817be`. Existing S1/S2 contract is authoritative. The public helper's
signature, callers, dependency versions, kernel policies and all budgets stay fixed.
Pre-flight: one shared helper correction; its existing demo/published callers need
no signature changes. Codanna impact evidence is unavailable, so callers were
inspected directly. Keep the plan inline and progress in this scoped issue; no
worktrees, plan files or separate execution journals.

RED observed in the actual module test: application-loader binding fails with
`IllegalArgumentException: org.springframework.aop.SpringProxy ... is not visible`
from the parent URLClassLoader. The regression packages the real compiled API and
implementation in separate ordinary JARs; the parent demonstrably cannot load
Spring and the child demonstrably owns the binder. It uses real Spring/H2 host
binding, not native attestation or a fabricated production runtime.

GREEN: the only production correction is `factory.getProxy()` instead of forcing
the contract loader. The packaged-parent/child regression now preserves frozen
proxy, target, four-advisor and manager identities, rejects unbound calls before
body entry, binds the exact registered singleton and invokes the shared interface
once. All 118 Spring tests pass without skips/failures/errors; the module build's
configured analysis/coverage gates and strict configuration-cache reuse pass.
Exact-source aggregate/full build and scoped final review remain pending.

Final code producer `cbd04e308c7e3440db63b57305f976220f7cc215` passes exact-source
optional acceptance in 10m56s (one executed, zero skipped/failed), including the new
classloader regression, all 118 Spring cases, direct/agent Koog modes, ordinary-JAR
JDBC/Security, finished Boot and the ownership/negative matrix. The full build then
passes in 5m18s. No budgets, policies, bindings or dependencies were changed.

Independent scoped review reports no Critical, Important or Minor findings and
confirms default loader selection matches Spring 7.0.9's original manual path.
Final scope rulings: arbitrary context-loader fallbacks and new Boot/exploded
compatibility claims are not added; this regression proves real host Spring
binding, not native attestation. Existing native deployment gates remain required
and passed. Earlier feature code is not re-audited by this scoped fix review.
The author's inspected exact-source/full results supply the review's pending gates.
No deferred review findings remain. Nothing was pushed, merged or published;
the issue stays open for the ordinary approved landing path.

---
<!-- id: issue-20261026-011129-611-use-application-visible-loader-for-explicit-spring  file: issue-20261026-011129-611-use-application-visible-loader-for-explicit-spring.md -->
