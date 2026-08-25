---
title: Implement Atomic Write-Ahead Pattern for Orchestrator State File Persistence
severity: HIGH
status: open
priority: high
dependencies: []
component: orchestrator
target_modules:
- :tools:orchestrator
target_files:
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt
effort: small
autonomy: autonomous
paperclip_issue_id: 9dfca5fb-adf3-414d-932c-7b4a821c81ba
---

# 🔴 [Severity: HIGH]: Implement Atomic Write-Ahead Pattern for Orchestrator State File Persistence

**Context:**
The Autonomous Backlog Orchestrator maintains running state (e.g. active slots, tasks, SHAs, session IDs, PR numbers, retries) across executions in a properties file `.orchestrator_state.properties`.
Currently, the state is persisted inside `OrchestratorDaemonRunner.saveState` by directly opening the file output stream and writing to it:
```kotlin
fun saveState() {
    val props = java.util.Properties()
    context.save(props)
    stateFile.outputStream().use { props.store(it, "Orchestrator state") }
}
```
If the background daemon is killed, the machine restarts, or a write failure occurs midway through the stream write, the properties file can become corrupted or truncated. This leads to silent context and state loss for all active concurrent execution slots, making recovery impossible.

**Needed:**
Implement a safe, atomic file persistence pattern ("Write-Ahead/Write-Rename") for saving the orchestrator state:
1. Write the state properties to a temporary sibling file (e.g. `.orchestrator_state.properties.tmp`).
2. Flush and close the stream to ensure all data is written to disk.
3. Perform an atomic rename of the temporary file to the final destination `.orchestrator_state.properties` using NIO `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)`.
4. Gracefully fall back to standard `REPLACE_EXISTING` replacement if the underlying OS filesystem does not support `ATOMIC_MOVE`.
