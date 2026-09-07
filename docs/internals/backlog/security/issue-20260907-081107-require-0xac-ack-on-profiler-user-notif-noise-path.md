---
title: "Require 0xAC ACK on profiler USER_NOTIF noise path"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionHandler.kt"
target_symbols:
  - "ProfilerSessionHandler"
needs_kernel: true
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "8196b7bc-34ea-42a6-a294-2d8d30aad84e"
paperclip_identifier: "MAZ-1188"
---

# 🔴 [Severity: HIGH]: Require 0xAC ACK on profiler USER_NOTIF noise path

**Context:**
Profiler `processNotification` is a suppressed cyclomatic mix of parse, path, socket, handshake, and CONTINUE/ERROR. The noise-path helper replies CONTINUE without the `0xAC` ACK loop required for every USER_NOTIF path. `ProfilerArchitectureTest` checks `performHandshake` before `sendSeccompContinue` on `processNotification*`, but a helper that CONTINUE-skips handshake is an accepted exception unless the test names it. Deadlock / lost-wakeup risk if the traced JVM is waiting for ACK.

**Progress:**
- The noise path does not publish a JVM trace event, so waiting for 0xAC ACK deadlocks the session (cli-demo `DemoAppTest`). CONTINUE without parent ACK is required when no event was sent.

**Needed:**
1. Document and ArchUnit-allow the no-event CONTINUE path; do not wait for 0xAC when the JVM listener was not notified.
2. Extend `ProfilerArchitectureTest` (or a unit test of the helper) so a CONTINUE without handshake is a failure unless allowlisted with a comment citing profiler/AGENTS.md.
3. Run `:profiler:test` and kernel profiler tests if the ACK path is native.

## Side effects
- Noise-path CONTINUE without handshake ACK is removed; every USER_NOTIF path must ACK

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-081107  file: issue-20260907-081107-require-0xac-ack-on-profiler-user-notif-noise-path.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
