---
title: "Observe cancellation inside admitted Portal native polling"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalIo.kt"
target_symbols: 
  - "PortalIo"
open_questions: false
---

# 🟡 [Severity: HIGH]: Observe cancellation inside admitted Portal native polling

**Context:**
Fresh full native gate run-4b5b434f4157 exposed P2 cancellation leaving a sleeping worker alive. Portal admission checks interruption, but admitted native poll loops only observe channel close/deadline; focused tests had cancelled before reply-lock admission and missed this race.

**Needed:**
1. Signal an actual reply poll timeout in the native regression, then check interruption before and after native readiness polling while retaining process reaping and cleanup ownership.

**Evidence (pending repository review):**
- Strengthened real native test waits for a successful zero-result reply poll before cancellation, preventing accidental cancellation before lock admission. The original polling behavior then failed deterministically (`run-a85c9d96a14e`), leaving the child alive.
- PortalIo now observes interruption before polling and after successful readiness, preserves the interruption, and routes through existing terminal-frame/process retirement ownership. Permission errors and deadlines retain their original handling.
- Focused host/native/quality passed (`run-1be6bd26cfef`), full fresh host build passed (`run-87b7f35d556f`), and fresh mapped Docker full gate passed (`run-a7557f9c33a8`). Existing five-second assertions and cleanup-retention checks were not weakened.

---
<!-- id: issue-20261007-223139-618-observe-cancellation-inside-admitted-portal-native  file: issue-20261007-223139-618-observe-cancellation-inside-admitted-portal-native.md -->
