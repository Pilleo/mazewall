---
title: "Wait for the event consumer before certifying the drain"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt"
effort: "medium"
autonomy: "supervised"
related_pr: 512
related_thread: PRRT_kwDOScnnEM6a9Pyp
---

# 🔴 [Severity: HIGH]: Wait for the event consumer before certifying the drain

**Review (2026-08-21):** Still present.

**Current tree:** On `EOFException`, if `gracefulDrainRequested`, the socket reader sets `drainComplete = true` and breaks. `eventQueue.close()` runs in `finally`. `passThrough()` / close join the collector for `JOIN_TIMEOUT_MS = 5000` then **interrupt** it if still alive. Coverage uses `drainComplete && droppedEvents == 0` as a completeness input. A timed-out collector can abandon queued events while the session still reports a complete drain.

**Do not:**
- Increase the join timeout and call it fixed.
- Set `drainComplete=true` on EOF regardless of queue/consumer state.
- Interrupt the collector and still certify `droppedEvents=0`.

**Do:**
1. `drainComplete` is true only after the consumer has taken every queued event (or the queue is empty after close).
2. If the collector is interrupted or times out, count remaining/abandoned events as **drops** (`droppedEvents += leftover`) and leave `drainComplete=false`.
3. Fail closed: incomplete coverage must prevent `toPolicy()` without `allowIncomplete`.

**Tests:** Offer events into the queue, delay the consumer past join timeout (or never consume), then `passThrough()`. Assert `drainComplete=false` and/or `droppedEvents > 0`. Opposite: consumer drains fully before join returns → `drainComplete=true`, drops 0.

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819861578
