---
title: "Prevent stateful Portal worker recreation after ambiguous loss"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
target_symbols: 
  - "ProcessBroker"
open_questions: false
---

# 🟡 [Severity: HIGH]: Prevent stateful Portal worker recreation after ambiguous loss

**Context:**
Approved P1 terminal state loss forbids silent stateful worker replacement. The application gate rejects subsequent calls, but broker maintenance still constructs a new guest after failed RPC.

**Needed:**
1. Forward immutable instance mode to broker and terminally close stateful admission before retiring the lost worker; preserve stateless replacement.

**Implementation evidence (pending repository review):**
- Runtime forwards captured manifest instance mode; stateful brokers require exactly one worker. Worker-loss transition publishes Failed before maintenance retirement, so replacement cannot construct another guest. Failed ownership still permits close/retry.
- Pure lifecycle matrix includes worker loss and rejects late publication/restart after loss. Real worker timeout verifies unknown completion, child reaping, no later admission, and exactly one constructed worker after joining maintenance.
- Missing event/API baseline recorded (`run-10d02e5e8ff0`); removing terminal admission reproduced the real-process regression (`run-4506aa6dde05`). Restored focused host/real-process/quality checks passed (`run-7355e5bafdc9`).
- `./gradlew build` passed in 2m32s (`run-4f0e0fd8d0f3`), including existing stateless crash/timeout replacement checks. Backlog validation passed (`run-fa3950cea314`).

---
<!-- id: issue-20261007-205309-190-prevent-stateful-portal-worker-recreation-after-am  file: issue-20261007-205309-190-prevent-stateful-portal-worker-recreation-after-am.md -->
