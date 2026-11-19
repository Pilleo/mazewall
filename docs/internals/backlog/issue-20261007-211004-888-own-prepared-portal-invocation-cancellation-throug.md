---
title: "Own prepared Portal invocation cancellation through physical retirement"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["portals:runtime"]
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntime.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/OwnedPortalInvocation.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalInvocationRetirement.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PreparedPortalOperationTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalRetirementAdmissionTest.kt"
target_symbols:
  - "PortalRuntime"
  - "OwnedPortalInvocation"
  - "PortalInvocationRetirement"
  - "ProcessBroker"
open_questions: false
---

# 🟡 [Severity: HIGH]: Own prepared Portal invocation cancellation through physical retirement

**Context:**
Approved P2 requires public PreparedPortalOperation.submit and minimal InvocationHandle stages. Cancellation must retain finite admission and physical execution ownership through exact failed-worker reaping and cleanup retry.

**Needed:**
1. Add bounded broker-owned host dispatch, public typed operation preparation, and exact worker retirement tracking; verify queued and in-flight cancellation.

**Implementation evidence (pending repository review):**
- Public `PortalRuntime.prepare` selects a service/broker and returns a minimal shared `InvocationHandle` from `PreparedPortalOperation.submit`. Host mapping is explicitly outside child protection, rejects broker substitution/local fallback, and has a finite JDK host dispatch owner.
- Synchronous and prepared operations share admission. The submit deadline survives host queueing and broker checkout/transport. Queued cancellation never enters mapping; running cancellation retains the exact worker retirement future and physical lease until reaping and cleanup retry complete.
- Pure invocation transitions never let cancellation erase running/retiring state. Runtime close stops dispatch, retires process owners and joins dispatch; self-close rejects. Converted futures cannot forge physical end.
- Missing API baseline recorded (`run-afcbe969c562`). Focused full runtime host/quality and real public configuration/queued cancellation/request-progress cancellation/failed socket cleanup retention passed (`run-cb0dfbf279cb`). Public fixture uses typed test stubs for real builtins; generated consumer/required readiness remains P3–P5 proof.
- `./gradlew build` passed in 1m36s (`run-747f93371827`); backlog validation passed (`run-b670848185df`). No warning inventory expansion. Enforcer dependency is API because its invocation handle types are now publicly exposed.

## Recursive-review residuals — 2026-11-04

Three **P2 residuals** in the [recursive follow-up](../reviews/2026-11-04-repository-review-recursive.md)
extend this existing physical-retirement/fatal-outcome owner. Existing cancellation,
minimal-stage, admission and exact worker-slot retirement corrections remain valid;
the high-priority owner is neither closed nor replaced.

- **[PRT-10](../reviews/2026-11-04-repository-review-recursive.md#prt-10):**
  `PortalInvocationRetirement.kt:75–82` stores the first failure only; ordinary then
  fatal retention loses the fatal. `OwnedPortalInvocation.kt:79–104` samples retained
  failure only after successful mapping, so a caught retained fatal followed by an
  ordinary mapping throw loses it from result and end too. `ProcessBroker.kt:314–345`
  supplies the production retain calls. Both gaps were reproduced against existing
  host-owner bytecode with completed pure futures; no fresh source/native build.
  Merge retained observations fatal-first and include them on exceptional mapping
  exit. Assert exact Error identity for both orders, successful-return single-fatal
  control, pending physical retirement/admission and cancellation-result/fatal-end.
- **[PRT-11](../reviews/2026-11-04-repository-review-recursive.md#prt-11):**
  `PortalChannel.kt:119–123` transfers received reply capabilities to the broker.
  A pre-native close failure leaves the exact capability in broker retention
  (`ProcessBroker.kt:495–506`), while the slot future checks only its seven own
  resources (`:830–859`). Invocation end/admission can finish before that received
  capability retires. This is a source-confirmed cleanup-scope mismatch; broker
  custody remains, so no orphan/leak is established. Resolve the shared
  `InvocationHandle.kt:8–9` cleanup contract: join the exact transferred owner to
  invocation retirement, or explicitly specify/qualify a separate required owner.
  Use a genuinely acquired received descriptor with one pre-native close failure,
  successful slot retirement, held capability retry and terminal-native-close control.
- **[PRT-12](../reviews/2026-11-04-repository-review-recursive.md#prt-12):**
  Ordinary RPC failure retains a slot future. Maintenance cleanup Error leaves it
  pending and is logged (`ProcessBroker.kt:738–755,767–838`); only resource flags
  survive. Successful later close uses a fresh collector and completes Unit
  (`:834–835`), erasing the earlier fatal from execution end. Retain cumulative
  fatal-first slot cleanup evidence across retries and publish it only after
  physical retirement. Inject one-shot pre-native Error: end stays pending before
  retry, then exposes that exact Error plus original ordinary context. Preserve
  terminal native-close semantics and once-only physical disposal.

PRT-11/12's acquired-resource and asynchronous schedules were not executed. Extend
the existing owning fault fixtures and run affected runtime/native/full gates when
implementing. Coordinate broker edits with PRT-02/04, CG05 and P08; retain their
separate checkout, close-budget, replacement and capability contracts. None of
these cases claims ordinary result/cancellation completion is currently treated as
worker death, or reopens resolved fatal-checkout/channel corrections.

---
<!-- id: issue-20261007-211004-888-own-prepared-portal-invocation-cancellation-throug  file: issue-20261007-211004-888-own-prepared-portal-invocation-cancellation-throug.md -->
