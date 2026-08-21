---
title: "ContainedExecutorWrapper must not rewind ThreadLocal after a successful install"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapperStateTest.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: ContainedExecutorWrapper must not rewind ThreadLocal after a successful install

**Context:** `ContainedExecutorWrapper` saves `ContainmentStateRegistry.threadState` and restores it only when `receipt == null` (install never happened). After Landlock or seccomp apply, JVM tracking must remain: the kernel filter cannot be uninstalled. Restoring after success is a desync; the next task sees an empty registry, installs again, and stacks until Landlock `E2BIG` or 32 seccomp filters. See WONTFIX issue-102 and issue-103.

Existing `ContainedExecutorWrapperTest` is `@EnabledIfLinuxAndSupported` and does not assert registry depth. This issue adds a **new host unit test class** using `MockNativeEngine` and `Platform.setProvider`, copying the setup style of `FilterInstallationFailureTest`.

**Needed:**
1. Create `enforcer/src/test/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapperStateTest.kt` only.
2. `@AfterEach`: `LinuxNative.resetToDefault()`, `Platform.resetToDefault()`, `ContainmentStateRegistry.threadState = ContainerState()` (and process state if you touch it).
3. Do not edit production `ContainedExecutorWrapper` unless a test proves a real bug (expected: production already restores only when `receipt == null`).
4. Platform threads only. `Thread.ofVirtual` is banned in production ArchUnit and must not appear here.

**New cases:**
1. **Successful two tasks, same policy, same worker.** `Executors.newSingleThreadExecutor()`, wrap with `Policy.builder().block(Syscall.EXECVE).build().definition`. Submit two no-op runnables via `submit` + `future.get()`. After both complete: `threadState.filterDepth` after task 2 equals depth after task 1 (typically 1), **not** 2. Capture depth inside each task (the worker thread owns the ThreadLocal) if the test thread would otherwise see a different holder.
2. **Successful install is not rewound.** After one successful task on that worker, the worker's `threadState` is not empty (`filterDepth > 0` and/or `syscallActions` contains `EXECVE`).
3. **Failed install before receipt still rewinds.** Mock the `seccomp` syscall to `SyscallResult.Error(22, -1)` as in `FilterInstallationFailureTest`. Policy **without** Landlock paths so Landlock is not applied. First task throws. Worker's `threadState` equals the pre-task snapshot (empty `ContainerState()`).

**Do not:**
- Add `finally { threadState = initialState }` after a non-null receipt.
- Call `sanitizeThreadState()` from the wrapper.
- Use virtual threads or `kotlinx.coroutines` in this test.

**Verify:** `./gradlew :enforcer:test --tests io.mazewall.enforcer.internal.ContainedExecutorWrapperStateTest`
