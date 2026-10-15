---
title: "Preserve truthful profiler completion across terminal effects and native network evidence"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: [":platform", ":profiler", ":demos:vulnerable-web-app"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/process/ChildStartupTransaction.kt"
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompNotifHandler.kt"
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompSessionHandler.kt"
  - "platform/src/test/kotlin/io/mazewall/platform/seccomp/daemon/SeccompSessionHandlerTest.kt"
  - "platform/src/test/kotlin/io/mazewall/core/ChildStartupTransactionTest.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/api"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal"
  - "profiler/src/main/kotlin/io/mazewall/profiler/ffi/ProfilerNativeCommandWriter.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/ProfilerIntegrationTest.kt"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/ProcessWideProfilerIntegrationTest.kt"
  - "demos/vulnerable-web-app/src/test/kotlin/demo/vulnapp/VulnerableWebAppFreshJvmTest.kt"
  - "docs/internals/designs/profiler/profiler-design.md"
  - "docs/internals/designs/core/notification-liveness-contract.md"
  - "docs/internals/designs/api/profiler-public-api.md"
target_symbols: ["ProfilerTraceListener", "ProfilerSession", "ProfilerService", "SyscallEvent", "SeccompSessionHandler"]
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve truthful profiler completion across terminal effects and native network evidence

**Context:**
The operator authorized deep debugging and the safest scoped profiler repair after
CI38770607420 rejected the profile-first demo. Exhaustive parser/notification
reducers did not model producer retirement, concurrent JVM-hook ownership, or
successful collector consumption. USER_NOTIF CONNECT had no destination capture.
Preserve public APIs, exactly-one kernel replies and fail-closed completeness.

**Needed:**
1. Carry an explicit normal terminal record for verified producer exit or requested pass-through. Bare/partial EOF and failure remain incomplete.
2. Keep the daemon owned during bounded retirement of sessions by concurrent application shutdown; close/reap after the budget even on failure.
3. Require successful queue consumption as well as physical reader/collector end; storage failure must never certify evidence.
4. Read bounded Linux socket-address evidence in the existing owned helper, decode pure heap values, retain failures and preserve them through both private frame boundaries into typed observations.
5. Exercise real native retirement, CONNECT, shutdown publication, malformed/partial/permission/loss controls, complete quality gates and connected-code review. Update the state matrices to assert membership of all sealed variants.

## Established regressions and current evidence

- Producer exit before explicit close: deterministic actual native RED
  `run-e19c09014d62`; ordinary immediate-close characterization alone passed and
  did not expose the race. Linux `seccomp_notify_poll` emits HUP when filter
  users reach zero; error/HUP mixtures do not certify completion.
- Explicit terminal parser RED `run-1d09d677547e`; actual native terminal and
  parser/state checks GREEN `run-fff965918752`.
- Concurrent child shutdown killed the daemon before Spring drained sessions.
  Session lifetime ownership plus an explicit complete terminal record fixes all
  six actual collector drains (`run-f77b62897e9e`). A terminal record ends the
  logical stream: post-record ECONNRESET was directly observed when close commands
  crossed (`run-b47752916e04`); EOF/reset alone remains insufficient.
- Failed collector storage previously reported complete despite losing the event:
  RED `run-35737e1a1cfc`; outcome now requires successful consumption. Complete
  parser matrix, reactor terminal and loss-refusal checks pass `run-fab0c6cda42f`.
- CONNECT resolver and real helper/daemon pipeline RED `run-ebe774ae0562`, GREEN
  `run-6e7627e6d634`. Native-family/scope and network-order port/address fields
  checked against installed Linux UAPI (`run-66054dfbd8ad`). No native layout or
  BPF policy changed.
- Real demo now passes complete generation validation, then honestly refuses a
  separate deployment-policy problem: a profiled Tomcat multipart temporary file
  is absent in the fresh enforcement container (`run-2489629c7a3a`). This is not
  evidence of a complete exploitation sweep. Core adversarial checks/full quality
  gate and policy handoff investigation remain pending; do not archive yet.

Impact tooling is PARTIAL/INDEX_MISSING; direct callers and connected source are
the authority for this scoped change. No blanket completeness override, optional
security fallback, dropped check, threshold reduction or benchmark publication.

## Cold runtime closure and connected acceptance

A cold process-wide network regression exposed class loading in the ACK path:
missing parser event/file-facade dependencies caused self-notification and
ClassNotFound/permission failure (`run-8b2ad2b2a4fb`); the next exact missing
diagnostic/ACK companion dependencies were visible in `run-4c18448e25bb`.
Pre-install initialization now covers the complete parser/operand sealed sets,
critical codec/file-facade/diagnostic dependencies and a disposable heap-only
queue's suspended receive/send/retirement path. It publishes no synthetic event.
Actual cold process-wide network GREEN `run-6240c13fc5f4`; the older background
read test now uses bounded owned subprocess cleanup and requires actual successful
I/O instead of accepting a permission failure (`run-3bd9c7ab154a`).

The full profiler host suite passed after diagnostics/architecture corrections
(`run-f21b73476413`), and the connected packaged five-row standard demo passes
with complete generation, protected startup, exact controls and verified retirement
(`run-c5e6ca7b42bf`). HTTP-origin refusals remain inferred, SQL disclosure remains
an application-level limit, and thread/shared-FD limitations are documented.
Full build, mutation gates and final connected review still pending.

The connected lifecycle review also reproduced interrupted close reporting
Complete while its native-style reader was still physically alive
(`run-5652f6b54c6a`). Socket and thread cleanup are now separate: live references
stay owned and typed incomplete, retry joins them after release, and the consumed
descriptor closes only once. Cleanup retry does not invent stream completeness.
Listener/service/fatal cleanup regressions pass `run-e3b112667533`.

## Resolution checkpoint

Full included-module `./gradlew build` passes (`run-bbf486925536`,452290ms)
after the warning and native/context fixture failures were established and fixed.
The old in-class process-wide sentinel test is now an owned child: otherwise a
successful TSYNC permanently contaminated later test workers and correctly caused
NEW_LISTENER EBUSY (`run-5e11b7b2d44a`). All fifteen native profiler cases pass
together (`run-865af21e6571`), with no permission-failure acceptance.

All three unchanged mutation gates pass (`run-c0f65b467853`): enforcer reused its
matching current inputs (94% line/74% mutation/80% strength), platform 98/65/98,
profiler 98/89/89. The mutation target scope is unchanged; these are not coverage
claims for every new native branch. The new behavior has its separate deterministic
negative controls and actual native/cold/packaged acceptance above.

One connected review covered terminal framing, reply order, native ownership,
join/cancellation/fatal paths, bounded reads/decoding, immutable evidence binding,
pre-install class closure and consumer authority. No further verified scoped
defect remains. No binding/layout/BPF/virtual-thread safety invariant changed;
symbol-index evidence remains incomplete. The independent executable-mapping
EPERM issue120935-494 remains open. Exact-head remote CI still gates the PR merge.

---
<!-- id: issue-20261015-172121-857-preserve-truthful-profiler-completion-across-termi  file: issue-20261015-172121-857-preserve-truthful-profiler-completion-across-termi.md -->
