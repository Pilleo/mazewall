---
title: "DiagnosticTriageRunner.captureThreadDump Blocks Indefinitely on Busy JVM Targets"
severity: "MEDIUM"
status: "resolved"
priority: high
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/triage/DiagnosticTriageRunner.kt"
effort: "small"
autonomy: "autonomous"
open_questions: false
dependencies: []
---

# 🟠 [Severity: MEDIUM]: DiagnosticTriageRunner.captureThreadDump Blocks Indefinitely on Busy JVM Targets

**Context:** `DiagnosticTriageRunnerTest.test triage runner creates report file` fails with a
2-minute timeout on this machine **on a clean baseline** (reproduced via stash of all local changes).
Root cause: `captureThreadDump()` enumerates *every* running `java`/`gradle` process and executes
`jcmd <pid> Thread.print`, then calls `process.inputStream.bufferedReader().use { it.readText() }`.
When the target is the active Gradle daemon (busy executing this very build) or any VM whose attach
mechanism stalls, `readText()` blocks forever — there is no `waitFor` timeout, no `destroyForcibly`,
and no bound on how many targets are probed. `captureDmesg()` has the same unbounded-read shape.

**Needed:**
1. Bound the per-target capture: submit the stream read to a helper executor with a finite
   `future.get(timeout)`; on timeout `future.cancel(true)` + `process.destroyForcibly()` and record
   `"timed out"` for that PID.
2. Prefer excluding the current process's own Gradle ancestor chain from probing when identifiable,
   but keep the timeout as the hard guarantee (fail-open to a diagnostic string, never fail the
   report generation).
3. Apply the same bounded pattern to `captureDmesg()`.
4. Verification: `./gradlew :profiler:test --tests '*DiagnosticTriageRunnerTest*'` must pass within
   its assertion timeout even while a Gradle build is running concurrently.

