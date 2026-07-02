# Guidelines for AI Coding Agents in mazewall-enforcer

Welcome, AI Agent. This is the **`:enforcer`** subproject of **mazewall**. It contains the core, production-grade security containment and enforcement code.

Because this library operates directly at the kernel-user space boundary and manipulates JVM OS threads, mistakes can cause fatal VM deadlocks or silent security bypasses. You MUST adhere to these strict limits, rules, and guidelines when modifying files in this subproject.

---

## 🚧 Core Invariants & Boundaries

### 1. Never Block JVM Coordination System Calls
If a seccomp policy blocks syscalls required for thread scheduling, signal routing, or memory management, the JVM will permanently freeze at the next safepoint or GC cycle.
**Prohibited from blocking:**
- `futex` — thread synchronization.
- `sched_yield` — lock contention.
- `rt_sigreturn` / `rt_sigaction` — signals and HotSpot error/exit routing.
- `close` — file descriptor management.
- `gettid` — thread identification.
- `mmap` — blocks only `mmap(PROT_EXEC)` via argument inspection.
- `mprotect` — blocks only `mprotect(PROT_EXEC)` via argument inspection.
- `clone` **with `CLONE_THREAD` flag** — JVM thread creation. **Blocking the clone syscall directly deadlocks the JVM during thread creation.**
- `prctl` — Thread naming and controls. Whitelists safe operations via argument inspection.

### 2. Protect Against Loom Carrier Poisoning
Seccomp filters bind permanently to the OS thread (LWP). Installs from virtual threads contaminate the carrier thread, poisoning all future virtual threads scheduled on it.
- **Rule:** Any new installation entrypoint must assert `!Thread.currentThread().isVirtual` and throw an `IllegalStateException` on failure.
- **Virtual Threads + Seccomp Pattern:** To safely run virtual threads on seccomp-restricted carrier threads, pre-restrict carrier threads before mounting virtual threads:
  ```kotlin
  val carriers = Executors.newFixedThreadPool(4)
  val latch = CountDownLatch(4)
  repeat(4) {
      carriers.submit {
          ContainedExecutors.installOnCurrentThread(Policy.NO_EXEC)
          latch.countDown()
      }
  }
  latch.await()
  val vtFactory = Thread.ofVirtual().scheduler(carriers).factory()
  val pool = Executors.newThreadPerTaskExecutor(vtFactory)
  ```

### 3. Landlock-Seccomp Ordering Invariant
- **Rule:** Landlock's configuration system calls (`landlock_create_ruleset`, `landlock_add_rule`, `landlock_restrict_self`) are blocked by Seccomp policies. Therefore, **Landlock must always be initialized first before Seccomp is installed**.
- The `applyContainment()` method in `ContainedExecutors.ContainedExecutorWrapper` enforces this correct order. **Do not change this sequence.**

### 4. Fail Closed by Default
Ensure compliance with the global fallback policies defined in [Root AGENTS.md](file:///home/leanid/Documents/code/java/jseccomp/AGENTS.md#2-strict-protection-against-unsafe-fallback--bypass-scenarios).

### 5. BPF Compiler & Argument Safety
- **Multi-Instruction Argument Inspection:** When modifying `BpfFilter.kt`, preserve the multi-instruction argument-inspection sequences for `mmap`/`mprotect`, `clone`, and `prctl`. Do not replace them with simple `BPF_JEQ` checks against the syscall numbers; doing so deletes crucial protection context.
- **Sock Filter Field Layouts:** Use `ValueLayout.JAVA_INT` (4 bytes) for 32-bit `sock_filter` fields (`code`, `jt`, `jf`, `k`). Specifying a `JAVA_LONG` corrupts BPF filter streams silently.
- **Mutex Flags:** `SECCOMP_FILTER_FLAG_NEW_LISTENER` (used by profiler) and `SECCOMP_FILTER_FLAG_TSYNC` (used by enforcer) are mutually exclusive. Never combine them.

### 6. FFM API Patterns & Safety
- **Minimum JDK:** 22 (FFM API finalization). Target Java 25 idioms where applicable.
- **Off-heap Memory:** Use `Arena.ofConfined()` with `.use { }` for safe, deterministic off-heap allocations (`MemorySegment`).
- **Always Capture `errno`:** Native bindings must use `Linker.Option.captureCallState("errno")`.
- **Timing Constraint:** Read `errno` from the captured segment *immediately* after the downcall execution. Any subsequent FFM operation (even an unrelated one) may overwrite the underlying thread-local capture state.
- **Alignment Awareness:** When packing structs like `sock_fprog` or `msghdr`, ensure alignment matches the target architecture (8 bytes for pointers on 64-bit). Use `ValueLayout.ADDRESS` for pointers.

### 7. Native Engine Decoupling for Testability
Core enforcement logic (like `BpfFilter.kt` and `Landlock.kt`) must not call `LinuxNative` static methods directly for I/O. Instead, they must use the `NativeEngine` traits accessed via `LinuxNative.getFileSystem()`, `LinuxNative.getNetworking()`, etc.

**How to use in implementation:**
```kotlin
val fs = LinuxNative.getFileSystem()
val res = fs.openat(dirFd, path, flags, mode)
```

**How to use in tests:**
```kotlin
@BeforeEach
fun setup() {
    val mockFs = MockNativeFileSystem()
    LinuxNative.setEngine(mockFs)
}
```
This pattern is mandatory for any code that needs to be verified via unit tests with fault injection.

### 8. Containment Exception Translation
The violation detector in `ContainedExecutors.isDirectContainmentViolation()` uses a two-priority strategy:
1. **Priority 1 (locale-independent):** `\berror[=:]\s*(1|13)\b` — matches JVM-encoded errno 1 (`EPERM`) and 13 (`EACCES`).
2. **Priority 2 (for `IOException`/`SocketException` only):** `(?i)\bOperation not permitted\b|\bPermission denied\b|\brefusé\b|\bverweigert\b|\bnegado\b` and `"Cannot run"`.
3. `AccessDeniedException` (`java.nio.file`) — always treated as a violation.
4. **Prohibited:** broad fragments like `"denied"` without class restrictions (avoid false positives on standard business logic exceptions).
- **Traversing:** Always call `isContainmentViolation(t)` (performs cause-chain traversal) rather than calling `isDirectContainmentViolation(t)` directly.

---

## 🔄 Verification & Testing
For test script commands and Podman orchestration parameters, refer to the parent registry in [Root AGENTS.md](file:///home/leanid/Documents/code/java/jseccomp/AGENTS.md#5-testing-and-verification-guidelines).

---

## 📓 Code Issues & Discoveries Journal
If you discover a kernel-level behavior, FFM nuance, or bug during development, you MUST log it immediately by creating a new markdown file in [docs/internals/backlog/](../docs/internals/backlog/) and registering it in the [backlog README.md](../docs/internals/backlog/README.md).
