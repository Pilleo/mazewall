---
title: Arena MemorySegment leak in JVMValidationListener during async response loop
type: issue
status: resolved
priority: high
labels:
- security
- enforcer
- ffm
- memory-leak
component: enforcer
target_modules: [":enforcer"]
target_files: ["enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"]
github_issue: 335
paperclip_issue_id: 652de2fe-165a-48b6-8070-1ffa12aad3de
---

# Issue: Arena MemorySegment leak during async response loop

## Context
In `SupervisorSessionHandler.kt`, `readAndHandleJvmResponse` allocates `responseBuf` within a loop without an explicit localized Arena scope.

```kotlin
val responseBuf = arena.allocate(Layouts.SUPERVISOR_RESPONSE_SIZE)
var readRes: LinuxNative.SyscallResult<Long, *>
while (true) {
    readRes = engine.memory.read(socketFd, responseBuf, Layouts.SUPERVISOR_RESPONSE_SIZE)
    if (readRes is LinuxNative.SyscallResult.Error && readRes.errno == NativeConstants.EINTR) {
```

## The Bug
The `arena` passed via context receiver `context(arena: NativeArena)` is scoped to the `handleActiveListener` or outer event loop. If `SUPERVISOR_RESPONSE_SIZE` is allocated *inside* or around the `while` loop (or repeatedly across multiple notifications), the long-lived `NativeArena` keeps growing until the session is closed. This causes a slow native memory leak for every trapped system call.

## Security / Stability Impact
- **Denial of Service (OOM)**: Long running JVMs generating thousands of seccomp notifications will exhaust native memory causing the process to crash with an OOM.

## Recommendation
Native memory allocated for reading responses must be cleared or reused, or the `Arena` used for processing a single notification must be a child arena (e.g. `NativeArena.ofConfined()`) that is explicitly closed at the end of the `processNotification` function.
