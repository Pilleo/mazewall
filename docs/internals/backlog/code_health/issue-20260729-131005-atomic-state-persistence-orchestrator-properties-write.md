---
title: "Atomic Properties State File Writing in Orchestrator to Prevent Corruption"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt"
effort: "small"
autonomy: "autonomous"
paperclip_issue_id: 6ca67a45-5096-4936-a0e3-7cdb227224fe
---

# 🟠 [Severity: MEDIUM]: Atomic Properties State File Writing in Orchestrator to Prevent Corruption

**Context:**
The orchestrator preserves its state machine and task slots across restarts by storing key-value properties inside `.orchestrator_state.properties` on disk. Saving is performed in `OrchestratorDaemonRunner.saveState()`:
```kotlin
fun saveState() {
    val props = java.util.Properties()
    context.save(props)
    stateFile.outputStream().use { props.store(it, "Orchestrator state") }
}
```

**The Problem:**
Opening a direct `outputStream()` to `stateFile` truncates the existing file on disk immediately. If the runner process crashes, gets killed (e.g., via Out-Of-Memory killer), or encounters a power/system disruption while `props.store()` is writing bytes, the properties file is left empty, corrupted, or truncated.
When the daemon restarts, `loadState()` fails or loads an empty context, causing the orchestrator to forget all active slots, leading to parallel task collisions, duplicated GitHub issues/PR creations, and state-machine desynchronization.

**Needed:**
1. Implement atomic file writing for the Orchestrator state file.
2. In `saveState()`, write properties to a temporary sibling file (e.g., `.orchestrator_state.properties.tmp`).
3. Once fully written and closed, use atomic filesystem operations (such as `java.nio.file.Files.move` with `REPLACE_EXISTING` and `ATOMIC_MOVE`) to safely and instantly overwrite the target `.orchestrator_state.properties`.
4. Add a robust fallback in case of OS-level `ATOMIC_MOVE` support failures, falling back to a clean standard replace.
5. Add a unit test in `OrchestratorDaemonRunnerTest` to verify that concurrent file operations or mock interruptions do not result in a corrupted or partially-written properties file.
