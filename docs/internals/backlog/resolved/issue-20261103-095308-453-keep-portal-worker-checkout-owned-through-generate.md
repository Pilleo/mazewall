---
title: "Keep Portal worker checkout owned through generated reply validation"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies:
  - "issue-20261103-095307-699-reject-noncanonical-and-incompletely-consumed-gene"
component: "portal-runtime"
target_modules:
  - ":portals:runtime"
  - ":portals:codegen"
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalBrokerCallMachine.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalStatefulInvocationGate.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntime.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalCallException.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalBrokerCallMachineTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalRuntimeTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalBrokerOwnershipTest.kt"
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/ProcessBrokerIntegrationTest.kt"
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/PreparedPortalOperationIntegrationTest.kt"
  - "docs/internals/designs/enforcer/process-portal-design.md"
  - "docs/internals/designs/portal/local-service-isolation-design.md"
target_symbols:
  - "ProcessBroker"
  - "PortalStatefulInvocationGate"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Keep Portal worker checkout owned through generated reply validation

**Context:**
CG05: source review finds ProcessBroker returns a successful frame worker to the pool before generated host decoding; decoder IllegalArgumentException is outside broker retirement and does not terminally lose a stateful group. Native end-to-end reproduction remains required.

**Needed:**
1. Begin with a real-child regression that returns a matching success header and malformed generated body. Demonstrate the current checkout-release order; cover stateless and singleton bindings plus the existing prepared-call path.
2. Extend the current broker lifecycle so a successful frame remains owned by the exact checkout until CG04's generated wire validation succeeds. Preserve raw public invoke signatures and use only the narrow generated-code bridge required by separately compiled consumers.
3. Route corrupt responses through failed-checkout retirement with preserved cause and conservative invocation evidence. Terminally lose singleton state; never replay the call or silently continue it on another worker.
4. Distinguish protocol validation from trusted host DTO construction and completed remote business exceptions. Retain the current separation between result delivery and physical retirement across cancellation, close and fatal errors.

**Scope and dependencies:**
- Follows [CG04](issue-20261103-095307-699-reject-noncanonical-and-incompletely-consumed-gene.md)'s strict decoding/classification.
- [Review CG05](../../reviews/2026-11-03-portal-codegen-hardening.md#cg05--keep-response-validation-within-checkout-ownership) is source-confirmed only. A native reproduction remains required; the review's codec probes do not establish worker retirement.
- Reuse existing broker/pool/stateful-gate owners. P03's generated operations must later use the same boundary, without another checkout owner or arbitrary decoder framework.

**Acceptance:**
- A worker with a malformed generated response cannot be checked out again, including while validation is deliberately paused and another caller arrives.
- A singleton group becomes terminally lost on corrupt responses; later calls fail without dispatch. Stateless replacement follows existing policy and never replays the failed invocation.
- Well-formed responses and completed business exceptions retain normal reuse behavior. Ordinary trusted DTO-constructor failures preserve their identity and are not relabelled as wire corruption.
- Cancellation/close/fatal-failure races neither leak the checkout nor release physical accounting early; cleanup uses the exact offending worker identity.

**Verification:**
- [Junior implementation walkthrough: CG05](../../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#cg05--validation-owned-checkout) details real-child barriers, competing checkout, exact-worker retirement and failure controls.
- Run relevant broker machine/ownership/runtime tests and the real-child malformed-response regression in `ProcessBrokerIntegrationTest` / `PreparedPortalOperationIntegrationTest`.
- Run codegen compile/round-trip tests for the consumer bridge. Run `./gradlew build`, backlog and whitespace checks at completion; require child identity and retirement evidence before closure.

---
## Repository-review revalidation — 2026-11-04

[CG05/PCG-05/PRT-06](../../reviews/2026-11-04-repository-review-findings.md#cg05) is one
source-confirmed ordering defect: broker release precedes generated validation and ordinary
decode failure misses singleton state loss. Native reproduction remains required: real malformed
success body, held decode/competing checkout, exact worker retirement and non-reuse. P03/P07/P09/P13
must reuse this owner; strict decoding alone and invocation replay are not lifecycle fixes.

---
## Resolution Evidence (2026-11-21)

- **Broker call lifecycle & validation-owned checkout (`:portals:runtime`):**
  - Updated `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalBrokerCallMachine.kt` to transition `AwaitingReply + ReplyReceived(Response)` into `ValidatingReply(request)` with effect `ValidatePayload(payload)`, completing and emitting `ReleaseWorker` only on `ReplyValidated` while routing `ReplyValidationFailed` and out-of-order/transport failures to `Failed`.
  - Added `ProcessBroker.invokeValidated` (while preserving `ProcessBroker.invoke` unchanged) and `validateCheckedOutReply` / `BrokerCallOutcome` in `portals/runtime/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt`: `slot` stays exclusively checked out while `decodeReply` executes, retires the exact worker PID (`PortalInvocationRetirement.retain` + `scheduleRecycle`) on `PortalCallException` / `InterruptedException` / `Error` (and transitions `STATEFUL_SINGLETON` to `Failed` + `PortalStatefulInvocationGate` `STATE_LOST`), and preserves ordinary trusted host exceptions (`BrokerCallOutcome.HostFailure`) without misclassifying healthy workers.
- **Generated consumer bridge & deferred host DTO construction (`:portals:codegen`):**
  - Updated `portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt` so generated host stubs invoke `this.broker.invokeValidated(...)` and run wire decoding plus `reader.requireExhausted()` before releasing the worker slot, using `decodeDeferred` for record-containing returns so trusted host DTO constructors run only after wire exhaustion and healthy-worker release.
- **Documentation updates:**
  - Updated `docs/internals/designs/enforcer/process-portal-design.md`, `docs/internals/designs/portal/local-service-isolation-design.md`, and `docs/superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md`.
- **Verification:**
  - `./gradlew :portals:runtime:ktlintCheck :portals:runtime:detektMain :portals:codegen:ktlintCheck :portals:codegen:detektMain :detektWarningRatchet :portals:runtime:test` — passed (`162` unit tests across 35 suites in `:portals:runtime:test`, `0` failures, `0` skips; `0` added detekt warnings).
  - `./gradlew :portals:codegen:test` — passed (`56` tests across 4 suites, `0` failures, `0` skips, exercising compiled `StrictContractServicePortalStub` on stateless and `STATEFUL_SINGLETON` brokers, trailing-byte + DTO rejection precedence, and healthy-worker reuse).
  - `./gradlew :portals:runtime:ktlintCheck :portals:runtime:integrationTest --tests '*ProcessBrokerIntegrationTest' --tests '*PreparedPortalOperationIntegrationTest'` — passed (`27` tests in `ProcessBrokerIntegrationTest` and `4` tests in `PreparedPortalOperationIntegrationTest`, `0` failures, `0` skips, proving real-child malformed replies, held-decode competing-caller barrier, exact PID reap/non-reuse without replay, stateful singleton terminal loss across `PortalRuntime.prepare`, business/host-DTO controls, fatal `Error` suppression, concurrent `close()`, and cancellation cleanup retry).

---
<!-- id: issue-20261103-095308-453-keep-portal-worker-checkout-owned-through-generate  file: issue-20261103-095308-453-keep-portal-worker-checkout-owned-through-generate.md -->
