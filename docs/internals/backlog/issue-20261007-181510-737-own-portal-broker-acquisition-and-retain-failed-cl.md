---
title: "Own Portal broker acquisition and retain failed close resources transactionally"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [portals-runtime]
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntime.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalBrokerOwnership.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntimeOwnershipLifecycle.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalBrokerOwnershipTest.kt"
  - "docs/internals/designs/enforcer/process-portal-design.md"
  - "docs/internals/designs/portal/local-service-isolation-design.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Own Portal broker acquisition and retain failed close resources transactionally

**Context:**
Approved P0: PortalRuntime close races lazy broker compute/start and publishes closed before cleanup succeeds, clearing failed owners. Register acquisition before startup, prevent publication after closing, close startup resources and join acquisition, preserve failed cleanup for retry and original fatal identity.

**Needed:**
1. Deterministically race acquisition/startup with close and test failed cleanup retry and no post-close publication.

## Evidence

Application acquisition is registered before resource construction/startup. A broker is owned before its start call; runtime close stops publication/admission, retires visible startup owners, joins late acquisition and only publishes Closed after acquisition and all owners retire. Failed cleanup retains its owner for later retry. Proxies recheck runtime admission before invocation and cannot publish usable work after close. Failed ready futures are not reported as healthy live brokers. The existing broker remains the process/native lifecycle owner.

Host cases cover late construction, already-owned startup cancellation, cleanup retry, original startup fatal identity, fatal rollback precedence and non-resurrecting lifecycle decisions. The missing-owner API baseline is `run-16a6b06db447`; fatal rollback precedence independently failed before correction (`run-2f1b51c1b0f1`) and passed with the full Portal suite. Pure lifecycle decisions and small resource-boundary helpers retain unchanged warning budget (`run-a3af8e5e3232`). Broker process integration passed (`run-e176641dc462`). Shared Portal merge checkpoint passed in 1m43s, 265 tasks (42 executed/223 up-to-date), `run-21e594d7cc0f`.

Runtime acquisition join is bounded; native/process retirement still uses the broker's existing cleanup deadline. Public combined limit configuration remains P2 work. Issue stays open for repository review.

---
<!-- id: issue-20261007-181510-737-own-portal-broker-acquisition-and-retain-failed-cl  file: issue-20261007-181510-737-own-portal-broker-acquisition-and-retain-failed-cl.md -->
