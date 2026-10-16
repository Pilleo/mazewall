---
title: "Export bounded terminal Tier E event JSONL with raw capture identities and stack evidence"
severity: "HIGH"
status: "open"
priority: high
dependencies: ["issue-20260929-075557-179-add-opt-in-tier-e-all-target-syscall-capture-and-i"]
component: "core"
target_modules: ["profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEKotlinDaemon.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/InvocationReplayMachine.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEProfilerSession.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/AttributionCollector.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEEventJsonl.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEEventJsonlFileSink.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierETerminalReportFileSink.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierEEventJsonlFileSinkTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/TierEEventJsonlTest.kt"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEEndToEndIntegrationTest.kt"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEAllTargetDaemonIntegrationTest.kt"
  - "docs/internals/designs/profiler/tier-e-design.md"
  - "docs/internals/designs/profiler/tier-e-split-privilege-deployment.md"
target_symbols:
  - "TierEDaemonService"
open_questions: false
---

# 🟡 [Severity: HIGH]: Export bounded terminal Tier E event JSONL with raw capture identities and stack evidence

**Context:**
Kubescape PoC importer needs a completed per-observation file with retained repetitions, timestamps, raw arguments, Java frames or unavailable reasons, independent of attribution completeness.

**Needed:**
1. Add failing tests showing raw kernel timestamp, host TGID/TID, six unsigned raw arguments, flags, session/ordinal, invocation/execution status and Java frame identity survive the existing retained observation and terminal resolution paths, including repetition and unattributed rows.
2. Implement the versioned header/event/terminal JSONL schema and bounded no-follow incremental temporary writer; only an atomic, fully reconciled publication can be marked export-complete. On exhaustion or write/rename failure retain an explicit incomplete terminal and never claim a truncated success.
3. Wire the existing terminal-result seam for registered target sessions in opt-in mode. Export individually captured unknown/unresolved observations regardless of attribution coverage; separate transport completeness, attribution completeness, export completeness and producer-closure proof. Keep the legacy terminal report and `policy_eligibility=false`.
4. Run a short source-bound, multi-threaded JVMTI-agent JVM fixture through the existing split-privilege helper, requiring two distinct managed methods for one syscall, repeated raw events, unattributed entries, zero accepted transport loss and an independently verifiable file for the node-agent handoff.

---
<!-- id: issue-20260929-075625-151-export-bounded-terminal-tier-e-event-jsonl-with-ra  file: issue-20260929-075625-151-export-bounded-terminal-tier-e-event-jsonl-with-ra.md -->
