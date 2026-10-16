---
title: "Investigate AO SIGQUIT during monitored native verification and preserve no-replay recovery"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/triage/DiagnosticTriageRunner.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/triage/DiagnosticTriageRunnerTest.kt"
  - "docs/superpowers/plans/2026-10-09-opencode-v1-zed-continuation.md"
target_symbols: ["DiagnosticTriageRunner", "captureThreadDump"]
open_questions: false
---

# 🟡 [Severity: HIGH]: Investigate AO SIGQUIT during monitored native verification and preserve no-replay recovery

**Context:**
Pinned AO v0.13.5 daemon received SIGQUIT with sigcode0 during the full scanner commit hook. Daemon exited; the owned commit still completed successfully as e3bbfa7b with unchanged full hook BUILD SUCCESSFUL in8m47s. Restart/resume recovered the same conversation, marked interrupted waiting turn failed, and report-only finalization preserved work without replay. The sender was initially unproven; owned signal tracing subsequently identified jcmd and the JVM-selection false positive detailed below.

**Needed:**
1. Correct diagnostic JVM selection/explicit target ownership and replace the host-wide test side effect with a focused safe regression. Preserve bounded diagnostic capture and no-replay recovery; verify the isolated production AO deployment through the full gate.

**Root cause confirmed during continuation:**
- Owned signal-only tracing captured `SIGQUIT`, `SI_USER`, sender PID3446824.
  Sender was `jcmd`, cwd in the profiler module of the correction worktree.
- `DiagnosticTriageRunner.captureThreadDump()` filters all host processes using
  `command.contains("java") || command.contains("gradle")`, then invokes
  `jcmd <pid> Thread.print`. AO's extracted executable path was inside
  `/home/leanid/Documents/code/java/jseccomp/`, producing a false JVM match.
- `DiagnosticTriageRunnerTest` invokes that real host diagnostic collector.
  `jcmd` attach can deliver SIGQUIT to a non-JVM target, killing Go AO.
- Operational correction: deploy the identical checksum-verified AO executable
  to `~/.local/state/mazewall-v1-ao/bin/ao` as a regular file, not a symlink back
  into the `code/java` path. Preserve native test selection/assertions and keep
  profiler source changes out of the scanner diff.
- Follow-up source repair should classify actual executable basenames/JVM
  identity and use explicit diagnostic target ownership. Add a focused fake-JVM
  path regression; the current test should not indiscriminately attach to host
  processes merely to verify JSON keys. This remains a separate scoped issue.

---
<!-- id: issue-20261008-232414-071-investigate-ao-sigquit-during-monitored-native-ver  file: issue-20261008-232414-071-investigate-ao-sigquit-during-monitored-native-ver.md -->
