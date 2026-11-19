---
title: "Qualify actor-style stateful Portal bindings on the existing queue"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies:
  - "issue-20261103-085927-123-compare-warm-portal-calls-with-executor-and-persis"
  - "issue-20261103-090025-757-generate-typed-portal-operations-with-bounded-subm"
  - "issue-20261103-090026-112-add-explicit-portal-warm-up-through-existing-group"
component: "portal-runtime"
target_modules:
  - ":portals:runtime"
  - ":portals:worker"
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalStatefulInvocationGate.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalOperationOwner.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PreparedPortalOperationTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalServiceRegistryTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalRuntimeTest.kt"
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/PortalStatefulQueueIntegrationTest.kt"
  - "portals/worker/src/test/kotlin/io/mazewall/portal/worker/PortalWorkerServiceRegistryTest.kt"
  - "tests/consumers/portal-plugin"
  - "benchmarks/portal-comparison"
  - "docs/guides/portal-service-isolation.md"
target_symbols:
  - "PortalStatefulInvocationGate"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Qualify actor-style stateful Portal bindings on the existing queue

**Context:**
Portal developer-experience plan P07: expose predictable state affinity and serialized prepared submissions using the existing singleton broker queue; preserve legacy synchronous overlap rejection and terminal state loss.

**Needed:**
1. Qualify generated bindings against the existing singleton process and prepared dispatch queue, fixing only regression-demonstrated gaps in those owners.
2. Add a worker-local cached dictionary/counter example and a combined parse/validate/summarize method that keeps intermediate state inside the worker.
3. Document group sharing, synchronous/prepared interaction and explicit recovery after terminal state loss; compare initialization and steady-state locality separately.

**Plan and dependencies:**
- [Implementation plan P07](../../superpowers/plans/2026-11-03-portal-developer-experience.md) and [state/locality contract](../designs/portal/portal-developer-experience.md#5-warm-up-placement-and-state-locality).
- Requires P03 bindings and P05 warm-up; P01 owns the comparison extended by this issue.

**Acceptance:**
- A single submitter queues three counter calls without waiting and gets 1, 2, 3 from one PID/construction. Queue order is enqueue order; racing submitters have no extra ordering guarantee.
- Same-group services/bindings share their queue and worker-local collaborator; independent groups have distinct processes. Incompatible artifact/profile/mode membership rejects.
- Completed business errors preserve state. Unknown completion, crash or active cancellation lose it terminally; queued calls then have no guest entry and no automatic replacement/replay.
- Cancelling an untouched queued call leaves active state usable. Existing synchronous overlap rejection remains NOT_STARTED and mixed-use guidance is explicit.
- No extra mailbox, process/session-per-bind model, remote object identity or actor runtime is introduced.
- Qualify two stateless workers with independent persistent service graphs/caches using held concurrent calls, without assuming round-robin scheduling. STATELESS provides no affinity or per-call reset; request/tenant authorization stays request-local. P01 measures duplicated cache memory.

**Verification:**
- [Junior implementation walkthrough: P07](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#p07--state-locality-and-loss-boundaries) covers FIFO/locality controls, queued versus active cancellation and persistent stateless graphs.
- Run affected runtime/worker unit tests and `./gradlew :portals:runtime:integrationTest --tests '*PortalStatefulQueueIntegrationTest'` with real state-loss/physical-close controls.
- Run P01's state-locality comparison, `./gradlew build`, backlog validation and whitespace checks.

---
<!-- id: issue-20261103-090026-517-qualify-actor-style-stateful-portal-bindings-on-th  file: issue-20261103-090026-517-qualify-actor-style-stateful-portal-bindings-on-th.md -->
