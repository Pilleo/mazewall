---
title: "Operator Observability: Structured Diagnostics Events Beyond JUL Strings"
severity: "LOW"
status: "open"
priority: low
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManager.kt"
  - "enforcer/src/main/kotlin/io/mazewall/Platform.kt"
effort: "medium"
autonomy: "supervised"
open_questions: true
dependencies: []
---

# 🟡 [Severity: LOW]: Operator Observability — Structured Diagnostics Events

**Context:** All operational signaling today is `java.util.logging` strings plus the one-shot
`Platform.diagnose()` text dump. The most safety-relevant lifecycle events are effectively invisible
to operators running services: supervisor daemon unexpected exit currently ends in
`Runtime.halt(1)` (fail-closed, correct) with SEVERE logs — but there is no programmatic hook to
alert, dump state, or attempt orderly shutdown first. Similarly, fallback bypasses
(WARN_AND_BYPASS), Landlock application, CET arm/lock outcomes, and self-verification results (once
issue-20260823-172003 lands) are log-only.

**Needed:**
1. Introduce a tiny event SPI: `MazewallEvents { fun onEvent(event: DiagnosticEvent) }` with a
   default no-op; events are sealed data objects (DaemonExited(exitCode, lastLogLines),
   FallbackEngaged(behavior, reason), LandlockApplied(scope, abiVersion), CetOutcome,
   SelfVerificationResult) — no dependency on any metrics library.
2. Emit at existing decision points (the `onUnexpectedExit` hook already exists — generalize it).
3. JUL adapters remain the default sink so current behavior is unchanged.
4. Document a recipe: wiring events into Micrometer/OTel in the presentation docs.

## ❓ Open Questions
1. Should daemon-exited allow a non-halt policy (e.g. graceful drain then halt) via the SPI, given
   stranded USER_NOTIF waiters cannot be resumed? Default must remain fail-closed halt.
