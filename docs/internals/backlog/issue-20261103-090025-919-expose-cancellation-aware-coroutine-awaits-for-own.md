---
title: "Expose cancellation-aware coroutine awaits for owned invocation handles"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261103-090025-757-generate-typed-portal-operations-with-bounded-subm"
component: "core"
target_modules:
  - ":enforcer"
  - ":integrations:koog"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/InvocationHandle.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/InvocationHandleCoroutines.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/InvocationHandleCoroutinesTest.kt"
  - "enforcer/api/enforcer.api"
  - "integrations/koog/src/main/kotlin/io/mazewall/koog/PortalToolAdapter.kt"
  - "integrations/koog/src/test"
  - "tests/consumers/portal-plugin"
  - "docs/guides/portal-service-isolation.md"
target_symbols:
  - "InvocationHandle"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Expose cancellation-aware coroutine awaits for owned invocation handles

**Context:**
Portal developer-experience plan P04: reuse the existing compile-only coroutine dependency and invocation ownership model to offer framework-independent result/end awaits; caller cancellation must reach the owning handle.

**Needed:**
1. Add optional `awaitResult()` and observational `awaitExecutionEnd()` extensions over the existing invocation handle, using enforcer's current compile-only coroutine support.
2. Route cancelled result waits through `requestCancellation()` on the original handle. Keep execution-end waiting independent; cancelling observation must not cancel the invocation.
3. Reuse the helper in Koog only while preserving argument/result validation and its cancellation races; qualify generated Kotlin calls and a coroutine-free Java consumer.

**Plan and dependencies:**
- [Implementation plan P04](../../superpowers/plans/2026-11-03-portal-developer-experience.md) and [target API](../designs/portal/portal-developer-experience.md#2-target-application-surface).
- P03 is required for completed generated-consumer qualification; helper development can start against the existing `InvocationHandle`.

**Acceptance:**
- Cancelling a waiting coroutine requests cancellation once for that waiter without completing physical execution end early. Completing result/end independently does not complete the other stage.
- Cover already-complete handles, completion/cancellation races, immediate cancellation before submission in adapters, original exception identity and end-only wait cancellation.
- Prompt cancellation is retained; cancelling a detached CompletionStage copy is never the only ownership action.
- Existing Java runtime APIs load and execute without a coroutine JAR. No new module or dependency is introduced.

**Verification:**
- [Junior implementation walkthrough: P04](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#p04--coroutine-observation-and-cancellation) distinguishes fake-handle signals, cancellation races, optional runtime linkage and the Koog gate.
- Run `./gradlew :enforcer:test --tests '*InvocationHandleCoroutinesTest'` and affected Koog tests via `./scripts/koog-build.sh -- :integrations:koog:test` if its adapter changes.
- Run the staged Java/Kotlin consumers. Use `./gradlew build` as the completion gate, or `./scripts/koog-build.sh -- build` to include Koog when its adapter changes; also validate backlog and whitespace.

---
## Repository-review acceptance expansion — 2026-11-04

[KD-E3](../reviews/2026-11-04-repository-review-findings.md#kd-e3) is a concrete acceptance
case for this shared await: PortalToolAdapter's throw-capable invokeOnCancellation callback
routes ordinary/fatal request failure to the global coroutine handler, unlike ThreadInvocationAwait's
structured caller policy. Current actual handle/channel/source probes reproduce it. Move failure
handling to the wait boundary, request once and merge using existing fatal-first rules. Assert
exact caller failure/suppression, zero uncaught-handler events, already-cancelled/during-submit/
late-result controls and independent physical end. Normal cancellation races are sound; no
duplicate adapter redesign or new blocking waiter is required.

---
<!-- id: issue-20261103-090025-919-expose-cancellation-aware-coroutine-awaits-for-own  file: issue-20261103-090025-919-expose-cancellation-aware-coroutine-awaits-for-own.md -->
