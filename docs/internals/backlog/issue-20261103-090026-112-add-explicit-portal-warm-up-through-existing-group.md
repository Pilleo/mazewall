---
title: "Add explicit Portal warm-up through existing group lifecycle owners"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261103-090025-757-generate-typed-portal-operations-with-bounded-subm"
component: "portal-runtime"
target_modules:
  - ":portals:runtime"
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntime.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalBrokerOwnership.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalWarmUpTest.kt"
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/PortalWarmUpIntegrationTest.kt"
  - "tests/consumers/portal-plugin"
  - "docs/guides/portal-service-isolation.md"
target_symbols:
  - "PortalRuntime"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Add explicit Portal warm-up through existing group lifecycle owners

**Context:**
Portal developer-experience plan P05: amortize worker startup through explicit selected-group warm-up, preserving exact group/profile/artifact readiness and close/start ownership.

**Needed:**
1. Add `warmUp()` for all groups and `warmUp(Collection<PortalServiceId>)` for an explicit snapshotted selection. Validate all selectors first and deduplicate groups; an empty selection warms none.
2. Reuse `brokerFor` and `PortalBrokerOwnership`, existing per-group startup budgets and required readiness. Keep successful prior groups owned if a later group fails.
3. Add startup examples, local health output and deterministic bind/warm-up/close race coverage.

**Plan and dependencies:**
- [Implementation plan P05](../../superpowers/plans/2026-11-03-portal-developer-experience.md) and [warm-up/locality contract](../designs/portal/portal-developer-experience.md#5-warm-up-placement-and-state-locality).
- P03 supplies the generated binding and boxed selectors for final qualification; the acquisition implementation reuses current runtime owners.

**Acceptance:**
- Two selected services in one group acquire one broker; unknown selectors acquire none. Warm-up is idempotent and concurrent callers join the same acquisition.
- A real worker reports one construction per instance after repeated warm-up and calls. Actual required readiness precedes the successful return.
- Warm-up/bind racing close cannot publish a late process or proxy. Failed/late acquisitions remain owned and retryable until actual retirement.
- Missing artifacts or required protection reject with original service/group failure evidence. Group membership and planned process counts stay explicit; no automatic group merging.
- Guidance distinguishes process readiness from application/JIT warm-up and advises startup before a restrictive host baseline.
- Neither local health availability nor a capacity report proves readiness. A later host spawn restriction can prevent replacement even after warm-up; qualify the entire intended lifecycle and never bypass that restriction. Representative application checks are explicit side-effect-free calls, not automatic warm-up side effects.

**Verification:**
- [Junior implementation walkthrough: P05](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#p05--warm-up-through-existing-acquisition) explains selector validation, held acquisition, constructor counts and close/readiness evidence.
- Run `./gradlew :portals:runtime:test --tests '*PortalWarmUpTest'` and `./gradlew :portals:runtime:integrationTest --tests '*PortalWarmUpIntegrationTest'`, including actual child death after close.
- Run `./gradlew build`, backlog validation and whitespace checks at completion.

---
<!-- id: issue-20261103-090026-112-add-explicit-portal-warm-up-through-existing-group  file: issue-20261103-090026-112-add-explicit-portal-warm-up-through-existing-group.md -->
