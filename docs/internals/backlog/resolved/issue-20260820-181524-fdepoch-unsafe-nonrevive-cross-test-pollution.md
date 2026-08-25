---
title: "FileDescriptor.unsafe non-revival plus process-wide FdEpoch can poison sibling tests"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":platform"
  - ":enforcer"
  - ":profiler"
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt"
  - "platform/src/test/kotlin/io/mazewall/core/FileDescriptorTest.kt"
effort: "medium"
autonomy: "supervised"
---

# 🟡 [Severity: MEDIUM]: FileDescriptor.unsafe non-revival plus process-wide FdEpoch can poison sibling tests

**Context:** `FdEpoch` is a process-wide table. `FileDescriptor.unsafe()` now mints a dead token when `FdEpoch.isRetired(fd)` is true, instead of claiming a new generation. Production code was moved to `unixSocket` / `adopt` / role factories (which still `claimOpen` and can revive). Tests still call `FileDescriptor.unsafe(7)`, `unsafe(10)`, `unsafe(42)` in many classes (`SupervisorValidationChannelTest`, `SupervisorSessionHandlerTest`, `ProfilerTraceListenerTest`, …). `FileDescriptorTest` added `@Isolated` and bumped some integers, which only isolates that class. Under `./gradlew test` (`forkEvery = 0`), class A closing token 10 leaves integer 10 retired; class B wrapping `unsafe(10)` now gets a non-live `FdState.Open` token and NativeEngine I/O returns `EBADF` instead of the previous revived generation.

**Needed:**
1. Prefer `generic` / `unixSocket` / `adopt` in tests that represent live kernel fds (same contract as production).
2. Or reset `FdEpoch` between tests (package-visible test hook) so retired integers do not leak across classes in one JVM.
3. Keep `unsafe()` non-revival tests in an `@Isolated` class (already started in `FileDescriptorTest`).
