---
title: "Make Tier E shutdown terminal across service and control state"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEKotlinDaemon.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/ControlSessionMachine.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/ControlSessionMachineTest.kt"
target_symbols:
  - "TierEDaemonService"
  - "ControlSessionMachine"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make Tier E shutdown terminal across service and control state

**Context:**
Direct shutdown sets stopRequested but does not transition sessionState; a queued command can execute after shutdown. The reducer also lets AttachSucceeded or AttachFailed leave Terminated.

**Needed:**
1. Add deterministic tests for direct shutdown followed by ATTACH and for completion events delivered to terminal control state.
2. Establish one terminal admission decision under the service lock for both protocol SHUTDOWN and direct shutdown. Publish stopping/terminal admission before fallible cleanup and retain cleanup ownership separately.
3. Permit attach completion only for a matching active attach operation; terminal state must be absorbing for all events. Unexpected or late completion must not recreate an active state or authorize new effects.
4. Keep shutdown idempotent and preserve externally compatible command replies.

## Evidence and impact

Static review on 2026-09-26; no runtime reproduction yet:

- `TierEKotlinDaemon.kt:402–406`: direct `shutdown()` retires the session and sets `stopRequested`, but never sets `sessionState` to terminal. `executeCommand` at lines 218–237 does not gate admission on the stop flag.
- `TierEClientSession.kt:53–62` (also the TCP overload): the stop check precedes frame reading; a command already read or waiting for the service lock can still reach execution after direct shutdown.
- A shutdown-hook/client interleaving can therefore start another attachment after direct service shutdown. This differs from protocol SHUTDOWN, which does enter `Terminated` first.
- `ControlSessionMachine.kt:82–96`: `AttachSucceeded` always transitions to Attached, including from Terminated; `AttachFailed` always transitions to Idle. The normal interpreter serializes attach execution, so this reducer defect alone is not evidence of a remotely reachable asynchronous-completion exploit today.
- Existing `ControlSessionMachineTest` covers command rejection after shutdown but not terminal completion events or the direct shutdown entry point.

## Acceptance criteria and validation

- After direct shutdown, an ATTACH command is rejected without invoking engine creation or attachment, even when it was queued before shutdown.
- Use latches/barriers to exercise queued command admission; no timing sleeps are needed.
- Enumerate terminal-state event transitions: command, syntax error, attach success, and attach failure all preserve terminality and emit no attachment effect.
- Non-Attaching completion cannot silently create or discard a session; correlate completion to the pending operation if needed.
- Cleanup failure leaves admission closed while preserving retryable ownership, with original failure evidence intact.
- Run focused daemon/control tests and protocol golden fixtures, then `:profiler:test` and the merge gate. Declare additional service-test files before implementation.

## Related work

Coordinate with [Tier E cleanup ownership](issue-20260926-121446-405-preserve-tier-e-resource-ownership-until-cleanup-c.md), which fixes loss of owners rather than post-shutdown admission. This tightens the invariant introduced by [control machine extraction](resolved/issue-20260912-224640-816-extract-controlsessionmachine-pure-state-machine-f.md). Read the profiler design and notification-liveness contract before implementation.

---
<!-- id: issue-20260926-122439-817-make-tier-e-shutdown-terminal-across-service-and-c  file: issue-20260926-122439-817-make-tier-e-shutdown-terminal-across-service-and-c.md -->
