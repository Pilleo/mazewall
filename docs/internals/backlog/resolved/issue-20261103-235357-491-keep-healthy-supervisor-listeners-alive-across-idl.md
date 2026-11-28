---
title: "Keep healthy supervisor listeners alive across idle request intervals"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorValidationChannel.kt"
target_symbols:
  - "SupervisorValidationChannel"
open_questions: false
---

# 🟡 [Severity: HIGH]: Keep healthy supervisor listeners alive across idle request intervals

**Context:**
Repository-wide review, 2026-11-04: [SUP-01](../../reviews/2026-11-04-repository-review-findings.md#sup-01). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · reproduced · high confidence · existing supervised installations.**

**Trigger:** complete a valid handshake, then issue no supervised syscall for the configured installation timeout (30 seconds by default). The same failure occurs during a sufficiently long idle gap between requests.

**Connected evidence:**

- `enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorValidationChannel.kt:78–96,164–199,220–224` starts an installation-timeout deadline for every input read and throws on expiry, including a read waiting for the first byte of the next request.
- `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorInstaller.kt:223–249,264–269` immediately waits for the next request after the ACK and after each completed request. An idle timeout closes the runtime/channel and invokes the unexpected-termination callback.
- The callback is wired to the installation owner at `SupervisorInstaller.kt:129–136`; `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorInstallationLifetime.kt:17–31` records failure and rejects reuse. The already installed filter is permanent.

**Consequence:** ordinary inactivity destroys a healthy supervisor installation. Later task acquisition fails, and the permanently filtered worker cannot simply resume normal supervised service. This is independent of a stuck notification: there need not be any pending kernel work.

**Actual reproduction:** freshly compiled current channel source, with all I/O mocked, successfully read `0xAC`, then attempted the next request ID. With a 20 ms configured budget and an injected timeout poll it printed:

```text
successful handshake; now idle before any notification
idle read failed: supervisor validation readiness timed out; polls=2
```

**Smallest correction:** distinguish idle pre-frame waiting from handshake/in-progress-frame deadlines. Idle polling should keep checking stop/interruption without failing the installation; incomplete frames and notification work must still retain bounded absolute deadlines. Do not remove the reactor's pending-notification watchdog.

**Regression:** handshake, several idle poll expiries, then a valid request and successful response on the same installation; repeat after a completed request. Separately assert prompt owner stop, failed partial-frame handling and unchanged notification expiry. Existing `SupervisorValidationChannelTest.kt:60–112` exercises stalled reads/EINTR but not post-handshake idle health.

**Mapping/dependency:** **NEW specific defect** after searching active/resolved backlog. Related historical `docs/internals/backlog/resolved/issue-148-asynchronous-supervisor-socket-reads-timeout-failure-handlin.md` concerns a pending JVM verdict, not idle listener lifetime; its suggestion to wait indefinitely for pending authorization is superseded by the current liveness contract. Also expands the listener-health coverage recorded in `resolved/issue-20260910-225752-609-record-fail-closed-supervisor-routing-and-installa.md` under the same backlog directory. Fix independently before Plan B6/B7 liveness qualification.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

**Resolution**

JDK 25 focused channel and decoder tests, Enforcer ktlint, and the Detekt warning ratchet passed. Regression fixtures observed idle polling beyond the request budget, two subsequent valid requests, a bounded partial frame, and explicit owner retirement.

---
<!-- id: issue-20261103-235357-491-keep-healthy-supervisor-listeners-alive-across-idl  file: issue-20261103-235357-491-keep-healthy-supervisor-listeners-alive-across-idl.md -->
