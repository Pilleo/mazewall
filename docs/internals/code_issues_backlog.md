# Code Issues Backlog

## Recent Findings (Project Review June 2026)

### 🔴 [Severity: HIGH]: Yama `ptrace_scope` Blocks Daemon's `process_vm_readv` (Missing `PR_SET_PTRACER`)
**Context:** When the test worker JVM spawns the supervisor daemon via `ProcessBuilder`, the daemon is born as a child process. By default, Linux Yama `ptrace_scope=1` restricts `ptrace` (and thus `process_vm_readv`) such that only ancestors can trace descendants. Because the daemon is a descendant of the test worker JVM, its attempts to read string arguments (e.g. `pathStr` for `SYS_OPENAT`) from the test worker JVM threads using `process_vm_readv` are denied with `EPERM`.
**Symptoms:** 
1. The daemon fails to extract the path and silently falls back to `-EPERM` for `handleInjectFd`.
2. This causes seccomp to return `EPERM` to the tracee JVM thread.
3. The tracee throws `java.io.FileNotFoundException (Operation not permitted)` during application file IO, or `NoClassDefFoundError` if the blocked read occurs during internal JVM class loading (e.g., when trying to load exception handlers).
**Needed:** The parent test worker JVM MUST invoke `prctl(PR_SET_PTRACER, daemonPid)` immediately after spawning the daemon process to explicitly grant the child daemon permission to read the parent's memory under restricted ptrace scopes. Fixed in `SupervisorDaemonManager.kt`.


### 🔴 [Severity: HIGH]: Stacktrace-Enforced Process Spawning Safepoint Deadlock and Trace Propagation Gotchas
**Context:** During the implementation of the AI Agent sandboxing PoC, we discovered that:
1. **Empty Stack Trace on `execve` inside child processes:** When a child process is spawned via `clone` or `vfork`, it executes `execve` under its own PID. The seccomp notify event is triggered on that child PID. Because the child PID is not a registered JVM thread, calling `Thread.getStackTrace()` on it returns an empty array, making it impossible to enforce stacktrace scoping policies on `EXECVE`/`EXECVEAT` directly.
2. **ClassLoader/Safepoint Deadlocks when supervising `CLONE`:** Spawning a JVM thread calls `clone` (or `clone3`). If `clone` is supervised, stacktrace inspection forces a JVM safepoint while the JVM holds internal thread-creation locks, leading to a permanent deadlock during `Thread.start()`.

**Needed / Workaround:**
To enforce stacktrace-based scoping for process execution safely:
1. Allow `CLONE` and `CLONE3` entirely to prevent safepoint deadlocks during thread creation.
2. Force the JVM to use `vfork` or `fork` for process spawning (`-Djdk.lang.Process.launchMechanism=vfork`).
3. Supervise `VFORK` and `FORK` to capture the calling stacktrace on the parent thread before the child process is created.

**The Real (JVM-Independent) Fix:**
To enforce stacktrace-based scoping for process execution safely and portably:
1. **BPF-Level Clone Flag Inspection:** Configure the BPF program to inspect the clone flags. If `CLONE_THREAD` (value `0x00010000`) is set (indicating a Java thread creation), the BPF filter must **allow it unconditionally**, bypassing seccomp interception and avoiding safepoint deadlocks. Only intercept `clone` when `CLONE_THREAD` is absent (which signals process creation).
2. **Parent Stack Trace Capture on Spawn Entry:** Intercept `vfork`, `fork`, and non-thread `clone` calls on the parent JVM thread. The JVM validation listener captures the parent's stack trace and registers the thread TID in a global `PendingSpawnRegistry` with its authorized stack trace before allowing the syscall to continue.
3. **State-Based Propagation on Child `execve`:** When the child process ($PID_{child}$) calls `execve`/`execveat`:
   - The seccomp filter intercepts `execve`.
   - The supervisor daemon reads `PPID` from `/proc/$PID_{child}/stat` to verify it is a descendant of the JVM.
   - The JVM validation listener queries the `PendingSpawnRegistry` to find the parent thread (which is guaranteed to be suspended by the kernel in `vfork`/`clone` with `CLONE_VFORK` until the child execs).
   - The listener evaluates the child's `execve` using the pre-authorized stack trace of the blocked parent thread.
   - Once the child execs, the parent thread returns and is removed from the `PendingSpawnRegistry`.

### 🔵 [Severity: ENHANCEMENT]: Socket Address Family Filtering for Network Isolation Evasion Prevention
**Context:** Currently, `mazewall` blocks networking by disabling `socket` or `connect` completely. This breaks local IPC utilizing Unix Domain Sockets (`AF_UNIX`/`AF_LOCAL`) which are common for DB/daemon integration. NVIDIA OpenShell inspects the first argument (Address Family) of `socket()` to allow `AF_UNIX` while denying `AF_INET`/`AF_INET6`, `AF_PACKET`, etc.
**Needed:** Implement a `SocketAddressFamilyInspector` under `SyscallInspectionPipeline` to filter `socket` syscall arguments, preserving local IPC while preventing internet or raw packet capture.




### ✅ [RESOLVED]: JVM Safepoint / seccomp USER_NOTIF Circular Deadlock in `ProfilerTraceListener`
**Status:** RESOLVED (June 2026)
**Target Area:** `io.mazewall.profiler.internal.ProfilerTraceListener.readNextEvent` and `processEvent`
**Context & Root Cause:** When `ProfilerTraceListener` received a seccomp `USER_NOTIF` event for a profiled thread (e.g., an `openat` call from a worker), the thread was suspended in kernel space awaiting the `SECCOMP_USER_NOTIF_FLAG_CONTINUE` response from the daemon. The daemon delivers that response only after receiving an ACK byte from the trace-listener. However, the trace-listener was calling `Thread.getStackTrace()` on the profiled thread *before* sending the ACK — inside `readNextEvent()`. `Thread.getStackTrace()` requires the JVM to stop the target thread at a safepoint. A thread blocked in the kernel (blocked on a syscall, not yet returned to user space) cannot reach a safepoint until it returns from the kernel. It cannot return from the kernel until the seccomp response arrives. The response cannot arrive until the ACK is sent. The ACK cannot be sent because the trace-listener is waiting for the safepoint. **Circular deadlock, no timeout, permanent hang.**
**Fix:** Removed the inline `Thread.getStackTrace()` call from `readNextEvent()` entirely. Reordered `processEvent()` to call `sendAckIfNecessary()` first (releasing the kernel-blocked thread), then `accumulateStackTrace()` (which calls `Thread.getStackTrace()` after the thread is free to reach a safepoint). The captured stack trace remains diagnostically accurate: after returning from the syscall, the thread is still executing within the same high-level call chain (e.g., `File.readText()` → the profiled lambda), so the stack frames reflect the code that triggered the syscall.

### 🔴 [Severity: HIGH]: In-Process Stacktrace Analysis ClassLoader Deadlock in Supervisor
**Context:** During real-time (in-process) seccomp supervision using `USER_NOTIF`, when the sandboxed JVM thread is blocked on a syscall (e.g. `openat`) during lazy classloading, it holds the JVM's internal `ClassLoader` monitor. If the supervisor validation thread attempts to resolve the stack trace (which may trigger classloading of Kotlin stdlib or policy classes) or executes user-provided scoping policy, it blocks on the same `ClassLoader` monitor. This creates a permanent circular deadlock. This differs from the Profiler, which parses `strace` logs out-of-process and is completely free from tracee-side JVM locks.
**Needed:** A daemon-side fast-path bypass is required to intercept all file reads referencing the JVM's home directory (`java.home`), the application's classpath (`java.class.path`), and JVM startup agents (e.g., Jacoco). The daemon must resolve these to canonical absolute paths and inject the file descriptor immediately without delegating to the JVM validation thread, bypassing the scoping policy for standard classes.

### 🔴 [Severity: MEDIUM]: Kotlin Inlining Causes ArchUnit noGenericExceptionCatching Violation
**Context:** Kotlin inline functions like `Arena.ofConfined().use { ... }` or `nativeScope` expand at compilation time to copy their internal `try ... catch (e: Throwable)` or `finally` blocks directly into caller methods. As a result, caller methods in un-excluded packages like `io.mazewall.enforcer.supervisor` are falsely reported by ArchUnit as catching generic `Throwable` or `Exception` (e.g. `connectWithRetry` or `handleInjectFd`), violating the strict `noGenericExceptionCatchingInEnforcer` rule.
**Needed:** Add supervisor classes (e.g. `SupervisorSessionHandler`, `SupervisorInstaller`) to the ArchUnit exclusions list in `ArchitectureTest.kt` for this rule, as they do not catch generic exceptions directly but rely on standard FFM scoped block helpers.

### ✅ [RESOLVED]: Classloader Lock Deadlock in `JVMValidationListener` under `StacktraceScopingPolicy`
**Status:** RESOLVED (June 2026)
**Target Area:** `io.mazewall.enforcer.supervisor.JVMValidationListener.runValidationReactor`
**Context & Proof:** When a sandboxed thread ($T_1$) issued an `openat` syscall during lazy JVM class loading, it held the JVM `ClassLoader` monitor. The seccomp supervisor blocked $T_1$ in kernel space. The supervisor validation thread ($S_1$) then attempted to execute Kotlin stdlib helpers (`toList`, `find` with lambdas) to convert the stack trace and resolve the syscall enum — but those helpers were themselves not yet loaded, requiring classloading. $S_1$ blocked waiting for the `ClassLoader` lock that $T_1$ held. Permanent deadlock.
**Root Cause:** The tracee JVM thread was suspended while holding a ClassLoader lock, while the listener thread tried to evaluate the scoping policy, triggering class loading of stdlib or policy classes on the same loader.
**Fix:** The initial stacktrace-based `isClassloaderActive` check was insecure (malicious code could bypass seccomp verification by triggering lazy class loading during exploits) and fragile. It was removed and replaced with a path-based Daemon-Side Fast-Path in `SupervisorSessionHandler`. The daemon intercepts all `SYS_OPEN`, `SYS_OPENAT`, and `SYS_OPENAT2` calls, normalizes the target path to an absolute path, and matches it against the JDK home directory (`java.home`). If it resides within `java.home`, the daemon directly opens and injects the FD, bypassing JVM-side policy evaluation completely and safely preventing lock contention during standard runtime classloading.


### 🟡 [Severity: LOW]: High-Frequency Arena Allocation Overhead (MM Optimization)
**Context:** The current `nativeScope` utility and profiler reactor loop create a new `Arena.ofConfined()` for every single operation (syscall resolution, polling, etc.). This puts unnecessary pressure on the JVM native allocator and GC.
**Needed:** Investigate "Scoped Arenas" using Kotlin context parameters or a `ThreadLocal` arena for high-frequency reactor loops. Reuse the same arena for all operations within a single task or notification lifecycle.

### 🔵 [Severity: ENHANCEMENT]: Memory Segment Pooling for Profiler USER_NOTIF
**Context:** The `seccomp_notif` and `seccomp_notif_resp` structures are used for every trapped system call. Continually allocating and zeroing these segments in the `reactorLoop` is inefficient.
**Needed:** Implement a simple `SegmentPool` for fixed-size FFM structures. Pre-allocate a small cache of aligned segments and reuse them across different notifications.

### 🔵 [Severity: ENHANCEMENT]: Compile-Time Feature Proof Tokens and Scope-Safe Policy Builders (Type-State Pattern)
**Context:** Currently, `ContainedExecutors.kt` throws a runtime `UnsupportedOperationException` if process-wide containment is applied with Landlock rules because Landlock has historically been considered thread-scoped only. However, process-wide Landlock is supported on some newer kernels/setups. Blocking it unconditionally at compile-time or throwing runtime failures limits support on modern systems.
**Needed:** Implement compile-time feature proof tokens and type-state parameterized builders.
1. Define a `ProcessWideLandlockToken` that can only be obtained at runtime by checking support (`Landlock.isSupportedProcessWide()`).
2. Parameterize `PolicyBuilder` with a `Scope` type-state, requiring the token to configure Landlock filesystem rules on a process-wide policy.
3. Implement a `LandlockFallback` enum (`FailClosed`, `WarnAndBypass`) for process-wide policy installations when runtime kernel support is absent.
4. This ensures that Landlock's conditional process-wide availability is verified at runtime before configuration, preventing illegal rulesets while preserving compilation safety.

### 🔵 [Severity: ENHANCEMENT]: Verified-by-Construction BPF Bytecode (BpfProgram<Status>)
**Context:** BPF filters are constructed via string builders or manual instruction lists and passed directly to the kernel. A typo or structural error in a jump target or instruction boundary results in a runtime error or, worse, a kernel validation failure that triggers a fallback/bypass.
**Needed:**
1. Introduce a phantom type `BpfProgram<Status>` where `Status` is `Unverified` or `Verified`.
2. Provide a builder DSL that generates instructions into `BpfProgram<Unverified>`.
3. Require passing `BpfProgram<Unverified>` through an in-memory/in-app BPF static verifier or a local compilation dry-run to produce `BpfProgram<Verified>`.
4. Enforce that `PureJavaBpfEngine.install` only accepts `BpfProgram<Verified>`, guaranteeing that only mathematically verified filters can ever be loaded into the kernel.

### 🔵 [Severity: ENHANCEMENT]: Algebraic Policy Composition (Semigroup/Monoid)
**Context:** Policies are composed using the `+` operator or manual combination logic, but this does not adhere to a formal algebraic model. This makes complex nesting of policies or verification of identity laws difficult to test and model.
**Needed:**
1. Formally implement the `Monoid` interface for `Policy<S, State>`.
2. Define the identity element (`empty` policy) and ensure that combination is associative.
3. Leverage this monoidal composition to cleanly verify, merge, and diff sandbox configurations.

### 🔴 [Severity: MEDIUM]: Manual FFM Layout Maintenance and ABI Drift Risk
**Context:** `Layouts.kt` contains hand-coded `MemoryLayout` definitions for critical kernel structures (e.g., `sock_fprog`, `seccomp_data`, `landlock_ruleset_attr`). While `LayoutValidator` performs runtime alignment checks, it does not guarantee that the offsets match the actual target architecture's ABI if they differ (e.g., padding rules between x86_64 and AArch64).
**Needed:** Implement a robust validation or generation strategy.
1. Use `jextract` as a test-time "oracle" to verify that `Layouts.kt` offsets match the ground-truth C headers for all supported architectures.
2. Alternatively, generate separate architecture-specific layouts and switch them at runtime via `Arch.current()`.

### 🔵 [Severity: ENHANCEMENT]: BPF Disassembler/Dumper for Policy Verification
**Context:** Debugging seccomp policy behavior is difficult because the generated bytecode is opaque. Developers have no easy way to verify exactly what instructions were generated for a complex `Policy`.
**Needed:** Add a `disassemble()` or `dump()` method to `BpfProgram`.
1. It should produce a human-readable mnemonic output (e.g., `ld [0]`, `jeq #59, label_allow, label_deny`).
2. Integrate this into the logging or `DiagnosticsState` to allow developers to inspect the compiled filter during debugging.

### 🔵 [Severity: ENHANCEMENT]: Algebraic Policy Composition (Semigroup/Monoid)
**Context:** Policies are composed using the `+` operator or manual combination logic, but this does not adhere to a formal algebraic model. This makes complex nesting of policies or verification of identity laws difficult to test and model.
**Needed:**
1. Formally implement the `Monoid` interface for `Policy<S, State>`.
2. Define the identity element (`empty` policy) and ensure that combination is associative.
3. Leverage this monoidal composition to cleanly verify, merge, and diff sandbox configurations.

### 🔵 [Severity: ENHANCEMENT]: Strongly Typed Syscall Flags and Native Argument Definitions
**Context:** Many `NativeEngine` methods use raw `Int` or `Long` for flags (e.g., `open(path, flags)`, `mmap(..., prot, flags)`). This is prone to transposition bugs where a flag from one syscall is accidentally passed to another.
**Needed:** Introduce specialized value classes or enums for common bitmasks.
1. Define `OpenFlags`, `MmapProt`, `MmapFlags`, `CloneFlags`, etc.
2. Update the `NativeEngine` trait to use these types instead of raw primitives.
3. Refine `RealNativeHelper.toLong` to handle these typed wrappers.

### 🔵 [Severity: ENHANCEMENT]: Symbolic Errno Mapping in `SyscallResult`
**Context:** When a syscall fails, `SyscallResult.Error` only provides the raw `Int` errno. Seeing `errno=1` is less helpful than `EPERM`.
**Needed:** Implement a symbolic mapping for POSIX error numbers.
1. Add a utility to map common `Int` errnos to their symbolic names (e.g., `1 -> "EPERM"`, `13 -> "EACCES"`).
2. Update `SyscallResult.Error.toString()` and `throwErrno()` to include this symbolic name for better developer feedback.

### 🔵 [Severity: ENHANCEMENT]: Formal Monoidal Composition for `BillOfBehavior`
**Target:** `io.mazewall.profiler.BillOfBehavior`
**Context:** `BillOfBehavior` has a manual `plus` operator, but it isn't formally modeled as a Monoid. Merging complex behavior profiles (e.g., merging a JVM floor with an application-specific trace) is a core operation for generating policies.
**Needed:** Formally implement the Monoid pattern for `BillOfBehavior`.
1. Define an `identity` (Empty SBoB).
2. Ensure the `plus` operation is associative and correctly merges sets and maps (including deep merging of stack profiles).
3. This allows using standard functional aggregators like `list.reduce(BillOfBehavior::plus)` or `list.fold(BillOfBehavior.empty, ...)` with algebraic certainty.

### 🔵 [Severity: ENHANCEMENT]: Refactor Profiler Daemon to use Coroutines (Structured Concurrency)
**Target:** `io.mazewall.profiler.engine.ProfilerDaemonEngine` and `ProfilerSessionHandler`
**Context:** The current profiler daemon uses a "thread-per-connection" model and manual thread management for handling tracee sessions. This is heavyweight and makes graceful shutdown/cancellation complex.
**Needed:** Transition the daemon to a coroutine-based architecture.
1. Use `supervisorScope` and `launch` for managing connection handlers and session loops.
2. Replace synchronous `transport.poll` loops with non-blocking equivalents (e.g., using a coroutine-friendly wrapper around `epoll` or `io_uring`).
3. This improves the daemon's scalability and makes its lifecycle management more robust and idiomatic.

### 🔵 [Severity: ENHANCEMENT]: Asynchronous Trace Event Streaming via `Channel` / `Flow`
**Target:** `io.mazewall.profiler.Profiler` and `ProfilerTraceListener`
**Context:** Captured trace events and stack traces are currently collected using `CopyOnWriteArrayList` and `ConcurrentHashMap`. The listener thread synchronously updates these collections, which can introduce latency in the "ACK loop" and increase the risk of deadlocks if the collections block.
**Needed:** Use Kotlin `Channel` or `Flow` to stream events.
1. The `ProfilerTraceListener` should send `TraceEvent` objects into a `Channel`.
2. The `BobCompiler` (or a background collector) can consume these events asynchronously.
3. This reduces the time spent by the listener thread in the critical section of the seccomp notify loop, improving profiling performance and decoupling event capture from analysis.

### ✅ [RESOLVED]: Memory Registry Leak in `Profiler.threadRegistry`
**Status:** RESOLVED (June 2026)
**Target Area:** `io.mazewall.profiler.Profiler`
**Context & Proof:** `Profiler.profile` registers the current thread via `threadRegistry[spid] = Thread.currentThread()`. There is no corresponding `remove` call in the `finally` block or completion callback.
**Fix:** Added a `finally` block to `Profiler.profile` to remove the TID from the registry once the workload is finished.

### ✅ [RESOLVED]: Thread-Safety Violation: Mutable `LongArray` in `SyscallEvent`
**Status:** RESOLVED (June 2026)
**Target Area:** `io.mazewall.profiler.engine.SyscallEvent`
**Context & Proof:** `SyscallEvent` used `val args: LongArray`. Since arrays in JVM are mutable, any reference holder can execute `event.args[0] = value`.
**Fix:** Refactored `SyscallEvent` to use an immutable `List<Long>` for `args`, ensuring the captured state remains constant.

### ✅ [RESOLVED]: Leverage Kotlin Contracts for Static Analysis
**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.enforcer` and `io.mazewall.LinuxNative`
**Context:** The compiler is often unaware of the side effects of many validation functions or the invocation guarantees of scoped lambdas. This leads to redundant checks and prevents initializing `val` properties within blocks like `withTransaction`.
**Fix:** Implemented Kotlin Contracts across core utilities including `validateLinuxAndNotVirtual()`, `SyscallResult.isSuccess()`, and `withTransaction`.

### ✅ [RESOLVED]: ArchUnit: Strict Isolation of FFM (`java.lang.foreign`) Boundaries
**Status:** RESOLVED (June 2026)
**Target:** Entire project structure
**Context:** FFM calls must go through `NativeEngine` to allow mockability and fault injection, but nothing stops a developer from importing `java.lang.foreign.*` directly in a policy builder or integration test.
**Fix:** Implemented ArchUnit rules `rawMemorySegmentAccessMustBeEncapsulated` and `memorySegmentReinterpretIsBanned` asserting restricted access to FFM classes.

### 🔵 [Severity: ENHANCEMENT]: Strongly-Typed Generics for `ioctl` Commands (`IoctlCommand<Req, Res>`)
**Target:** `io.mazewall.NativeEngine` and `io.mazewall.ffi`
**Context:** The backlog notes that `ioctl` fallback crashes happen because arguments are highly polymorphic and easy to misalign when reading memory.
**Needed:** Define `class IoctlCommand<Req : StructLayout, Res : StructLayout>(val code: Long)`. `NativeEngine.ioctl` would use these generics, ensuring the request/response payload structs strictly match the `ioctl` command code at compile time.

### ✅ [RESOLVED]: Type-State Machine for Landlock Ruleset Mutability
**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.landlock.Landlock`
**Context:** Landlock follows a strict `Create -> Add Rules -> Restrict Self` lifecycle. Adding a rule after restriction fails silently or errors out.
**Fix:** Implemented `RulesetState` (Building/Sealed) and `LandlockRuleset<S>` type-state wrapper to ensure rules are only added before the ruleset is sealed.

### 🔵 [Severity: ENHANCEMENT]: ArchUnit: Enforce `Errno` Capture Locality Wrapper
**Target:** `io.mazewall.ffi` and `io.mazewall.NativeEngine`
**Context:** `AGENTS.md` explicitly warns that `errno` must be read *immediately* after an FFM downcall, or it will be overwritten by the JVM.
**Needed:** Use ArchUnit to ban direct calls to FFM `MethodHandle.invokeExact()` anywhere outside a dedicated `SyscallInvoker` utility, ensuring that the downcall and the subsequent `errno` capture are always atomically bound together.

### 🔵 [Severity: ENHANCEMENT]: Phantom Types for Thread Pool Containment Constraints (`SandboxedExecutor`)
**Target:** `io.mazewall.enforcer.ContainedExecutors`
**Context:** Standard `ExecutorService` usage trivially bypasses Tier 2 (thread-scoped) sandboxes if a developer accidentally delegates tasks to global thread pools (e.g., via `CompletableFuture.supplyAsync`).
**Needed:** Introduce `interface SandboxedExecutor<out P : Policy> : Executor`. Require sensitive classes to explicitly depend on this typed executor (e.g., `SandboxedExecutor<Policy.NO_NETWORK>`). This API guardrail forces the compiler to verify that components run on thread pools with the required security baseline, preventing *accidental* architectural leaks of data-oriented workloads. Note: Due to JVM Type Erasure, this does NOT prevent a malicious actor with ACE from reflecting or escaping the sandbox at runtime (which is caught instead by the Tier 1 Process-Wide baseline).

### 🔵 [Severity: ENHANCEMENT]: Proof-of-Progress State Machine for Landlock Discovery (`DiscoveryTask<Status>`)
**Target:** `io.mazewall.profiler.IterativeProfiler`
**Context:** The `IterativeProfiler` uses a feedback loop (Run -> Catch -> Resolve -> Add Rule -> Retry). If resolution fails or retries occur without new rules, it can enter infinite loops.
**Needed:** Use a state machine to track discovery progress: `Discovery<Pending> -> Discovery<Resolved(Path)> -> Discovery<RuleVerified> -> Discovery<Retrying>`. The `retry()` function will only accept `Discovery<RuleVerified>`, proving at compile-time that each iteration contributes a verified rule toward the final policy, preventing infinite-loop regressions.

### 🔴 [Severity: LOW]: Architectural DIP (Dependency Inversion) Violations in Native Scopes
**Target:** Entire project
**Context:** Many classes directly instantiate `Arena.ofConfined()` or rely on the `LinuxNative` object, making isolated unit testing without a Linux kernel difficult.
**Needed:** Refactor components to accept `NativeEngine` or `NativeScope` as constructor dependencies, improving mockability and environment independence.

### 🔴 [Severity: LOW]: Redundant State in `ThreadStateRegistry` vs `Policy`
**Target:** `io.mazewall.enforcer.ThreadStateRegistry`
**Context:** The registry manually tracks `landlockAppliedReads` and `landlockAppliedWrites`, partially duplicating the information already contained within the `Policy` object (DRY violation).
**Needed:** Consolidate state tracking to use the `Policy` object as the single source of truth for applied Landlock restrictions.

### 🔵 [Severity: ENHANCEMENT]: Compile-Time BPF Termination Safety (Type-State RET Enforcement)
**Context:** Currently, `BpfBuilder.NrLoaded.build()` can be called on a program that does not end with a `RET` instruction. While the kernel verifier will reject such programs at runtime, it results in a "Fail Closed" crash rather than a compile-time error.
**Needed:** Split `NrLoaded` into `Active` and `Terminated` states. The `ret()` method should transition the builder to the `Terminated` state, and only `Terminated` should expose the `build()` method.

### ✅ [RESOLVED]: ProfilerTraceListener Lacks Deterministic Lifecycle (AutoCloseable)
**Status:** RESOLVED (June 2026)
**Context:** `ProfilerTraceListener` starts a background thread and reads from a socket. Currently, there is no standard way to signal shutdown or join the thread, leading to potential leaks or "half-dead" listeners during profiler restarts.
**Fix:** `ProfilerTraceListener` now implements `AutoCloseable` and ensures proper cleanup.

### 🟡 [Severity: LOW]: High-Frequency Arena Allocation Overhead (MM Optimization)
**Context:** The current `nativeScope` utility and profiler reactor loop create a new `Arena.ofConfined()` for every single operation (syscall resolution, polling, etc.). This puts unnecessary pressure on the JVM native allocator and GC.
**Needed:** Investigate "Scoped Arenas" using Kotlin context parameters or a `ThreadLocal` arena for high-frequency reactor loops. Reuse the same arena for all operations within a single task or notification lifecycle.

### 🔵 [Severity: ENHANCEMENT]: Memory Segment Pooling for Profiler USER_NOTIF
**Context:** The `seccomp_notif` and `seccomp_notif_resp` structures are used for every trapped system call. Continually allocating and zeroing these segments in the `reactorLoop` is inefficient.
**Needed:** Implement a simple `SegmentPool` for fixed-size FFM structures. Pre-allocate a small cache of aligned segments and reuse them across different notifications.

### 🔴 [Severity: MEDIUM]: Residual Interface Segregation Violation (ISP) in `NativeEngine`
**Target:** `io.mazewall.NativeEngine`
**Context:** While sub-engines (FileSystem, Networking) were extracted, the main `NativeEngine` interface still exposes low-level, unconstrained `syscall`, `ioctl`, and `poll` methods. Any component requiring the engine for simple file operations is unnecessarily exposed to raw syscall capabilities.
**Needed:** Segregate raw syscall operations into a separate `RawSyscallOperations` interface, ensuring higher-level components only depend on restricted, domain-specific traits.

### 🔵 [Severity: ENHANCEMENT]: `SeccompAction` Violates Open/Closed Principle (OCP)
**Target:** `io.mazewall.core.SeccompAction`
**Context:** Currently an `enum`, `SeccompAction` cannot support dynamic parameters (e.g., custom errno values for `ACT_ERRNO` or trace IDs for `ACT_TRACE`) without breaking changes or global modifications.
**Needed:** Refactor `SeccompAction` to a `sealed interface`. Use `data object`s for static actions and `data class`es for parameterized actions, enabling extensibility while maintaining exhaustive compiler checks.

### 🔵 [Severity: ENHANCEMENT]: Type-State for `FileDescriptor` Lifecycles (Compile-Time Use-After-Close Safety)
**Target:** `io.mazewall.core.FileDescriptor`
**Context:** Current FD safety relies on runtime validity checks. Use-after-close errors result in runtime crashes rather than being caught by the compiler.
**Needed:** Introduce a second Phantom Type parameter `Lifecycle` (e.g., `FileDescriptor<Role, Open>`). Methods like `close()` should consume an `Open` FD and return a `Closed` one, making any subsequent usage of the `Closed` token a compile-time error.

### ✅ [RESOLVED]: Generic Type Safety for `MemorySegment` Payloads
**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.NativeEngine` and `io.mazewall.profiler.engine`
**Context:** Native interfaces blindly accept untyped `MemorySegment` objects. This allows a developer to pass a segment initialized with the wrong layout (e.g., passing a `sockaddr` to a `poll` call), leading to memory corruption or kernel rejections.
**Fix:** Introduced `ManagedSegment` (Confined/Shared) in `io.mazewall.ffi.memory` to ensure type and scope safety.

### 🔴 [Severity: MEDIUM]: ArchUnit: Ban `java.lang.Thread` for Context Preservation
**Target:** Entire project
**Context:** Direct usage of `java.lang.Thread` or standard `Executors` ignores `mazewall`'s thread-local containment states and structured concurrency requirements, leading to "context leaks" where sandboxed tasks execute unconstrained.
**Needed:** Implement an ArchUnit rule banning raw thread instantiation and unmanaged executor usage. Force all asynchronous execution through managed `Coroutines` or `ContextAwareExecutor` implementations.

### ✅ [RESOLVED]: Leverage Kotlin Contracts for Static Analysis
**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.enforcer` and `io.mazewall.LinuxNative`
**Context:** The compiler is often unaware of the side effects of validation functions or the invocation guarantees of scoped lambdas. This leads to redundant checks and prevents initializing `val` properties within blocks like `withTransaction`.
**Fix:** Implemented Kotlin Contracts across core utilities including `validateLinuxAndNotVirtual()`, `SyscallResult.isSuccess()`, and `withTransaction`.

## Foundational Architecture & Test-Harness Enablers

### 🔵 [Severity: ENHANCEMENT]: Compile-Time Enforced Tier 1 Process Baseline (`ProcessContainmentToken`)
**Target:** `io.mazewall.enforcer.ContainedExecutors`
**Context:** `mazewall`'s Threat Model explicitly states that Tier 1 (process-wide `NO_EXEC` baseline) is an absolute architectural backstop against Arbitrary Code Execution (ACE) thread-hopping escapes. If a developer creates a Tier 2 (thread-scoped) sandbox without installing Tier 1, the system is highly vulnerable.
**Needed:** Make `ContainedExecutors.installOnProcess()` return a `ProcessContainmentToken<Tier1>` singleton. Modify `ContainedExecutors.wrap()` (which creates Tier 2 thread pools) to require this token as an argument. This forces developers to mathematically prove to the compiler that the Tier 1 process-wide baseline has been successfully installed before they can spawn a Tier 2 thread-scoped sandbox.

### 🔵 [Severity: ENHANCEMENT]: Phantom Types for Context-Aware Capability Tokens
**Target:** `io.mazewall.NativeTransaction` and `io.mazewall.LinuxNative`
**Context:** Currently, `NativeTransaction` acts as a blanket capability token, allowing any transaction to perform any native operation (read-only or read-write). This means an auditing or profiling phase can accidentally invoke a mutating system call (like `prctl` or `socket`) when it only intended to read memory.
**Needed:** Implement context-sensitive capability tokens using **Phantom Types**.
1. Define marker interfaces `ReadOnly` and `ReadWrite`.
2. Refactor `NativeTransaction` to `NativeTransaction<Mode>`.
3. Update `NativeEngine` methods to demand specific modes via context receivers, e.g., `context(_: NativeTransaction<out ReadOnly>)` for `processVmReadv` and `context(_: NativeTransaction<ReadWrite>)` for `prctl`. This ensures at compile-time that restricted scopes cannot perform mutating operations.

### 🔵 [Severity: ENHANCEMENT]: Type-State Enforced BPF DSL
**Target:** `io.mazewall.seccomp.BpfProgram.BDL`
**Context:** The current BPF DSL uses `String` identifiers for jump labels (e.g., `jmp("LABEL_ALLOW")`). This is prone to typos that cause runtime `IllegalStateException` during filter compilation and makes it difficult to verify that all branches are correctly resolved.
**Needed:** Refactor the DSL to use strongly-typed `BpfLabel` tokens generated by the builder.
1. `val allowLabel = createLabel()`
2. `jmpIfTrue(allowLabel)`
This ensures jump targets are validated at compile time and guarantees no dangling branches exist in the BPF program before it reaches the kernel.

## Critical Sandbox Escape & Security Constraints

### 🔴 [Severity: CRITICAL]: Standard Java Concurrency (`Virtual Threads`, `CompletableFuture`) trivially bypasses Thread-Scoped (Tier 2) containment without ACE
**Target:** `io.mazewall.enforcer.ContainedExecutors` and `docs/internals/SECURITY_CONSIDERATIONS.md`
**Failure Hypothesis:** A developer wraps an `ExecutorService` using `ContainedExecutors.wrap(delegate, Policy.NO_NETWORK)` to safely process an untrusted document. The untrusted parsing logic calls standard Java APIs like `CompletableFuture.runAsync { ... }` or `Thread.startVirtualThread { ... }`. Because these APIs delegate execution to the JVM's pre-existing `ForkJoinPool.commonPool()` (whose OS carrier threads were spawned at JVM startup and lack the seccomp filter), the delegated task executes entirely unconstrained.
**Context & Proof:** Seccomp and Landlock filters are strictly inherited via the Linux `clone` syscall. While `mazewall` correctly notes that Arbitrary Code Execution (ACE) can poison sibling threads, it fails to account for the fact that standard, safe Java APIs bypass thread-scoped containment by design. An attacker does not need memory corruption (ACE) or native access; they only need to submit a closure to a standard thread pool. Any network request or file access within that closure will succeed, instantly neutralizing the Tier 2 containment.
**Vulnerability Chain Potential:** Critical. Completely invalidates the security boundary of Tier 2 `wrap()` for any workload that isn't strictly synchronous and single-threaded. Malicious libraries can easily initiate SSRF or read files by simply hopping threads.
**Needed:**
1. Document this fundamental architectural bypass clearly in `SECURITY_CONSIDERATIONS.md` alongside the ACE pivot. Emphasize that Tier 2 containment only restricts synchronous execution on the current thread.

### ✅ [RESOLVED] [Severity: HIGH]: Tier S Profiler is blind to background threads (No TSYNC/Inheritance)
**Target:** `io.mazewall.profiler.Profiler.kt`, `io.mazewall.profiler.engine.ProfilerInstaller.kt`
**Context:** Seccomp filters and `USER_NOTIF` file descriptors are per-thread by default. The current Tier S `Profiler.profile { ... }` only installs the filter on the calling thread. Background JVM threads (GC, JIT, ForkJoinPool) completely bypass the profiler, leading to an incomplete "JVM Floor" baseline.
**Needed:** Implement process-wide tracing support in Tier S. Two potential paths:
1. **`SECCOMP_FILTER_FLAG_TSYNC`:** Synchronize the filter to all existing threads in the thread group at installation time.
2. **`SECCOMP_FILTER_FLAG_NEW_LISTENER` + Clone Tracking:** Ensure new child threads automatically inherit the seccomp filter and notify the same supervisor daemon.
This is critical for generating a production-grade JVM Syscall Floor that accounts for background management tasks.

### 🔴 [Severity: HIGH]: Blacklist policies trigger silent Landlock filesystem lockdown due to `io_uring` check
**Target:** `io.mazewall.enforcer.ContainedExecutors.kt` (specifically `needsLandlock` calculation)
**Context:** In `ContainedExecutors.kt`, `needsLandlock` is implicitly triggered if `io_uring_setup` is allowed, even if no filesystem paths are specified. This causes Landlock to be applied with an empty ruleset, permanently locking down the filesystem for the thread. This trigger is currently undocumented in the code, making it difficult for agents to diagnose the root cause of the "silent lockdown" symptom observed in `Landlock.kt`.
- *Threat Model Nuance:* Seccomp BPF filters are unable to inspect shared memory Submission Queue Entries (SQEs) inside `io_uring` queues at syscall entry time. Thus, an attacker can bypass path-based seccomp blocks (e.g. `openat`) by submitting operations asynchronously via `io_uring`. Since kernel workers (`io-wq`) inherit the thread's Landlock credentials, applying Landlock prevents this bypass.
- *Root Cause:* If a blacklist policy allows `io_uring_setup` but blocks direct `open`/`openat`, `needsLandlock` evaluates to `true`. If the user did not define any allowed filesystem paths, Landlock is applied with empty read/write lists, effectively locking the thread out of all file operations.
**Needed:** Add a cross-reference comment to the `io_uring` trigger in `ContainedExecutors.kt`.
- *Fix Strategy:*
  1. Add a `disableLandlockForIoUring` boolean option in `PolicyBuilder` / `PolicyDefinition` (defaulting to `false` to remain secure-by-default).
  2. In `needsLandlock`, check `!policy.disableLandlockForIoUring` before applying the implicit `io_uring` trigger.
  3. In `applyLandlockIfNecessary`, log a logger warning if Landlock is implicitly triggered due to `io_uring` while no paths are defined, advising the developer to use the builder opt-out if they require full filesystem access.


## Profiler, SBoB Parser & Exception Mapping Diagnostics

### 🔴 [Severity: HIGH]: Silent failure of Profiler path resolution under Yama `ptrace_scope` > 1 leads to catastrophic Landlock enforcement failures
**Target:** `io.mazewall.profiler.engine.ProfilerDaemon`

**Failure Hypothesis:** A system administrator configures Linux with Yama `kernel.yama.ptrace_scope = 2` (admin-only attach). When the `mazewall` Profiler daemon attempts to read path arguments using `process_vm_readv` on the JVM threads, the kernel denies the read with `EPERM` (1).
**Context & Proof:** The daemon catches this `EPERM`, logs a warning to `System.err`, and gracefully returns `null` for the read string. The event is then passed to `getPathArgs()`, which receives `null` and yields an empty list of paths (`emptyList()`). The `TraceEvent` is sent to the JVM without any path context. When `BobCompiler` consumes these events, it generates an empty set for `opens` and `fsWritePaths`.
**Vulnerability Chain Potential:** High usability / stability failure. Because the profiler fails gracefully instead of crashing, it produces a "valid" `BillOfBehavior` JSON containing `[]` for paths. When this SBoB is deployed to production via `SbobParser.parseToPolicy`, it generates a `Policy` that permits zero paths. The JVM wrapper then applies Landlock with an empty ruleset, instantly revoking all filesystem access and causing a catastrophic production crash across the application.
**Needed:**
The profiler must explicitly FAIL (or throw an exception back to the JVM) if it encounters `EPERM` during path resolution. At the very least, it should inject a specific sentinel path like `"<YAMA_ERROR_UNKNOWN_PATH>"` so `BobCompiler` knows the trace was corrupted and can refuse to compile an empty SBoB, preventing invalid policies from being shipped.

### 🔴 [Severity: MEDIUM]: `SbobParser` lacks Context-Aware Working Directory resolution for Relative Paths
**Target:** `io.mazewall.SbobParser`
**Failure Hypothesis:** The `Profiler` runs in a staging environment where the JVM's Current Working Directory (CWD) is `/var/lib/staging`. An application accesses a file using a relative path, e.g., `config/settings.json`. The Profiler `tryRead` fails to resolve `dirfd` and falls back to logging the relative path `config/settings.json` into the `BillOfBehavior`. In production, the JVM's CWD is `/opt/app`. When `SbobParser` reads the SBoB, it calls `Paths.get("config/settings.json").toAbsolutePath().normalize()`, which resolves to `/opt/app/config/settings.json`.
**Context & Proof:** Landlock requires absolute paths. `SbobParser`'s `pruneSubpaths` method silently converts relative paths using the production JVM's CWD at the time of parsing. If the application actually intends to access a global relative path, or the profiler's CWD differs from the production CWD, the generated policy will allow the wrong absolute path.
**Vulnerability Chain Potential:** Medium usability and sandbox evasion failure. If a relative path is unintentionally permitted, and the production CWD is `/`, the policy might inadvertently allow access to `/config/settings.json`. This breaks deterministic policy portability across environments.
**Needed:**
1. `SbobParser` should warn or throw an error when attempting to parse a relative path, or it should accept an explicit `baseCwd` parameter to resolve relative paths deterministically rather than relying on the environmental JVM CWD at load time.
2. The Profiler should ensure all paths are fully resolved to absolute canonical paths *before* writing them to the SBoB, failing the profiler session if a `dirfd` cannot be resolved to an absolute path.

### 🔴 [Severity: MEDIUM]: Trace Listener misleads developers by capturing the Main Thread stack trace for unmapped child threads
**Target:** `io.mazewall.profiler.internal.ProfilerTraceListener`
**Failure Hypothesis:** A profiled workload spawns unmanaged child threads (via standard libraries or thread pools) that execute I/O or other trapped syscalls. When a child thread triggers a `USER_NOTIF`, the Trace Listener fails to resolve its TID to a Java `Thread` object in the JVM thread registry. As a fallback, the listener captures the stack trace of the main worker thread, permanently logging a completely unrelated stack trace for the child thread's event.
**Context & Proof:** In `ProfilerTraceListener.kt`'s `runListenerLoop`, the listener runs a loop reading events from the daemon socket:
```kotlin
val threadToProfile = Profiler.threadRegistry[pid] ?: workerThreadProvider()
val stackTrace = threadToProfile?.stackTrace?.map { it.toString() }
```
`Profiler.threadRegistry` only tracks threads that explicitly call the profiler registration hook. Child threads spawned dynamically by libraries are not registered.
When the daemon notifies the listener that a child thread with TID `pid` made a syscall, `threadRegistry[pid]` returns `null`. The listener then invokes `workerThreadProvider()`, which returns the main thread's `Thread` object. As a result, the generated `TraceEvent` contains the stack trace of the **main thread** instead of the actual child thread. During SBoB analysis, developers are shown highly confusing stack traces of the main thread supposedly performing filesystem or network actions that it never initiated.
**Cascading Risk Potential:** Medium diagnostic and maintainability defect. Misleads developers and increases debugging complexity by reporting false/uncorrect stack frames for sandboxed workload execution.
**Needed:** Remove the fallback to `workerThreadProvider()` when capturing stack traces in the listener thread. If the TID is not found in `threadRegistry`, record `null` or a sentinel string (e.g., `["<untracked_descendant_thread_stack_trace>"]`) to maintain strict data integrity.

### ✅ [RESOLVED]: `SyscallPathResolver` correctly resolves `SYMLINKAT` path parameters
**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.profiler.engine.SyscallPathResolver` (migrated from `ProfilerDaemon.getPathArgs`)
**Original Bug:** `SYMLINKAT` was grouped with `RENAMEAT`/`LINKAT` in a four-argument (olddirfd, oldpath, newdirfd, newpath) branch. The Linux `symlinkat(target, newdirfd, linkpath)` signature puts `newdirfd` at `args[1]` — not a string pointer — so reading it as a char* caused `EFAULT` failures, meaning zero paths were ever resolved for `symlinkat` calls.
**Fix:**
1. `SyscallPathResolver` now has a dedicated `"SYMLINKAT"` branch:
   ```kotlin
   "SYMLINKAT" ->
       listOfNotNull(
           tryRead(tid, args[0]),          // target — raw string pointer (no dirfd)
           tryRead(tid, args[2], args[1]), // linkpath relative to newdirfd
       )
   ```
2. `args[1]` is correctly treated as the `newdirfd` integer, not a string pointer.
3. `args[3]` (unused register) is never accessed.
**Verification:** `SyscallPathResolverTest` (June 2026) includes a regression-guard test that asserts only `args[0]` and `args[2]` are ever passed to `readStringFromProcess`, with `args[1]` used solely as the dirfd. Additional tests cover absolute linkpath, AT_FDCWD, and contrast against the correct RENAMEAT/LINKAT four-argument layout.

### ✅ [RESOLVED]: `IterativeProfiler` fails to resolve wrapped exception chains
**Status:** RESOLVED (June 2026)
**Target:** `/profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt` (specifically `extractViolationPath`)
**Context:** Progressive profiling relied on catching permission failures, but failed if a library wrapped the underlying `AccessDeniedException` in a custom exception, as the profiler only inspected the top-level exception.
**Fix:** Updated `IterativeProfiler.extractViolationPath` to use the refactored `ContainmentViolationDetector.findViolationCause(t)`, which correctly traverses the exception cause chain to find the actual containment violation.

### ✅ [RESOLVED]: Excessive Landlock directory capability leak on unlinked/deleted files ending in ` (deleted)`
**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.profiler.engine.ProfilerMemoryReader` (specifically `resolveLink`) and `io.mazewall.landlock.Landlock.kt` (specifically `handleInitialOpenFailure`)
**Context & Proof:** If an application opens a file (e.g. `/var/log/app/tmp_file`) and unlinks it immediately, reading the `/proc/$pid/fd/$fd` symlink returns `/var/log/app/tmp_file (deleted)`. Landlock's fallback mechanism previously opened the parent directory, exposing the entire directory to the sandbox.
**Fix:**
1. Stripped any trailing `" (deleted)"` suffix from resolved symlink paths in `ProfilerMemoryReader.resolveLink`.
2. Modified `Landlock.handleInitialOpenFailure` to return `res to false` immediately without falling back to the parent directory if the path ends with `" (deleted)"`.
3. Verified via unit and integration tests.

### ✅ [RESOLVED]: `ProfilerDaemon` memory-reading fails to resolve paths on page boundaries or large strings
**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.profiler.engine.ProfilerMemoryReader` (specifically `readStringFromProcess`)
**Context & Proof:** If `process_vm_readv` reads a path string that does not contain a null terminator in the returned buffer (due to page boundaries or large lengths), the profiler returned `null`, breaking rule compilation.
**Fix:** Modified `readStringFromProcess` to return a best-effort decoded string of length `bytesRead` when a null terminator is not found, instead of returning `null`. Verified via unit tests.

### ✅ [RESOLVED]: `IterativeProfiler` crashes deterministically on relative-path filesystem violations
**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.profiler.iterative.IterativeProfiler` (specifically `extractViolationPath` and `resolveAbsolutePath`)
**Context & Proof:** When a profiled workload accessed a file using a relative path, the extracted path was passed raw to the builder, violating the absolute path requirement and crashing the profiling loop. Furthermore, `resolveAbsolutePath` explicitly returned `null` for relative paths in exception messages, ignoring them completely.
**Fix:**
1. Updated `extractViolationPath` to always resolve and normalize paths to absolute form (`java.nio.file.Paths.get(it).toAbsolutePath().normalize().toString()`).
2. Modified `resolveAbsolutePath` to allow returning relative paths so they can be processed and canonicalized properly.
3. Verified via unit and build verification tests.

### 🔴 [Severity: HIGH]: `IterativeProfiler` infinite retry loop and failure on disjoint prefix file paths
**Target:** `/profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt` (specifically `updatePolicyForViolation`)
**Failure Hypothesis:** The `IterativeProfiler` checks if read is already allowed using a naive string `startsWith` check. If the workload accesses a path whose prefix matches an already allowed path but is a different, longer directory name (e.g., `/var/log-extra` when `/var/log` is allowed), the check falsely returns `true`. The profiler then attempts to add a *write* rule instead of a *read* rule, causing subsequent read attempts to continue failing and forcing the profiler into an infinite discovery retry loop that aborts after 20 retries.
**Context & Proof:** If `currentPolicy` allowed read to `/var/log`, and a trapped read occurs on `/var/log-extra`, `isCurrentlyReadAllowed` evaluates to `true` (since `"/var/log-extra".startsWith("/var/log")` is true). So `updatePolicyForViolation` executes the `then` branch: `if (isCurrentlyReadAllowed) { builder.allowFsWrite(path) }`. Thus, it adds a write rule for `/var/log-extra` but NEVER adds a read rule! On the next retry, the thread tries to read `/var/log-extra` again, gets denied, and the same logic is executed. This continues until the retry count hits `maxRetries` (20), at which point the profiler crashes.
**Cascading Risk Potential:** High stability and usability bug. Blocks iterative profiling for applications with sibling directories sharing identical prefixes.
**Needed:** Use proper component-based `Path.startsWith` logic instead of raw string `startsWith`. Map the strings in `allowedFsReadPaths` to `Path` structures and normalize them, then compare using `java.nio.file.Path.startsWith`.

### ✅ [RESOLVED]: `ProfilerDaemon` `SYMLINKAT` Mapping Error
**Status:** RESOLVED (June 2026) — see entry above for full details.
**Target:** `io.mazewall.profiler.engine.SyscallPathResolver` (logic migrated from `ProfilerDaemon.getPathArgs`)
**Fix:** `SYMLINKAT` has its own dedicated branch in `SyscallPathResolver` with the correct `(target, newdirfd, linkpath)` argument layout. Regression-guarded by `SyscallPathResolverTest`.

### 🔴 [Severity: MEDIUM]: `SbobParser` Syntactic Pruning Inaccuracy
**Target:** `io.mazewall.SbobParser.kt` (specifically `pruneSubpaths`)
**Context:** Pruning relies on syntactic `normalize()` and `startsWith()` checks. If a parent path is a symlink to a different filesystem branch, syntactic pruning is invalid and can lead to incorrect permission grants.
**Needed:** Document this limitation or switch to a more robust pruning strategy that considers the physical inode structure.

### 🔴 [Severity: HIGH]: `IterativeProfiler` Context Loss via thread creation
*   **Dimension:** DX
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt` (specifically `executeTask`)
*   **Failure Hypothesis:** When a developer profiles a workload that relies on `ThreadLocal` context variables (e.g. MDC logging, Spring Security context, or database transactions) using `IterativeProfiler.profile { ... }`, the profiler strips all this context, causing the workload to crash or behave incorrectly during the profiling run.
*   **Context & Proof:** In `IterativeProfiler.executeTask`, the task is executed by spawning a completely new thread: `val thread = Thread { ... task.run() }`. Standard `Thread` creation does not copy `ThreadLocal` variables from the parent thread. Consequently, when the task runs, any state initialized in the main thread is lost.
*   **Cascading Risk Potential:** High DX friction and compatibility risk. Breaks profiling for modern enterprise Java frameworks that heavily rely on thread-local contexts.
*   **Recommendation:** Use `InheritableThreadLocal` where appropriate, or allow the caller to pass a custom `ExecutorService` (like a Spring `TaskExecutor`) that implements context propagation, rather than raw `Thread` instantiation.

### 🔴 [Severity: HIGH]: `IterativeProfiler` Path Truncation on Spaces
*   **Dimension:** Cascading Failure Analysis
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt` (specifically `findPathEnd`)
*   **Failure Hypothesis:** When a profiled workload is denied access to a file whose absolute path contains spaces (e.g. `/var/log/my file.txt`), the `IterativeProfiler` incorrectly truncates the path at the first whitespace when parsing the exception message, returning an invalid path and failing to whitelist the correct resource.
*   **Context & Proof:** In `IterativeProfiler.findPathEnd`, the backwards scan loop continues while `end >= 0 && (msg[end].isWhitespace() || msg[end] == '(')`. This strips trailing spaces. Then, `resolveAbsolutePath` scans backwards until it hits `!msg[start - 1].isWhitespace()`. This means that any spaces *within* the path itself will act as boundary markers, prematurely ending the path resolution. The profiler then attempts to whitelist the truncated snippet, leaving the actual file blocked.
*   **Cascading Risk Potential:** High stability and usability bug. Completely breaks iterative profiling for any workload executing in directories containing spaces.
*   **Recommendation:** Stop relying on naive string-message parsing for `IOException` or fallback exception wrappers. If exceptions must be parsed, consider injecting specific delimiters around the path string in the enforcer exception message, or using regex boundary matching that accounts for quoted/spaced paths.

## Secondary Logic Bugs, Optimizations & Enhancements

### 🟡 [Severity: LOW]: Manual FFM Layout Maintenance and Drift Risk
**Target:** `io.mazewall.ffi.Layouts` and `io.mazewall.ffi.LayoutValidator`
**Context:** Currently, FFM `MemoryLayout` definitions for system structs are maintained by hand in `Layouts.kt`. While `LayoutValidator.kt` asserts structural alignments at runtime, these should ideally be derived from system headers.
**Findings & Trade-offs:**
1.  **Linux ABI Guarantee:** The Linux kernel's "Do Not Break User Space" rule ensures that struct offsets (e.g., `seccomp_data`) remain stable across kernel versions for a given architecture. Thus, generated bindings for Linux are version-stable.
2.  **Cross-Architecture Divergence:** `jextract` produces architecture-specific layouts (e.g., x86_64 vs. AArch64). Hardcoding generated bindings from a single architecture into the JAR breaks "Write Once, Run Anywhere" if the target architectures have different padding or alignment rules for those structs.
3.  **Integration Strategies:**
    *   **Strategy A (Multi-Arch Bindings):** Generate separate packages for `x86_64` and `aarch64`, checking both into the repo and switching at runtime via `Arch.current()`. (Highest safety, but increases JAR bloat).
    *   **Strategy B (Validation Oracle):** Use `jextract` purely as a test-time oracle. CI generates bindings dynamically and reflects on them to verify that the manual `Layouts.kt` is mathematically correct against the ground-truth C headers. (Minimal JAR size, prevents human error during release, but requires manual layout updates).
**Needed:** Decide between Strategy A (full automation) and Strategy B (automated verification of manual layouts) to eliminate ABI drift risk without sacrificing multi-arch compatibility.

### ✅ [RESOLVED]: STRICT_SANDBOX crashes on Linux kernels < 6.10 (Landlock ABI < 5) due to unblocked `ioctl`
**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.landlock.Landlock` (specifically `validateAbiSupport`) and `io.mazewall.PolicyPresets` (specifically `PURE_COMPUTE`)
**Context & Proof:** The `Policy.PURE_COMPUTE` preset previously did not block `Syscall.IOCTL`. Running `PURE_COMPUTE` on a system with Landlock ABI < 5 (Linux < 6.10) caused `validateAbiSupport` to throw a fatal `UnsupportedOperationException` unconditionally.
**Fix:**
1. Updated `validateAbiSupport` to query and respect `Platform.configuredFallback()` before throwing an `UnsupportedOperationException`.
2. Explicitly blocked `Syscall.IOCTL` in the `PURE_COMPUTE` preset definition to ensure clean initialization and robust containment on older Linux kernels by default.
3. Verified via unit and build verification tests.

### 🔴 [Severity: MEDIUM]: Excessive container privileges and deprecated Audit architecture in compose.yml files
**Target:** /infra/dev/compose.yml and /demos/vulnerable-web-app/compose.yml
**Context:** The SECURITY_CONSIDERATIONS.md document clearly states that Landlock Audit is deprecated for transparent profiling because it lacks a permissive mode and causes EACCES crashes. It explicitly mandates an unprivileged profiling strategy (Tier H or Tier A). However, infra/dev/compose.yml still grants AUDIT_READ, AUDIT_CONTROL, network_mode: host, and userns_mode: host citing the deprecated Audit subsystem. Even worse, demos/vulnerable-web-app/compose.yml grants SYS_ADMIN and SYS_PTRACE, completely invalidating the claim that the demonstration runs in a restricted, unprivileged container environment. Furthermore, the demo compose file references a broken path ${PWD}/../../podman-seccomp.json.
**Needed:**
1. Remove AUDIT_READ, AUDIT_CONTROL, network_mode: host, and userns_mode: host from infra/dev/compose.yml.
2. Remove SYS_ADMIN, AUDIT_READ, and SYS_PTRACE from demos/vulnerable-web-app/compose.yml.
3. Fix the seccomp annotation path in the demo compose file to point correctly to the infra/dev/podman-seccomp.json file.

### ✅ [RESOLVED]: Redundant BPF Argument Inspection Blocks in Stacked Filters cause performance and size bloat
**Status:** RESOLVED (June 2026)
**Target:** `/enforcer/src/main/kotlin/io/mazewall/enforcer/FilterInstallationPlanner.kt` (specifically `calculateNewFilter`)
**Context:** Seccomp BPF filters are additive. If a previous filter already restricts `mmap(PROT_EXEC)`, non-thread `clone`, or unsafe `prctl` calls, there is no need to compile and install duplicate argument inspection blocks for these syscalls in a new stacked filter.
**Fix:** Optimized `FilterInstallationPlanner.calculateNewFilter` to skip redundant inspection blocks if already enforced in the current thread state.

### ✅ [RESOLVED]: Public `PureJavaBpfEngine.install` bypasses Loom Carrier Poisoning safeguards and JIT warmups
**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.seccomp.PureJavaBpfEngine` & `io.mazewall.enforcer.ContainedExecutors`
**Context:** The `PureJavaBpfEngine` and `SeccompEngine` were public and lacked check checks for virtual threads, allowing users to call them directly, potentially poisoning carrier threads.
**Fix:** Declared `SeccompEngine` and `PureJavaBpfEngine` as `internal` to prevent direct external access. Added a virtual thread check `if (Thread.currentThread().isVirtual)` inside `PureJavaBpfEngine.installInternal` as a defense-in-depth safety measure.

### 🔴 [Severity: CRITICAL]: StraceProfiler completely fails to trace `io_uring` file operations natively
**Target:** `io.mazewall.profiler.strace.StraceProfiler`, `docs/internals/profiler_design.md`
**Context:** The `profiler_design.md` document claims that Tier P (`StraceProfiler`) natively captures paths and async execution of `io_uring` (stating "Tier P (Root) | Paths and async captured natively"). This is fundamentally impossible under the current implementation and kernel constraints.
1. `StraceProfiler` executes `strace -f -e trace=file,network`. The `trace=file` class traces syscalls that take a string path argument (e.g., `openat`, `stat`). It *does not* include `io_uring_enter`.
2. Even if `io_uring_enter` were traced, the file paths exist entirely in the shared memory Submission Queue Entries (SQEs), not as standard string arguments to a syscall.
3. When the kernel processes these SQEs (often via `io-wq` kernel threads), the VFS operations occur entirely within kernel space. No user-space syscall boundary is crossed, so `ptrace` (which powers `strace`) is completely blind to them.
4. Consequently, if a workload relies on `io_uring` for file access, `StraceProfiler` will silently miss all accessed paths, producing broken policies. The claim in the documentation that `strace` captures `io_uring` paths natively is objectively false.
**Needed:**
1. Update `docs/internals/profiler_design.md` to remove the false claim that Tier P traces async `io_uring` natively. Emphasize that Tier A (Iterative Profiler) is the *only* profiler that can correctly learn `io_uring` Landlock paths (by failing and retrying) unless the application's `io_uring` is disabled during tracing (the Hybrid approach).
2. For Tier P, developers must either run with the Hybrid approach (disabling `io_uring` during profiling to force fallback to standard POSIX I/O) or rely on Iterative profiling.

### 🔵 [Severity: ENHANCEMENT]: Unprivileged Pivot Root (Empty `tmpfs`)
**Context:** Landlock is excellent for thread-scoped restrictions, but it operates on the host's view of the filesystem. If an exploit finds a bypass in Landlock or uses a filesystem action Landlock doesn't handle yet, the host files are physically present in the mount namespace.
**Needed:** Inspired by `bubblewrap`, implement a process-wide Tier 1 initialization option that uses `unshare(CLONE_NEWUSER | CLONE_NEWNS)` at JVM startup (before background threads spawn) to `pivot_root` into a `tmpfs` bind-mount jail. This provides an absolute physical backstop to Landlock by ensuring only necessary host directories are physically present in the sandbox's mount namespace.

### 🔵 [Severity: ENHANCEMENT]: Supervisor Proxy Pattern (FD Injection) & Stacktrace Scoping
**Target:** `docs/internals/supervisor_proxy_design.md`
**Context:** Thread-scoped network or file containment currently relies on static kernel rules (BPF/Landlock). These cannot provide context-aware authorization (e.g., "only allow this specific Java method to open a database connection") and are vulnerable to path traversal or TOCTTOU attacks if the sandbox needs to access dynamic files.
**Needed:** Implement a `USER_NOTIF` daemon that acts as an Authorization Proxy. The BPF filter handles fast-path I/O but punts rare, sensitive operations (like `execve` or connection pooling) to the proxy.
1.  **Stacktrace Scoping:** The proxy maps the trapped thread's OS TID to a JVM `Thread` and inspects `getStackTrace()` to authorize the call. This is protected from spoofing by `mazewall`'s Tier 1 `NO_EXEC` memory baseline.
2.  **FD Injection:** For file access, the proxy executes the open and injects the FD via `SECCOMP_IOCTL_NOTIF_ADDFD`.
3.  **Confused Deputy Mitigation:** The proxy must NEVER use string manipulation for path resolution. It must strictly use `openat2` with the `RESOLVE_BENEATH` flag to ensure the kernel physically blocks TOCTTOU symlink escapes.
For full architectural details, see `supervisor_proxy_design.md`.

### 🔵 [Severity: ENHANCEMENT]: Resource Containment via Cgroups v2
**Context:** `mazewall` currently focuses on capability and access containment (Syscalls and Filesystem) but lacks hard native resource limits (Memory, CPU) per thread or sandbox. This leaves the JVM vulnerable to native memory leaks (via FFM) or thread-spawning denial-of-service (fork-bomb) attacks within a contained thread pool.
**Needed:** Use FFM to interact with the `/sys/fs/cgroup` filesystem. When wrapping an untrusted workload, the library should dynamically create a transient cgroup v2 slice, move the worker thread's OS TID into that slice, and apply hard memory and CPU limits. This provides robust protection against resource-exhaustion DoS attacks from within sandboxed tasks.

### 🔵 [Severity: ENHANCEMENT]: Network Isolation via Namespaces (`CLONE_NEWNET`)
**Context:** Seccomp effectively blocks *new* network connections (`socket`, `connect`), but it cannot prevent data exfiltration over a pre-existing, inherited network file descriptor if the policy permits `write` or `send` calls (which are often needed for file I/O).
**Needed:** Propose an optional process-wide `CLONE_NEWNET` initialization to create a private network namespace. This physically removes the host's routing tables and network interfaces (leaving only loopback), ensuring that even if a process possesses an open socket FD, it has no route to the external network, providing a stronger architectural guarantee than syscall blocking alone.

### 🔵 [Severity: ENHANCEMENT]: Introduce Context Parameters for Memory and Engine Scopes
**Target:** Entire `:enforcer` module
**Context:** Many methods pass `Arena` or `NativeEngine` as explicit parameters, leading to verbose method signatures and "parameter drilling."
**Needed:** Refactor internal kernel-interface methods to use Kotlin 2.0+ `context(Arena)` or `context(NativeFileSystem)`. This ensures that operations like path allocation or syscall execution are only possible within an active, valid context, reducing boilerplate and improving clarity.

### 🔵 [Severity: ENHANCEMENT]: Contract-Based Invariant Validation
**Target:** `io.mazewall.Platform.kt`, `io.mazewall.enforcer.ContainerStateRegistry.kt`
**Context:** We perform many runtime checks for thread types (e.g., ensuring not on a Virtual Thread) and platform support.
**Needed:** Use `kotlin.contracts` to define formal invariants. For example, a `validateNotVirtual()` function should use a contract to prove to the compiler that the following code is safe from Loom-specific carrier poisoning, allowing for more aggressive smart-casting and reduced redundant checks.

### 🔵 [Severity: ENHANCEMENT]: Delegated Properties for Thread-Local Sandbox State
**Target:** `io.mazewall.enforcer.ContainerStateRegistry.kt`
**Context:** Accessing thread-local state requires explicit `.get()` and `.set()` calls on `ThreadLocal` objects.
**Needed:** Implement property delegates for `ThreadLocal` values. This would allow accessing the current thread's sandbox state as a standard property (`var currentPolicy by ThreadLocalDelegate(...)`), making the code more readable while safely encapsulating the underlying storage.

### ✅ [RESOLVED]: Landlock.applyRestrictiveBarrier() Silent Fail-Open
**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.landlock.Landlock.kt`
**Context:** The method ignored the return values of `prctl(PR_SET_NO_NEW_PRIVS)` and the `landlock_restrict_self` syscall. If the kernel fails to apply the ruleset (e.g. invalid FD, EPERM), the method returned silently, and the `IterativeProfiler` continued running WITHOUT filesystem containment, leading to zero discovered paths.
**Fix:** Updated to use `getOrThrow("landlock_restrict_self")` in `enforceRuleset`, ensuring failures are caught and reported.

### 🟡 [DEFERRED — Medium]: JVM Invariant Syscall Floor is Incomplete
**Context:** `BpfFilter.getJvmCriticalNrs()` contains 7 hardcoded syscalls established empirically on one JVM (Temurin G1GC x86-64). ZGC, Shenandoah, Loom, and GraalVM require additional syscalls (`userfaultfd`, `ioctl(UFFDIO_*)`, `rt_sigprocmask`, `memfd_create`, Loom epoll/eventfd calls). Profiling-based approaches are fundamentally incomplete (only capture exercised paths, miss GC-pressure-triggered and JIT-background paths). Source analysis is the correct approach but requires JVM internals expertise and cannot easily cover GraalVM separately.
**Needed:** See `docs/internals/jvm_syscall_floor_research.md` for full option analysis. Recommended path: Option E (source analysis + stress harness validation). Short-term: manually add confirmed-missing entries (`rt_sigprocmask`, non-EXEC `mmap`/`mprotect`) to `getJvmCriticalNrs()`.

### 🔴 [Severity: MEDIUM]: `ContainmentDesignSpec` test fails on systems without Landlock support
*   **Dimension:** Cascading Failure Analysis
*   **Target Area:** `enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/ContainmentDesignSpec.kt` (specifically `"Pre-warmed JVM task runs successfully..."`)
*   **Failure Hypothesis:** The test instantiates `ContainedExecutors.wrap(executor, Policy.builder().build())`. Because the default policy allows `IO_URING_SETUP`, `ContainedExecutors` automatically triggers Landlock. If the kernel does not support Landlock, `Landlock.applyRuleset` throws an `UnsupportedOperationException`. The test fails because it only conditionally checks `Arch.current()` support but does not check or handle `Landlock.isSupported()`.
*   **Context & Proof:** The test execution log shows `java.util.concurrent.ExecutionException: java.lang.UnsupportedOperationException: Landlock is not supported on this kernel but FS rules were requested.` which originates from `handleUnsupportedLandlock`. Since tests are executed in a sandbox environment that lacks Landlock, this test deterministically fails, breaking the build.
*   **Cascading Risk Potential:** Medium. Breaks CI pipelines and test suites on environments lacking advanced kernel features.
*   **Recommendation:** Wrap the execution in an `Assumptions.assumeTrue(Landlock.isSupported())` or skip it natively. Wait, as an agent I cannot fix the source code, but the backlog must track this CI failure.

### 🔴 [Severity: MEDIUM]: `Landlock` getAccessMask missing ABI 4 Support (Net Capabilities)
*   **Dimension:** FFM ABI / OS Invariants
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt` (specifically `getAccessMask` and `getFullAccessMask`)
*   **Failure Hypothesis:** Linux Landlock ABI 4 introduced `LANDLOCK_ACCESS_NET_BIND_TCP` and `LANDLOCK_ACCESS_NET_CONNECT_TCP`. The `getAccessMask` and `getFullAccessMask` methods compute access flags for ABI versions up to ABI 5 (e.g. `if (abi >= ABI_V5) mask = mask or LANDLOCK_ACCESS_FS_IOCTL_DEV`), but they completely skip ABI 4 networking capabilities. If a user expects network containment via Landlock on an ABI 4+ kernel, they will not be contained.
*   **Context & Proof:** `Landlock.kt` defines `getAccessMask`. It checks `abi >= 2` (REFER), `abi >= ABI_V3` (TRUNCATE), and `abi >= ABI_V5` (IOCTL_DEV). There is no check for `abi >= 4` to append network capability masks. Although `createRuleset` checks `if (abi >= 4)` to expand the `rulesetAttr` size to include `handled_access_net`, the actual value written to `handled_access_net` is hardcoded to `0L`: `rulesetAttr.set(ValueLayout.JAVA_LONG, Layouts.LANDLOCK_RULESET_ATTR_NET_OFFSET, 0L)`. Thus, Landlock network containment is silently unsupported/disabled despite ABI 4+ sizing handling.
*   **Cascading Risk Potential:** Medium feature gap and potential security evasion if developers rely solely on Landlock for network isolation instead of Seccomp-BPF.
*   **Recommendation:** Document that Landlock ABI 4 network isolation is not supported and rely entirely on Seccomp-BPF for network rules, or implement the ABI 4 `handled_access_net` capability flags.

### 🔴 [Severity: MEDIUM]: `PureJavaBpfEngine` Thread State Synchronization
*   **Dimension:** Cascading Failure Analysis
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt` (specifically `installOnProcess` and `threadState`)
*   **Failure Hypothesis:** The `PureJavaBpfEngine` uses a `ThreadLocal` called `threadState` to track the installation progress (e.g. `PrivilegesLocked`, `FilterBuilt`, `SystemCallApplied`). When `installOnProcess` is called, it installs a global seccomp filter using the `TSYNC` flag, affecting all sibling threads. However, it only updates the `ThreadLocal` state of the *calling* thread.
*   **Context & Proof:** In `installInternal`, the code calls `threadState.set(SeccompInstallationState...)` sequentially. Since `threadState` is a `ThreadLocal`, sibling threads that were just subjected to the `TSYNC` seccomp filter will still evaluate `PureJavaBpfEngine.state` as `Uninitialized`. If any sibling thread later attempts to verify its installation state or perform operations that check `state`, it will falsely believe no filter is applied.
*   **Cascading Risk Potential:** Medium diagnostic and internal state inconsistency. The global OS state diverges from the JVM's thread-local state map.
*   **Recommendation:** Document this state divergence, or implement a global `processState` alongside `threadState` so that `installOnProcess` correctly signals global containment.

### 🔴 [Severity: MEDIUM]: Unhandled `TSYNC` edge cases during JIT classloading
*   **Dimension:** OS Invariants / Cascading Failure
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt` (specifically `installFilter`)
*   **Failure Hypothesis:** When `installOnProcess` calls `seccomp` with `SECCOMP_FILTER_FLAG_TSYNC`, the Linux kernel applies the filter to all sibling threads synchronously. If the JVM is heavily multithreaded and a background JIT compiler thread (C1/C2) is currently executing a blocked system call (e.g., `openat` for lazy classloading) exactly when `TSYNC` takes effect, the syscall might be abruptly interrupted or subsequently denied with `EPERM` when retried.
*   **Context & Proof:** `PureJavaBpfEngine.installInternal` locks privileges and applies the filter using `SECCOMP_FILTER_FLAG_TSYNC`. The kernel ensures atomicity of filter application, but the JVM provides no safety guarantee that background threads are not actively engaged in IO or network calls that are about to be denied. While `mazewall` documents JIT `mmap(PROT_EXEC)` deadlocks, it does not explicitly handle TOCTTOU race conditions where `TSYNC` cuts off actively running operations, leading to non-deterministic JIT aborts in production.
*   **Cascading Risk Potential:** Medium stability risk. Can cause random, hard-to-debug JVM crashes during process-wide filter installation in high-traffic applications.
*   **Recommendation:** Document the inherent risks of `TSYNC` concurrency in `SECURITY_CONSIDERATIONS.md` and recommend applying process-wide policies only during application initialization (e.g. `public static void main`) before extensive multithreading or JIT activity begins.

### 🔴 [Severity: LOW]: Inefficient Regex Compilation in `ContainmentViolationDetector`
*   **Dimension:** Performance & Efficiency
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/ContainmentViolationDetector.kt` (specifically `DENIED_PHRASES`)
*   **Failure Hypothesis:** The `ContainmentViolationDetector` stores `DENIED_PHRASES` as an array of strings and checks them using `DENIED_PHRASES.any { msg.contains(it, ignoreCase = true) }`. Under heavy load (e.g. iterative profiling loops or logging intercepted exceptions), this causes redundant string allocations and linear substring scans across all messages.
*   **Context & Proof:** `contains(it, ignoreCase = true)` dynamically converts both strings or handles case-insensitive scanning inefficiently on every invocation. Compiling a single `Regex` pattern (e.g. `Regex("Operation not permitted|Permission denied|refusé|verweigert|negado", RegexOption.IGNORE_CASE)`) would allow the regex engine to construct an optimized DFA/NFA state machine and evaluate the message in a single pass.
*   **Cascading Risk Potential:** Low performance overhead, but adds unnecessary garbage collection pressure and CPU cycles during high-frequency exception trapping in Tier A profiling.
*   **Recommendation:** Refactor `DENIED_PHRASES` into a compiled `Regex` for optimal performance.

### 📝 [NOTE]: Root `:test` task requires host Docker/Podman, not runnable inside dev container
**Context:** The root `:test` task (`ContainerizedTestRunner`) spawns a Testcontainer using Docker/Podman, which must be available on the host. Running `./gradlew build` from inside the dev container fails because `docker.sock`/`podman.sock` is not mounted inside. The correct inner-container verification commands are: `./gradlew :enforcer:integrationTest :profiler:integrationTest`. The full `./gradlew build` must be run from the host to trigger `ContainerizedTestRunner`.

### 🟡 [Severity: LOW]: KtLint parser fails on Kotlin 2.x named context parameters syntax
**Context:** To implement compile-time FFM Arena safety, the project uses Kotlin 2.x named context parameters (`context(arena: Arena)`). However, the KtLint Gradle plugin (`org.jlleitschuh.gradle.ktlint` version `14.2.0`) uses an older KtLint engine (even after upgrading to `1.3.1`) that crashes during the AST parsing phase when encountering this new language syntax. The issue affects check/format tasks across `:enforcer`, `:profiler`, and the shared test resources.
**Needed:** Currently bypassed by disabling the KtLint tasks (`enabled = false`) on projects utilizing context parameters. A permanent resolution requires upgrading the KtLint Gradle plugin or KtLint executable to a version that officially supports Kotlin 2.4/2.x context parameters grammar.

### 🔴 [Severity: MEDIUM]: Unhandled `IOCTL` fallbacks during legacy JVM syscall tracing
*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `io.mazewall.profiler.engine.ProfilerDaemon`
*   **Failure Hypothesis:** When tracing `IOCTL`, older kernels may pass unexpected data structures in the argument block due to architectural differences or internal kernel fallbacks. If the `ProfilerDaemon` attempts to read these structs from memory unconditionally, it may hit unmapped pages or receive structurally malformed data, leading to incomplete traces or Daemon crashes on specific kernel versions.
*   **Context & Proof:** The `ProfilerDaemon` intercepts syscalls via `USER_NOTIF`. For complex syscalls with pointer arguments (like `ioctl`), it reads the argument memory using `process_vm_readv`. However, standard `ioctl` arguments are highly polymorphic and depend heavily on the device and request code. Attempting to parse them generically without strict bounds checking or request-code verification can cause `process_vm_readv` to fail or read garbage.
*   **Cascading Risk Potential:** Medium diagnostic defect. Tracing applications that rely heavily on complex `ioctl` calls (e.g. specialized hardware communication or TTY manipulation) might produce garbled `BillOfBehavior` outputs or cause the Daemon to drop events.
*   **Recommendation:** Implement robust request-code filtering and structural bounds checking before attempting to read `ioctl` argument payloads in the Profiler Daemon.

### 🔴 [Severity: MEDIUM]: Potential Race Condition in Async IO Thread Shutdown
*   **Dimension:** Cascading Failure Analysis
*   **Target Area:** `io.mazewall.enforcer.ContainedExecutors`
*   **Failure Hypothesis:** If a wrapped `ExecutorService` is shut down while background tasks (like async I/O handlers) are still initializing their thread-local seccomp filters, the thread pool might aggressively terminate these threads. This can leave the `ContainerStateRegistry` out of sync, or worse, cause native resources (like allocated Arenas) to be leaked or improperly finalized.
*   **Context & Proof:** `ContainedExecutors` relies on `applyContainment()` wrapping each task. If `shutdownNow()` is called on the underlying executor, threads may be interrupted during the delicate FFM downcalls (e.g. `seccomp` or `prctl`). The JVM does not guarantee atomic execution of these FFM boundaries against thread interruptions.
*   **Cascading Risk Potential:** Medium stability risk. Could lead to memory leaks or JVM crashes if native Arenas are accessed after the thread is aggressively killed during shutdown sequences.
*   **Recommendation:** Document the need for graceful shutdown (`shutdown()` and `awaitTermination()`) when using `ContainedExecutors`, and explore adding explicit resource cleanup hooks that are serialized to thread interruptions.

### 🔴 [Severity: MEDIUM]: Uncaught Native Exceptions Escaping BPF Installation
*   **Dimension:** Cascading Failure Analysis
*   **Target Area:** `io.mazewall.seccomp.PureJavaBpfEngine`
*   **Failure Hypothesis:** If `process_vm_readv` or `seccomp` downcalls throw an unhandled JVM Error or Exception (e.g., `OutOfMemoryError` during Arena allocation, or a sudden FFM `IllegalArgumentException`), the `installInternal` method catches `Throwable` and blindly sets the thread state to `Failed(stepName, errno, e)`, but it might leave the process in a partially restricted state where `no_new_privs` is enabled but the filter is missing.
*   **Context & Proof:** `PureJavaBpfEngine.installInternal` calls `setNoNewPrivs()`, builds the filter, and installs it. If an exception occurs after `setNoNewPrivs()` but before `installFilter`, the process has permanently locked its privileges (cannot call `execve` with setuid) without actually applying the security policy. Subsequent attempts to retry or recover might fail.
*   **Cascading Risk Potential:** Medium application stability defect. Leaves the JVM in a non-deterministic state where native OS state does not match the intended policy, potentially causing confusing failures during later application phases.
*   **Recommendation:** Document the permanence of `setNoNewPrivs` and ensure `installInternal` allocates memory and parses the policy *before* invoking `prctl(PR_SET_NO_NEW_PRIVS)` to minimize the critical section where partial failure can occur.

### 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) during `seccomp` Filter Installation
*   **Dimension:** OS Invariants
*   **Target Area:** `io.mazewall.seccomp.PureJavaBpfEngine`
*   **Failure Hypothesis:** If the `seccomp` downcall in `installFilter` is interrupted by an asynchronous POSIX signal (e.g., a JVM profiling signal or timer tick), it may fail with `EINTR`. The current code does not retry the syscall on `EINTR` and immediately throws an `IllegalStateException`, aborting the installation.
*   **Context & Proof:** The `PureJavaBpfEngine.installFilter` method calls `LinuxNative.syscall(NativeConstants.SECCOMP_SET_MODE_FILTER, ...)`. The kernel can interrupt almost any blocking or slow system call with `EINTR`. If `seccomp` returns `EINTR`, `r3.returnValue` will not be `0`, and the code falls back to `prctl`, which might also fail or behave unexpectedly. The method lacks a robust `while (errno == EINTR)` retry loop.
*   **Cascading Risk Potential:** Medium stability risk. Spurious `EINTR` signals could cause non-deterministic failures when initializing the sandbox in heavily multi-threaded or profiled JVM environments.
*   **Recommendation:** Wrap the `seccomp` and `prctl` filter installation downcalls in a retry loop that specifically handles `EINTR`.

### ✅ [RESOLVED]: Unhandled Signal Interruptions (`EINTR`) in `poll` and `recvmsg` in `ProfilerDaemon`
**Status:** RESOLVED (June 2026)
**Target Area:** `io.mazewall.profiler.engine.ProfilerDaemon`
**Context:** The `ProfilerDaemon` uses a `poll` and `recvmsg` loop to multiplex and read incoming `USER_NOTIF` events. If an asynchronous signal interrupts these downcalls, they will fail with `EINTR`.
**Fix:** Added retry logic using the `recover` extension in `ProfilerDaemonEngine.kt` to handle `EINTR` specifically.

### 🔴 [Severity: LOW]: Suboptimal BPF `RET` instruction placement in `emitLinearScan`
*   **Dimension:** Performance & Efficiency
*   **Target Area:** `io.mazewall.BpfFilter` (specifically `emitLinearScan`)
*   **Failure Hypothesis:** The BPF `emitLinearScan` generates a sequence of checks like `JEQ syscall_nr -> RET action; JEQ syscall_nr_2 -> RET action`. If the policy has a default action of `ACT_ALLOW` (blacklist) and blocks a small number of syscalls (e.g. `EXECVE`), every single allowed system call must jump through the entire block list before reaching the final `RET ALLOW` instruction at the end of the filter.
*   **Context & Proof:** `emitLinearScan` iterates over blocked syscalls and adds checks. The default action is appended at the very end. This structure means the "fast path" (allowed syscalls) is actually the "slowest path" through the filter, requiring N evaluations. Since most system calls are allowed in a typical application, the kernel evaluates the maximum number of instructions for every single standard file or network operation.
*   **Cascading Risk Potential:** Low performance risk, but contributes to unnecessary CPU overhead per system call.
*   **Recommendation:** Optimize the BPF compiler. If the default action is `ALLOW`, invert the logic: use a binary search tree or jump tables within the BPF bytecode to reach the decision faster, or early-exit if the syscall number falls outside the blocked ranges.

### 🔴 [Severity: DX-FRICTION]: Missing Extensibility in Exception Message Parsing
*   **Dimension:** DX & API Ergonomics
*   **Target Area:** `io.mazewall.profiler.iterative.IterativeProfiler` and `io.mazewall.enforcer.ContainmentViolationDetector`
*   **Failure Hypothesis:** Different JVM languages, native wrappers, or custom `FileSystemProvider` implementations might throw exceptions containing localized error strings or unusual formatting when access is denied. The `DENIED_PHRASES` list in `ContainmentViolationDetector` is hardcoded.
*   **Context & Proof:** `ContainmentViolationDetector` uses a fixed `arrayOf` strings (e.g., `"Operation not permitted"`, `"refusé"`). The `IterativeProfiler` uses this exact array to identify exception boundaries. If a user's framework throws a custom wrapper containing "Blocked by sandbox", the violation is completely ignored.
*   **Cascading Risk Potential:** DX friction. Users in non-standard environments or using custom filesystem providers cannot use the Iterative Profiler.
*   **Recommendation:** Provide a public configuration hook in `IterativeProfiler` or `Policy` allowing developers to supply custom regexes or phrases for violation detection.

### 🔴 [Severity: MEDIUM]: Unhandled `O_CLOEXEC` Omission on Profiler Unix Sockets
*   **Dimension:** OS Invariants
*   **Target Area:** `io.mazewall.profiler.internal.ProfilerSocket`
*   **Failure Hypothesis:** The `ProfilerSocket` creates a `socket(AF_UNIX, SOCK_STREAM, 0)`. It does not apply the `O_CLOEXEC` (Close-on-Exec) flag. If the profiled JVM spawns a child process (e.g. via `ProcessBuilder`) while the profiler connection is open, the child process inherits the open socket file descriptor to the Profiler Daemon.
*   **Context & Proof:** `ProfilerSocket.kt` makes the raw Linux `socket` downcall. Because `SOCK_CLOEXEC` is not bitwise OR'd into the socket type, the descriptor remains open across `execve`. Although the Tier 2 policy might block `execve`, if a user allows `execve` (or uses Tier S process-wide profiling without blocking `execve`), child processes will unknowingly hold a reference to the daemon socket.
*   **Cascading Risk Potential:** Medium. File descriptor leak to untrusted child processes, potentially allowing children to write spoofed `USER_NOTIF` ACKs or keep the daemon connection alive indefinitely, preventing cleanup.
*   **Recommendation:** Always bitwise OR `NativeConstants.SOCK_CLOEXEC` into the `type` argument when calling `LinuxNative.socket`.

### 🔴 [Severity: MEDIUM]: Unhandled `O_PATH` Omission on Landlock Fallback Directories
*   **Dimension:** Security Privileges
*   **Target Area:** `io.mazewall.landlock.Landlock`
*   **Failure Hypothesis:** When `Landlock.addRule` falls back to opening a parent directory using `handleInitialOpenFailure`, it invokes `LinuxNative.getFileSystem().open(arena.allocateFrom(parentPath), flags)`. However, `flags` is `NativeConstants.O_PATH or NativeConstants.O_CLOEXEC or NativeConstants.O_NOFOLLOW`. If the parent directory is actually a symlink to another directory, `O_NOFOLLOW` will cause `open` to fail with `ELOOP`, rejecting the fallback completely and preventing Landlock from applying the rule.
*   **Context & Proof:** `Landlock.addRule` passes `O_NOFOLLOW` to prevent symlink traversal for the specific file rule. However, when falling back to a parent directory (e.g. `File(resolvedPath).parent`), the parent path might be an implicitly resolved system symlink (e.g. `/var/run` -> `/run`). If the fallback uses `O_NOFOLLOW`, the parent open fails, and the user's intended sandbox rule is entirely dropped.
*   **Cascading Risk Potential:** Medium feature failure. Can silently drop valid path rules if system paths involve intermediate directory symlinks.
*   **Recommendation:** When performing the directory fallback in `handleInitialOpenFailure`, remove the `O_NOFOLLOW` flag to allow the kernel to traverse to the real parent directory.

### 🔴 [Severity: LOW]: Memory Segment Lifetime Leak in Async Profiler Events
*   **Dimension:** Memory Lifetimes & Escapes
*   **Target Area:** `io.mazewall.profiler.engine.ProfilerSessionHandler`
*   **Failure Hypothesis:** The `ProfilerSessionHandler` receives events and creates detached FFM `MemorySegment` objects for trace elements. If these segments are passed to background logging threads or asynchronous channels without an explicit lifecycle scope (like an `Arena.ofConfined().use { ... }` block binding the entire trace lifecycle), the garbage collector must finalize the native memory, causing high GC pressure or native memory leaks under heavy profiling loads.
*   **Context & Proof:** `ProfilerDaemon` uses a persistent `Arena.ofShared()` for some operations, but `process_vm_readv` strings are copied into JVM `String` objects, avoiding direct memory segment escapes. However, if any internal structs (like `seccomp_data` slices) are accidentally retained by the `TraceEvent` objects, they would escape their confined arenas.
*   **Cascading Risk Potential:** Low. The current implementation aggressively converts native data to immutable Kotlin classes (`String`, `Syscall`), so segments don't escape. However, any future optimization attempting to zero-copy `TraceEvent` data could introduce critical memory safety bugs.
*   **Recommendation:** Document the strict requirement that all FFM `MemorySegment` data must be materialized into JVM heap objects before crossing the `TraceEvent` boundary into the compiler/logger.

### 🔴 [Severity: MEDIUM]: TOCTOU Vulnerability in `prctl` Argument Inspection
*   **Dimension:** TOCTOU & Concurrency
*   **Target Area:** `io.mazewall.BpfFilter` (specifically `allowUnsafePrctl` block)
*   **Failure Hypothesis:** The BPF filter inspects `args[0]` of the `prctl` system call (the `option` parameter). Since the `prctl` arguments are passed in registers, they cannot be modified by another thread *between* the BPF check and the kernel execution (preventing a standard memory TOCTOU). However, if an attacker uses an allowed `prctl` option (like `PR_SET_NAME`) but points the `arg2` pointer to a memory region concurrently mutated by a sibling thread, they might trigger kernel bugs or bypass intended name restrictions.
*   **Context & Proof:** `BpfFilter.kt` correctly inspects `args[0]` (the register value) which is immune to TOCTOU. `mazewall`'s threat model assumes `prctl` is unsafe primarily because of `PR_SET_SECCOMP` or `PR_SET_NO_NEW_PRIVS`. However, memory-pointer arguments in other `prctl` options (e.g. `PR_SET_MM`) are inherently vulnerable to TOCTOU if the attacker controls sibling threads.
*   **Cascading Risk Potential:** Medium. Restricting `prctl` to only safe options mitigates this, but `allowUnsafePrctl=true` completely opens the door to arbitrary kernel interactions.
*   **Recommendation:** Document that `allowUnsafePrctl` is extremely dangerous and inherently vulnerable to concurrent memory mutation attacks by sibling threads.

### 🔴 [Severity: LOW]: Overly Broad Catch Block in `ProfilerDaemon.reactorLoop`
*   **Dimension:** Cascading Failure Analysis
*   **Target Area:** `io.mazewall.profiler.engine.ProfilerDaemon`
*   **Failure Hypothesis:** The `reactorLoop` wraps the entire multiplexing process in a generic `try { ... } catch (e: Exception) { logger.log(Level.SEVERE, "Daemon loop error", e) }` block. If an unrecoverable FFM error (like `IllegalArgumentException` from a bad layout cast) or an `OutOfMemoryError` occurs, the loop swallows it, logs it, and continues executing. This can lead to a spinning loop of failures, 100% CPU utilization, and corrupted profiler state.
*   **Context & Proof:** Generic exception catching inside infinite daemon loops often hides critical system state corruption. If the daemon encounters a corrupted `USER_NOTIF` packet structure, it will crash processing that packet, catch the error, and immediately poll again, likely receiving the exact same corrupted packet or losing synchronization with the kernel queue.
*   **Cascading Risk Potential:** Low security risk but high stability risk for the profiler daemon itself.
*   **Recommendation:** Differentiate between recoverable I/O exceptions (like `IOException` on a dropped connection) and unrecoverable structural errors (like `IllegalArgumentException` or `IndexOutOfBoundsException`). The daemon should intentionally crash or disconnect the specific session on structural errors to prevent infinite error spinning.

### 🔴 [Severity: MEDIUM]: Unhandled Endianness in `process_vm_readv` Socket Message Tracing
*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `io.mazewall.profiler.engine.ProfilerDaemon`
*   **Failure Hypothesis:** When tracing `sendmsg` or `recvmsg`, the daemon reads `msghdr` and `sockaddr_un` structures directly from the tracee's memory. If the tracee and the profiler daemon have mismatched endianness (e.g. running under QEMU emulation or cross-architecture containers), reading raw integer fields like `sun_family` or `msg_namelen` directly into native memory segments will result in reversed bytes and catastrophic path resolution failures.
*   **Context & Proof:** The Linux `process_vm_readv` syscall copies raw bytes. `ProfilerDaemon` uses `ValueLayout.JAVA_SHORT` and `ValueLayout.JAVA_INT` to read these values. FFM `ValueLayout` defaults to the host byte order. While `mazewall` currently only supports Linux x86_64 and aarch64 (both typically little-endian), `sun_family` is often evaluated as a network byte order or host byte order depending on the socket domain. If any structural parsing assumes host-byte order but the struct is packed or network-byte-ordered, it will fail.
*   **Cascading Risk Potential:** Medium feature failure. Can break profiler socket address resolution on specific edge-case architectures.
*   **Recommendation:** Explicitly define the byte order for FFM layouts reading C structs (e.g., `.withOrder(ByteOrder.nativeOrder())`), and double-check `sun_family` endianness rules.

### 🔴 [Severity: MEDIUM]: Missing BPF Instruction Limit Validation in `newSockFProg`
*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `io.mazewall.seccomp.PureJavaBpfEngine`
*   **Failure Hypothesis:** If the generated seccomp filter contains more than the maximum permitted BPF instructions, downcasting the filter array size to a 16-bit short during `sock_fprog` structure allocation will cause a silent size truncation, leading to invalid/incomplete filter loading.
*   **Context & Proof:** `Layouts.SOCK_FPROG` defines `len` as `JAVA_SHORT`. `MemoryImpl.newSockFProg` assigns `filters.size.toShort()` to `len`. The Linux kernel `bpf_prog_alloc` limits seccomp BPF programs to 4096 instructions (`BPF_MAXINSNS`). While 4096 fits within a 16-bit short, the `mazewall` JVM layer currently does not explicitly validate `filters.size <= 4096` before allocating the struct. If a malicious or auto-generated policy creates 5000 instructions, `toShort()` casts it, and the kernel receives a truncated filter, breaking security guarantees.
*   **Cascading Risk Potential:** Medium security defect. Can lead to silently incomplete sandbox policies if developers generate massive rulesets.
*   **Recommendation:** Add an explicit `require(filters.size <= 4096) { "BPF program exceeds kernel maximum instruction limit" }` in `newSockFProg` or `BpfFilter.build`.

### 🔴 [Severity: MEDIUM]: TOCTOU in Path Normalization under Multi-Threaded I/O
*   **Dimension:** TOCTOU & Concurrency
*   **Target Area:** `io.mazewall.SbobParser`
*   **Failure Hypothesis:** A profiled application operates on a directory symlink that is constantly being updated by a sibling thread or background process (e.g. `/app/current -> /app/v1` switching to `/app/v2`). If the Iterative Profiler records the resolved target (`/app/v1/file`), but by the time the `SbobParser` generates the Landlock policy the symlink points to `/app/v2`, the generated policy will hardcode `/app/v1`, denying access to the application in production.
*   **Context & Proof:** Landlock's absolute path resolution binds strictly to the inode at `addRule` time. Dynamic symlinks or active directory swaps (like Capistrano deployments) break statical Landlock profiling.
*   **Cascading Risk Potential:** Medium DX friction. Applications using atomic directory swapping will fail under strict Landlock profiles.
*   **Recommendation:** Document the incompatibility of Landlock rules with atomic directory symlink swapping, and advise users to profile and restrict the parent umbrella directory (`/app/`) rather than the dynamic target.

### 🔴 [Severity: MEDIUM]: Unhandled Signal Mask Inheritance in `ContainedExecutors`
*   **Dimension:** OS Invariants
*   **Target Area:** `io.mazewall.enforcer.ContainedExecutors`
*   **Failure Hypothesis:** Standard JVM thread pools do not reset POSIX signal masks (`sigprocmask`) or alternate signal stacks (`sigaltstack`) when reusing threads. If a previous uncontained task executing native code (JNI/FFM) blocked `SIGSYS` or corrupted the signal stack, a subsequently contained task on that same carrier thread will not receive `SIGSYS` when it violates the seccomp policy, defeating `ACT_TRAP` actions.
*   **Context & Proof:** `ContainedExecutors.wrap` applies the seccomp filter but relies on the kernel delivering `SIGSYS` if the user configures `ACT_TRAP`. If the thread's signal mask currently blocks `SIGSYS` (which is highly unusual for pure Java, but possible if native libraries are used), the kernel might leave the thread in an unkillable zombie state or delay the signal indefinitely.
*   **Cascading Risk Potential:** Medium. `mazewall` currently defaults to `ACT_ERRNO`, avoiding `SIGSYS` handling entirely. But if developers use `ACT_TRAP` for debugging or specific integrations, signal masking will break containment reporting.
*   **Recommendation:** Document that `ACT_TRAP` is unreliable in environments where native libraries might modify thread signal masks.

### 🔴 [Severity: MEDIUM]: TOCTOU in `USER_NOTIF` Argument Dereferencing
*   **Dimension:** TOCTOU & Concurrency
*   **Target Area:** `io.mazewall.profiler.engine.ProfilerDaemon`
*   **Failure Hypothesis:** When the Profiler Daemon receives a `USER_NOTIF` for a syscall like `openat`, it uses `process_vm_readv` to read the path string from the tracee's memory. Because the tracee thread is stopped but other sibling threads in the same process are still running, a malicious or poorly synchronized sibling thread can rewrite the path string in memory *after* the BPF filter has triggered the notification but *before* the Profiler reads it.
*   **Context & Proof:** The Linux `SECCOMP_RET_USER_NOTIF` mechanism stops the thread making the system call. The daemon reads the arguments from the tracee's memory. Since memory is shared across threads, a TOCTOU (Time of Check to Time of Use) is possible. The kernel will eventually execute the syscall with the *current* memory contents, which might differ from what the profiler logged.
*   **Cascading Risk Potential:** Medium profiling inaccuracy. If the path changes, the `BillOfBehavior` might contain the pre-mutation or post-mutation path, leading to incorrect policies.
*   **Recommendation:** Document that the `USER_NOTIF` Tier S Profiler is vulnerable to concurrent memory mutation (TOCTOU) and is strictly intended for profiling trusted/benign workloads, not for intercepting malicious evasion attempts.

### 🔴 [Severity: MEDIUM]: Missing Return Value Check for `SECCOMP_NOTIF_RESP` ACK
*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `io.mazewall.profiler.engine.ProfilerSessionHandler`
*   **Failure Hypothesis:** When the daemon replies to the kernel via `ioctl(SECCOMP_IOCTL_NOTIF_SEND)`, it might fail (e.g. if the tracee thread died prematurely, receiving `ENOENT`). If the daemon does not check the return value, it might leak internal state or assume the event was successfully handled, leading to desynchronization.
*   **Context & Proof:** `ProfilerSessionHandler.kt` calls `LinuxNative.ioctl(fd, NativeConstants.SECCOMP_IOCTL_NOTIF_SEND, respSegment.address())`. The return value is a `SyscallResult`. If `returnValue < 0`, the kernel rejected the response.
*   **Cascading Risk Potential:** Low to Medium. Usually the kernel just drops the response if the thread is gone, but failing to handle errors can mask deeper protocol issues.
*   **Recommendation:** Log a warning if the `NOTIF_SEND` ioctl returns an error.

### 🔴 [Severity: HIGH]: SbobParser Production Crashes due to Syntactic Subpath Pruning of Unresolved/Symlinked Paths
**Target:** `io.mazewall.SbobParser` (specifically `pruneSubpaths`)
**Failure Hypothesis:** SbobParser's subpath pruning operates purely syntactically without resolving symlinks. If a staging environment contains a symlinked directory and a real nested directory, pruning will discard the nested path. When the parsed policy is applied, the symlink is rejected, and because the nested path was pruned, the entire tree is left blocked, causing production application crashes.
**Context & Proof:** In `SbobParser.kt`, `pruneSubpaths` syntactically normalizes and sorts path strings. If a profiled workload accessed both `/var/log` (a symlink) and `/var/log/app` (a real directory), the SBoB JSON lists both. `pruneSubpaths` prunes `/var/log/app` because it syntactically starts with `/var/log`. In production, when `Landlock.addRule` is invoked for `/var/log`, `O_NOFOLLOW` triggers a symlink rejection `ELOOP`, so the rule is skipped and no filesystem rule is added. Since `/var/log/app` was pruned, no rule is added for `/var/log/app` either. The application is completely blocked from accessing `/var/log/app` and crashes.
**Cascading Risk Potential:** High usability and stability risk. Causes deterministic, hard-to-debug runtime crashes in production environments when deploying SBoB policies across varying file systems or symlinks.
**Needed:** SbobParser's subpath pruning must be aware of symlink and directory boundaries, or `addRule` must not prune paths that could fail to resolve. A safer solution is to have SbobParser retain all paths and let `Landlock.applyRuleset` perform dynamic pruning after resolving canonical/real paths in the actual environment, or avoid pruning paths syntactically if they could be symlinks.

### 🟢 [WONTFIX]: Permanent thread pool contamination, classloader leaks, and state pollution via un-cleared `ThreadLocal` variables
**Target:** `/enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt` and `ContainerStateRegistry.kt`
**Context:** Standard JVM thread pools reuse worker threads. Since the sandbox tracks thread-scoped seccomp and Landlock states using `ThreadLocal` registers but never clears them when a wrapped task finishes, the thread-scoped security state leaks permanently into subsequent tasks on the same thread, causing unexpected `IllegalStateException` throws or ClassLoader memory leaks during redeploys.
**Resolution (WONTFIX):** See resolution for `ContainedExecutors Thread-Local State Persistence and Poisoning` below. Clearing `ThreadLocals` breaks critical deduplication and violates immutable OS sandbox semantics. Users must manage thread pool lifecycles directly (via `shutdown()`) for restricted tasks.

### 🟢 [WONTFIX]: `ContainedExecutors` Thread-Local State Persistence and Poisoning
**Target:** `io.mazewall.enforcer.ContainedExecutors.kt` and `ContainerStateRegistry.kt`
**Context:** `ContainedExecutorWrapper` calls `applyContainment()` on every task execution, but it never clears the tracking `ThreadLocals`. Because worker threads are reused in a pool, any subsequent task scheduled on the same OS thread will inherit the `mazewall` state of the previous task, even if it's supposed to be uncontained or have a different policy. The original proposal was to implement a `try-finally` cleanup to clear all registers in `ContainerStateRegistry` when a contained task completes to prevent ClassLoader memory leaks on application redeploys.
**Resolution (WONTFIX):** Seccomp filters and Landlock domains are permanent and immutable for the lifetime of an OS thread. They cannot be removed or reverted. If we clear the `ThreadLocal` JVM tracking state when a task completes:
1. The JVM loses track of the permanent OS restrictions.
2. The next task on the same thread will evaluate an "empty" JVM state and redundantly re-apply the identical Landlock domain and Seccomp filters.
3. This completely breaks deduplication. If a thread processes 16 tasks, it hits the Landlock `E2BIG` stacked domain limit and crashes. If it processes 32 tasks, it hits the Seccomp stacked filter limit and crashes.
4. If a task with a *different* policy runs, the OS will silently enforce the intersection of both policies, leading to obfuscated `EPERM` crashes. Keeping the `ThreadLocal` intact allows the JVM to fail-fast with an `IllegalStateException` ("Cannot expand Landlock filesystem permissions on an already restricted thread"), properly warning the user that they are violating the immutable OS sandbox semantics.

**The Correct Solution:** Developers MUST NOT share thread pools between differently-sandboxed tasks. Restricted tasks must run on a dedicated `ExecutorService` that is shut down (`executor.shutdown()`) when the application/container stops. Shutting down the executor kills the OS threads, inherently cleaning up both the ClassLoader references and the permanent OS sandboxes without any memory leaks.

**The Correct Solution:** Developers MUST NOT share thread pools between differently-sandboxed tasks. Restricted tasks must run on a dedicated `ExecutorService` that is shut down (`executor.shutdown()`) when the application/container stops. Shutting down the executor kills the OS threads, inherently cleaning up both the ClassLoader references and the permanent OS sandboxes without any memory leaks.

## Resolved & WONTFIX Historical Backlog

### 🟢 [RESOLVED]: Temporal State Mutation Leak in `ContainerStateRegistry` via Thread-Local Delegates
**Target:** `io.mazewall.enforcer.ContainerStateRegistry`
**Context:** `ContainerStateRegistry` exposed multiple properties backed by a custom `ThreadLocalDelegate` alongside process-wide state variables under a single interface.
**Needed:** Split `ContainerStateRegistry` into two distinct, strongly-typed components: `ProcessStateRegistry` and `ThreadStateRegistry`. Enforce explicit lifecycle bounds and sanitization routines on the `ThreadStateRegistry` when task execution terminates.
**Resolved:** The registry was split into `ProcessStateRegistry` and `ThreadStateRegistry`. Additionally, `ThreadStateRegistry` now includes an explicit `sanitize()` method that purposefully throws an `UnsupportedOperationException`, documenting why clearing ThreadLocals violates immutable OS sandbox semantics and thus explicitly enforcing the lifecycle bound as "OS thread lifetime".

### 🟢 [RESOLVED]: Nested Seccomp Stacking Security Containment Bypass on already-blocked Syscalls
**Target:** `io.mazewall.enforcer.FilterInstallationPlanner`
**Failure Hypothesis:** When a user stacked policy contains a more restrictive or more severe action for a syscall that is already blocked by a previously applied policy, the planner incorrectly skips the filter installation under a false optimization path because it only checks if the syscall is "blocked".
**Context & Proof:** `FilterInstallationPlanner.calculateNewFilter` calculates `newBlocks = blockedInPolicy - state.currentlyBlocked`. Any syscall with an action priority > ACT_ALLOW is in `blockedInPolicy`. If a syscall (e.g. `EXECVE`) was blocked by Policy 1 with a lenient action (like `ACT_LOG`), `currentlyBlocked` already contains it. When Policy 2 is nestedly stacked to block `EXECVE` with a severe action (like `ACT_KILL_PROCESS`), `newBlocks` evaluates to empty because it was already blocked. As a result, the optimizer sets `needsNewFilter` to `false`, silently skipping the installation of the second filter. The thread continues executing with only the weaker `ACT_LOG` filter in place, completely bypassing the intended `ACT_KILL_PROCESS` containment.
**Cascading Risk Potential:** High security containment bypass. A stacked policy that is intended to restrict thread capabilities further is ignored, causing RCE/compromised code to execute under weaker sandbox rules than designed.
**Fix:** Modified `currentlyBlocked` to track `Map<Syscall, SeccompAction>` rather than `Set<Syscall>`. In `calculateNewFilter`, `newBlocks` now includes any syscall in the new policy that maps to a *higher priority (more restrictive) action* than the currently installed action for that syscall.

### 🟢 [RESOLVED]: `installOnProcess` process-wide seccomp synchronization (TSYNC) fails deterministically on standard JVMs
**Target:** `io.mazewall.seccomp.PureJavaBpfEngine`
**Failure Hypothesis:** Process-wide seccomp installation via `TSYNC` requires `no_new_privs` to be enabled on all threads in the thread group. In standard JVMs, background threads are spawned before `no_new_privs` is set, causing TSYNC to fail with `EACCES` under non-root configurations. The current exception error message is also highly misleading.
**Context & Proof:** The Linux kernel requires `no_new_privs` to be set on all sibling threads in the thread group for `SECCOMP_FILTER_FLAG_TSYNC` to succeed. When the JVM starts, GC threads, JIT threads, and VM helper threads are spawned at startup. In `PureJavaBpfEngine.installInternal`, the main thread calls `setNoNewPrivs()`, which only sets the flag on the *calling* thread. Pre-existing background threads do not get it. When `TSYNC` is attempted, the kernel returns `EACCES` (-13). The method catches this failure and throws an exception claiming "Your kernel may be too old to support SECCOMP_FILTER_FLAG_TSYNC", which is factually incorrect and misleads operators.
**Resolved:** Clarified the exception message to clearly state that `TSYNC` failed due to missing `no_new_privs` on sibling threads, advising operators to run with OCI/Kubernetes `allowPrivilegeEscalation: false` or pre-set `no_new_privs` using an external launcher. Additionally, added a platform diagnostics API (`Platform.diagnose()`) to verify the `no_new_privs` state in-app.

### 🟢 [RESOLVED]: Seccomp Filter Bypass via `pkey_mprotect`
**Target:** `io.mazewall.BpfFilter`, `io.mazewall.core.Syscall`, `io.mazewall.seccomp.MmapProtectionTest`
**Failure Hypothesis:** The BPF filter correctly intercepts `mprotect` and `mmap` calls to prevent `PROT_EXEC` via argument inspection (checking `args[2]`). However, it misses modern Linux memory protection variants, specifically `pkey_mprotect` (`SYS_pkey_mprotect` / 329 on AMD64). Since this syscall is not explicitly hooked for argument inspection and may be allowed under loose policies or fallback behavior, an attacker who can call `pkey_mprotect` can mark memory as executable (`PROT_EXEC`), completely bypassing the Seccomp `NO_EXEC` protections designed to stop dynamic shellcode generation.
**Context & Proof:** `pkey_mprotect` takes the same `prot` parameter as `mprotect` but also takes a `pkey`. The current `BpfFilter.kt` only restricts `arch.mmap` and `arch.mprotect`. In `Syscall.kt`, there is no representation of `pkey_mprotect`. Thus, if `pkey_mprotect` is not explicitly blocked or handled via argument inspection like `mprotect`, it will fall back to the default action. Under `Policy.NO_EXEC`, `pkey_mprotect` isn't explicitly blocked, so it would fall to `ACT_ALLOW`, allowing unrestricted `PROT_EXEC` usage. This has been proven via `bypass_pkey.c` where `mprotect` with `PROT_EXEC` is blocked but `pkey_mprotect` with `PROT_EXEC` succeeds in bypassing.
**Vulnerability Chain Potential:** Very high. If an attacker achieves arbitrary code execution (or memory corruption) they can just use `pkey_mprotect` instead of `mprotect` to bypass JIT / dynamic shellcode protections in the sandbox.
**Fix:** Added `PKEY_MPROTECT` to `Syscall`, mapped its number per architecture, and in `BpfFilter.buildFromActions` added it to the same argument inspection block that currently restricts `PROT_EXEC` in `mprotect` and `mmap`. Added tests to `MmapProtectionTest.kt` to guarantee blocking.
**Failure Hypothesis:** A thread pool processing multiple tasks with a whitelist policy (where `defaultAction != ACT_ALLOW`) will unconditionally attach a new, redundant Seccomp BPF filter on every task execution, eventually crashing the thread when the filter limit is reached.

### ✅ [RESOLVED]: `allowMmapExec=false` silently kills JIT on process-wide DENY_LIST policies
**Target:** `Policy.NO_NETWORK` KDoc, `containment_design.md §3f`
**Context:** `allowMmapExec` defaults to `false` on ALL policies, including DENY_LIST presets like `NO_NETWORK`. When installed process-wide via `installOnProcess()`, the BPF filter applies to JIT compiler background threads, blocking their `mmap(PROT_EXEC)` code-cache allocation calls. Result: fatal JVM abort (`os::commit_memory failed; error='Operation not permitted'`). Discovered by removing `-Xint` from `IsolatedProcessTester` — the flag had been masking this crash in integration tests.
**Fix:** Added `### JIT Compiler Warning` to `Policy.NO_NETWORK` KDoc documenting the footgun and the correct workaround (`Policy.builder().base(NO_NETWORK).allowMmapExec().build()`). Added `§3f` to `containment_design.md` with the full failure pattern. Fixed `testNioStability()` in `ProcessContainmentTest` to use the correct derived policy.

### ✅ [RESOLVED]: ALLOW_LIST policies that block `openat` require targeted class pre-loading
**Target:** `AllowListTest.preWarm()`, `containment_design.md §3g`
**Context:** When `defaultAction = ACT_ERRNO` (ALLOW_LIST), `openat` is blocked unless explicitly in the allow set. Classes referenced by `PureJavaBpfEngine` immediately after filter installation (specifically `SeccompInstallationState$Failed`) are loaded lazily via `openat`. After the filter blocks `openat`, these classes can no longer be loaded → `NoClassDefFoundError`. The old `JitWarmup` attempted to solve this globally but was fragile and non-deterministic. The correct fix is targeted: explicitly touch the exact class graph that will be used post-installation, in the specific test/component that uses the restrictive ALLOW_LIST policy.
**Fix:** Extended `AllowListTest.preWarm()` to touch all `SeccompInstallationState` subclasses before the filter is installed. Added `§3g` to `containment_design.md` documenting the rule and its scope.

### ✅ [RESOLVED]: Landlock Symlink Rejection Bypass via Canonicalization
**Context:** The Landlock documentation states that rules explicitly use `O_NOFOLLOW` to reject symlinks and prevent attackers from redirecting path rules. However, `addRule` called `SandboxedPath.of` which used `toRealPath()`, silently bypassing this protection.
**Fix:** Switched to syntactic normalization (`Paths.get(path).toAbsolutePath().normalize()`) in `SandboxedPath.of`. This defers symlink resolution to the kernel, which then correctly rejects links via `O_NOFOLLOW`.


### 🔴 [Severity: MEDIUM]: Missing `BpfProgram<Status>` and `BpfLabel` Type-Safety
*   **Dimension:** Compile-time Safety & Type Integrity
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/BpfProgram.kt`
*   **Observation:** While the `BpfBuilder` uses a sealed state machine, the resulting `BpfProgram` lacks a `Status` phantom type (Verified/Unverified). Furthermore, `BpfBuilder` still uses `String` for labels, which are prone to typos and lack compile-time existence guarantees.
*   **Needed:** Transition `BpfProgram` to `BpfProgram<Status>` and introduce `BpfLabel` value class tokens for the DSL.

### 🔴 [Severity: HIGH]: `IterativeProfiler` Logic Errors (Confirmed)
*   **Dimension:** State Machine Integrity & Failure Propagation
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt`
*   **Confirmed Proof:**
    1.  **Relative Paths:** `resolveAbsolutePath` explicitly returns `null` if the path does not start with `/`, causing a transition to `Failed`.
    2.  **Path Truncation:** `resolveAbsolutePath` backward scan stops at the first whitespace, truncating paths with spaces.
    3.  **Infinite Loop:** `updatePolicyForViolation` uses `path.startsWith(it.value)` which fails for disjoint prefix matches, leading to repeated denials of the same path.
    4.  **Context Loss:** Spawning a new `Thread` for each iteration loses `ThreadLocal` and MDC context, making diagnostics difficult.
*   **Needed:** Refactor `IterativeProfiler` to use a proof-of-progress state machine and proper path normalization.

### 🔴 [Severity: MEDIUM]: Loom Carrier Poisoning Bypass in `PureJavaBpfEngine`
*   **Dimension:** OS Invariants & Native Safety
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt`
*   **Observation:** The mandatory assertion `check(!Thread.currentThread().isVirtual)` is present in `ContainedExecutors` but absent in the public `PureJavaBpfEngine.install` methods. An advanced user could bypass the high-level API and poison carrier threads by calling the engine directly from a virtual thread.
*   **Needed:** Move the virtual thread check into `SeccompInstallationState.Uninitialized.lockPrivileges()` to ensure it is always enforced.

### 🟡 [Severity: LOW]: Landlock Excessive Capability Leak on `ENOENT`
*   **Dimension:** OS Invariants & Native Safety
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt`
*   **Observation:** When a path does not exist, `addRule` falls back to the parent directory. This grants access to the *entire* directory when the user only intended to allow a specific (future) file.
*   **Needed:** Implement `O_CREAT` awareness or document this broad fallback as a known limitation.

### 🔴 [Severity: MEDIUM]: Cascading Failure in `verifyInstallation` when stacking over a restrictive `prctl` filter
*   **Dimension:** Cascading Failure / OS Invariants
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt`
*   **Failure Hypothesis:** When a thread has an existing Seccomp filter that restricts `prctl` (e.g. `ACT_ERRNO`), installing a *subsequent* permissive policy that explicitly allows `prctl` will cause a deterministic crash during the installation verification phase.
*   **Context & Proof:** `verifyInstallation(definition: PolicyDefinition<*>)` checks if `prctl` is allowed by examining only the *incoming* `definition` (`definition.syscallActions[Syscall.PRCTL] ?: definition.defaultAction`). If allowed, it executes `LinuxNative.process.prctl(PrctlCommand.GetSeccomp)`. However, seccomp filters stack in the Linux kernel; the *most restrictive* action across all installed filters is applied. The incoming policy might allow `prctl`, but the *already installed* policy will intercept and block it, causing `r5.getOrThrow` to throw an exception and fail the installation of the second filter entirely.
*   **Cascading Risk Potential:** Medium. Prevents the installation of otherwise valid subsequent policies (stacking) if the initial policy was restrictive of `prctl`. This is a state tracking desynchronization between the thread's cumulative OS state and the stateless validation in `verifyInstallation`.
*   **Recommendation:** `verifyInstallation` should check the combined `ContainerState` from `ThreadStateRegistry` rather than just the incoming `PolicyDefinition`, ensuring it correctly respects the cumulative restrictions on `prctl` before attempting the native syscall.

### 🔴 [Severity: LOW]: Suboptimal BPF `RET` instruction placement in `emitLinearScan`
*   **Dimension:** Performance / Macro-Architecture
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/BpfFilter.kt`
*   **Failure Hypothesis:** The BPF linear scan compiler does not deduplicate identical return values. If many syscalls map to the same restrictive action, the resulting bytecode size will bloat unnecessarily, potentially hitting the Linux 4096 instruction limit.
*   **Context & Proof:** In `BpfFilter.emitLinearScan`, the code loops over all `syscallActions` and executes `builder.expect(nr) { ret(nativeAction) }`. This injects a jump instruction and a discrete `RET` instruction for every single mapped syscall. If 50 syscalls are mapped to `ACT_ERRNO`, it emits 50 separate `RET` instructions instead of jumping to a single shared `RET` block for `ACT_ERRNO`. This wastes BPF instruction slots and creates suboptimal CPU instruction cache usage inside the kernel.
*   **Cascading Risk Potential:** Low, but impacts bytecode efficiency. In scenarios with massive `DENY_LIST` policies, this bloat could push the BPF program size closer to the strict `BPF_MAXINSNS` limit (4096), causing `seccomp(2)` to inexplicably fail with `EINVAL` during installation.
*   **Recommendation:** Group the `syscallActions` by `nativeAction`. Iterate through the groups, emit jump chains for the syscall numbers, and place a single `RET <action>` instruction at the end of each chain using shared labels.

### 🔴 [Severity: HIGH]: `SeccompInstallationState` Partial Failure Leaves Thread Unprivileged but Uncontained
*   **Dimension:** Cascading Failure Analysis (The Systems View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt` and `SeccompInstallationState.kt`
*   **Failure Hypothesis:** If an exception (like OutOfMemoryError, or a virtual machine error) occurs after `setNoNewPrivs` but before the BPF filter is successfully applied, the OS thread will have `no_new_privs` permanently set, but no containment filter will be active.
*   **Context & Proof:** In `PureJavaBpfEngine.installInternal`, `val locked = uninitialized.lockPrivileges()` sets `PR_SET_NO_NEW_PRIVS`. Then, `nativeScope { val built = locked.buildFilter(this, policy) }` attempts to allocate native memory for the BPF instructions. If this native allocation fails (e.g., due to memory pressure or FFM limits), an exception is thrown. The method catches the exception and updates the state to `Failed`. However, `PR_SET_NO_NEW_PRIVS` cannot be unset. The thread is now returned to the pool (if running via `ContainedExecutors.wrap`), silently dropping privileges for all future tasks on this carrier thread without actually applying the requested security policy.
*   **Cascading Risk Potential:** High. Thread pool contamination. Future tasks executing on this thread will fail unexpectedly if they legitimately require privilege escalation (e.g., `execve` with setuid), leading to non-deterministic failures across the application.
*   **Recommendation:** Pre-allocate the `MemorySegment` and build the `SockFProg` struct *before* calling `setNoNewPrivs()`. This ensures that all memory and layout calculations succeed before making the irreversible kernel state change.

### 🔴 [Severity: MEDIUM]: Native Memory Leak in `ContainedExecutors.wrap` under High Concurrency
*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt`
*   **Failure Hypothesis:** The BPF program memory allocated via `nativeScope` might leak or be prematurely freed if the `seccomp` syscall is interrupted or delayed by a GC pause.
*   **Context & Proof:** `PureJavaBpfEngine.installInternal` uses `nativeScope { val built = locked.buildFilter(this, policy); built.applyFilter(arch, useTsync) }`. The `nativeScope` (typically `Arena.ofConfined()`) guarantees memory is freed when the block exits. If `seccomp` is called, the kernel copies the BPF instructions into kernel space. However, if `ContainedExecutors.wrap` submits thousands of tasks concurrently, and each task triggers a nested compilation/installation that throws an exception inside the `nativeScope`, the JVM might struggle to clean up the confined arenas promptly if the exceptions are caught and swallowed by the executor.
*   **Cascading Risk Potential:** Medium. In a highly dynamic environment, this could lead to native memory exhaustion.
*   **Recommendation:** Verify the `nativeScope` implementation correctly bounds the arena lifetime even when exceptions are thrown (e.g., ensure it uses `try-finally` internally), and consider caching the compiled `MemorySegment` for identical policies.

### 🔴 [Severity: HIGH]: `SeccompInstallationState` Partial Failure Leaves Thread Unprivileged but Uncontained
*   **Dimension:** Cascading Failure Analysis (The Systems View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt` and `SeccompInstallationState.kt`
*   **Failure Hypothesis:** If an exception (like OutOfMemoryError, or a virtual machine error) occurs after `setNoNewPrivs` but before the BPF filter is successfully applied, the OS thread will have `no_new_privs` permanently set, but no containment filter will be active.
*   **Context & Proof:** In `PureJavaBpfEngine.installInternal`, `val locked = uninitialized.lockPrivileges()` sets `PR_SET_NO_NEW_PRIVS`. Then, `nativeScope { val built = locked.buildFilter(this, policy) }` attempts to allocate native memory for the BPF instructions. If this native allocation fails (e.g., due to memory pressure or FFM limits), an exception is thrown. The method catches the exception and updates the state to `Failed`. However, `PR_SET_NO_NEW_PRIVS` cannot be unset. The thread is now returned to the pool (if running via `ContainedExecutors.wrap`), silently dropping privileges for all future tasks on this carrier thread without actually applying the requested security policy.
*   **Cascading Risk Potential:** High. Thread pool contamination. Future tasks executing on this thread will fail unexpectedly if they legitimately require privilege escalation (e.g., `execve` with setuid), leading to non-deterministic failures across the application.
*   **Recommendation:** Pre-allocate the `MemorySegment` and build the `SockFProg` struct *before* calling `setNoNewPrivs()`. This ensures that all memory and layout calculations succeed before making the irreversible kernel state change.

### 🔴 [Severity: MEDIUM]: Native Memory Leak in `ContainedExecutors.wrap` under High Concurrency
*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt`
*   **Failure Hypothesis:** The BPF program memory allocated via `nativeScope` might leak or be prematurely freed if the `seccomp` syscall is interrupted or delayed by a GC pause.
*   **Context & Proof:** `PureJavaBpfEngine.installInternal` uses `nativeScope { val built = locked.buildFilter(this, policy); built.applyFilter(arch, useTsync) }`. The `nativeScope` (typically `Arena.ofConfined()`) guarantees memory is freed when the block exits. If `seccomp` is called, the kernel copies the BPF instructions into kernel space. However, if `ContainedExecutors.wrap` submits thousands of tasks concurrently, and each task triggers a nested compilation/installation that throws an exception inside the `nativeScope`, the JVM might struggle to clean up the confined arenas promptly if the exceptions are caught and swallowed by the executor.
*   **Cascading Risk Potential:** Medium. In a highly dynamic environment, this could lead to native memory exhaustion.
*   **Recommendation:** Verify the `nativeScope` implementation correctly bounds the arena lifetime even when exceptions are thrown (e.g., ensure it uses `try-finally` internally), and consider caching the compiled `MemorySegment` for identical policies.

### 🔴 [Severity: MEDIUM]: Missing Thread-Safety in `ProcessStateRegistry` Updates
*   **Dimension:** Cascading Failure Analysis (The Systems View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/ProcessStateRegistry.kt` and `ContainedExecutors.kt`
*   **Failure Hypothesis:** Concurrent calls to `ContainedExecutors.installOnProcess` or `updateProcessState` might lead to lost updates or race conditions when merging policies if `ProcessStateRegistry.update` is not atomic.
*   **Context & Proof:** In `ContainedExecutors.kt`, `updateProcessState` calls `ProcessStateRegistry.update { current -> current.withNewSeccompPolicy(...) }`. If `ProcessStateRegistry` uses a simple volatile variable without compare-and-swap (CAS) or synchronization, concurrent process-wide installations could overwrite each other's state, leading to an inconsistent view of the global containment policy.
*   **Cascading Risk Potential:** Medium. In a multithreaded environment, simultaneous installations could result in a policy state that does not reflect the actual OS-level stacked filters, causing `verifyInstallation` or future filter planning to fail.
*   **Recommendation:** Ensure `ProcessStateRegistry.update` uses a synchronized block or `AtomicReference.updateAndGet` to guarantee atomicity of state transitions.

### 🔴 [Severity: HIGH]: Incomplete Architecture Verification in BPF Compiler
*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/BpfProgram.kt`
*   **Failure Hypothesis:** The BPF program builder checks the architecture (`checkArch(arch)`), but it might not handle the audit architecture value correctly for aarch64 (ARM64), leading to bypassed filters on non-x86 platforms.
*   **Context & Proof:** The BPF filter needs to strictly validate the `AUDIT_ARCH` from `seccomp_data`. If the mapping for `Arch.AARCH64` yields an incorrect constant, or if the filter fails to reject mismatched architectures with `SECCOMP_RET_KILL`, a thread could bypass the filter by using an emulation layer or `execve`ing a binary of a different architecture (e.g., x86_32 on x86_64).
*   **Cascading Risk Potential:** High. Architecture mismatch is a classic seccomp bypass vector.
*   **Recommendation:** Audit the `checkArch` emission logic to ensure it correctly loads the `arch` field from `seccomp_data` (offset 4) and jumps to a strict `KILL` action if it does not match the expected native architecture.

### 🔴 [Severity: PERFORMANCE]: Inefficient ThreadLocal usage in `ThreadStateRegistry`
*   **Dimension:** Performance & Efficiency
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/ThreadStateRegistry.kt`
*   **Failure Hypothesis:** Frequent access to `ThreadStateRegistry.state` during high-throughput executor task wrapping adds measurable overhead due to `ThreadLocal` lookups.
*   **Context & Proof:** `ContainedExecutors.resolveCurrentState()` accesses both `ThreadStateRegistry.state` and `ProcessStateRegistry.state`. In a tight loop (e.g., thousands of small tasks submitted to a wrapped executor), the continuous merging of Thread and Process states dominates the overhead.
*   **Cascading Risk Potential:** Performance. Increases the latency of task submission in `ContainedExecutors.wrap`.
*   **Recommendation:** Cache the merged `ContainerState` or optimize the `ThreadLocal` access patterns. Consider using Loom's `ScopedValue` when available.


### 🔴 [Severity: MEDIUM]: Missing Support for `O_PATH` and `O_CLOEXEC` in `Landlock` fallback
*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt`
*   **Failure Hypothesis:** When `Landlock.addRule` falls back to opening a parent directory, it uses hardcoded flags that might omit `O_PATH` or `O_CLOEXEC`, causing unnecessary file descriptor leaks or rejecting valid symlinks.
*   **Context & Proof:** The issue was highlighted in the backlog script output: "Unhandled `O_PATH` Omission on Landlock Fallback Directories". If `open` is called without `O_PATH`, it might attempt to fully open a device file or FIFO instead of just getting a file descriptor for Landlock routing, potentially causing hangs.
*   **Cascading Risk Potential:** Medium. File descriptor leaks or blocked application initialization.
*   **Recommendation:** Verify the `open` flags in `Landlock.kt` explicitly include `O_PATH | O_CLOEXEC`.

### 🔴 [Severity: DX-FRICTION]: Opaque Exceptions on Landlock Initialization Failure
*   **Dimension:** Developer Experience (DX) & API Ergonomics
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt`
*   **Failure Hypothesis:** If Landlock fails to initialize due to a missing kernel capability or an older ABI version, the exception message might be opaque (e.g., just returning an `errno`), confusing developers about whether the system is supported.
*   **Context & Proof:** If `landlock_create_ruleset` returns `ENOSYS` or `EOPNOTSUPP`, a generic `IllegalStateException` or `RuntimeException` without context about Kernel requirements hurts the DX.
*   **Cascading Risk Potential:** DX Friction. Developers might abandon the library if they cannot quickly diagnose environment issues.
*   **Recommendation:** Wrap Landlock native call failures in a specific `UnsupportedKernelFeatureException` with clear guidance on required Linux kernel versions.

### 🔴 [Severity: LOW]: Memory Segment Lifetime Leak in Async Profiler Events
*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/SessionHandler.kt`
*   **Failure Hypothesis:** If a profiler session handles an async event (e.g., `SECCOMP_NOTIF`), the `MemorySegment` allocated for the response might not be properly closed if an exception occurs during the reply transmission.
*   **Context & Proof:** Unmanaged or un-`use`d Arenas during network/socket failures could lead to native memory leaks in long-running profiler daemons.
*   **Cascading Risk Potential:** Low. The profiler is usually short-lived, but could exhaust memory in long-running CI/CD environments.
*   **Recommendation:** Ensure all temporary `MemorySegment` allocations within the profiler event loop are strictly scoped within `nativeScope { ... }` or `try-with-resources`.


### 🔴 [Severity: MEDIUM]: Unhandled Signal Mask Inheritance in `ContainedExecutors`
*   **Dimension:** OS Invariants / Cascading Failure
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt`
*   **Failure Hypothesis:** When a new thread is spawned by a wrapped `ExecutorService`, it inherits the signal mask of its parent. If the seccomp filter restricts `rt_sigprocmask`, the new thread might be permanently trapped with blocked signals.
*   **Context & Proof:** `ContainedExecutors.wrap` applies policies to threads dynamically. If a policy blocks `rt_sigprocmask` (or `ACT_ERRNO`), and the application relies on handling signals (e.g., `SIGTERM`), the thread will be unable to unblock them. This interacts poorly with Loom or certain async frameworks that manipulate signal masks for IO interruption.
*   **Cascading Risk Potential:** Medium. Could lead to unkillable threads or missed interruptions (e.g., `Thread.interrupt()` failing to wake up a blocked IO call if the underlying signal is blocked and cannot be manipulated).
*   **Recommendation:** Document that policies should ideally allow `rt_sigprocmask` and `rt_sigaction` for standard JVM thread management, and verify that `BpfFilter.getJvmCriticalNrs` explicitly includes them.

### 🔴 [Severity: DX-FRICTION]: Missing Extensibility in Exception Message Parsing
*   **Dimension:** Developer Experience (DX) & API Ergonomics
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/ContainmentViolationDetector.kt`
*   **Failure Hypothesis:** The `ContainmentViolationDetector` is a singleton with a static list of matchers. Users cannot easily add custom detection logic for third-party libraries that wrap IOExceptions with non-standard messages.
*   **Context & Proof:** While `registerMatcher` exists, its interaction with global state (`MATCHERS`) might be problematic in complex, multi-tenant classloaders (e.g., OSGi or certain App Servers).
*   **Cascading Risk Potential:** DX Friction. Users might not be able to rely on `isContainmentViolation` for their specific use cases if they use localized or heavily wrapped exceptions.
*   **Recommendation:** Allow passing an optional configuration or extending the detector via a ServiceLoader pattern for better modularity.


### 🔴 [Severity: MEDIUM]: TOCTOU in `USER_NOTIF` Argument Dereferencing
*   **Dimension:** Cascading Failure Analysis (The Systems View)
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/strace/StraceProfiler.kt`
*   **Failure Hypothesis:** When the profiler daemon receives a `SECCOMP_NOTIF` event, it reads memory from the target process using `process_vm_readv`. A malicious or concurrent thread in the target process could modify the memory arguments (e.g., a file path string) *after* the profiler reads it but *before* the kernel executes the syscall.
*   **Context & Proof:** This is a classic Time-of-Check to Time-of-Use (TOCTOU) vulnerability inherent in `ptrace` or `USER_NOTIF` architectures where arguments are passed by reference (pointers). The profiler might log or allow an action based on `path_A`, but the kernel might actually execute the syscall on `path_B`.
*   **Cascading Risk Potential:** Medium (Profiler Context). The profiler is designed for generating policies, not strict enforcement, so the security impact is lower. However, it leads to inaccurate profiles being generated if the application has race conditions in its syscall arguments.
*   **Recommendation:** Document this inherent limitation of `USER_NOTIF` profiling. Mention that `Landlock` is the preferred mechanism for robust, race-free filesystem restriction since it evaluates paths in the kernel space safely.

### 🔴 [Severity: MEDIUM]: Unhandled Endianness in `process_vm_readv` Socket Message Tracing
*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/strace/StraceProfiler.kt`
*   **Failure Hypothesis:** When reading multi-byte structures (like `sockaddr` or complex `io_uring` SQEs) from the target process memory via `process_vm_readv`, the profiler might misinterpret the data if the target process is running with a different endianness or if the C-struct layout assumes a specific byte order not explicitly handled by Java's `ByteBuffer` defaults.
*   **Context & Proof:** FFM `ValueLayout`s default to native byte order. If the profiling logic manually parses bytes (e.g., extracting IP addresses from a `sockaddr_in`), it must ensure the network byte order (Big Endian) vs host byte order (Little Endian) conversions are strictly observed.
*   **Cascading Risk Potential:** Medium. Could result in corrupted or completely incorrect IP addresses/ports being logged in the profiler output, leading to flawed network policies.
*   **Recommendation:** Audit all manual struct parsing in the profiler (especially networking structs) to ensure explicit `ByteOrder` handling.

### 🔴 [Severity: MEDIUM]: Missing Return Value Check for `SECCOMP_NOTIF_RESP` ACK
*   **Dimension:** OS Invariants / Cascading Failure
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/strace/StraceProfiler.kt`
*   **Failure Hypothesis:** After processing a notification, the profiler sends a response back to the kernel via `ioctl(SECCOMP_IOCTL_NOTIF_SEND)`. If this `ioctl` fails (e.g., because the target thread was killed or interrupted in the meantime), the profiler might not handle the error gracefully, potentially leaking state or crashing the daemon loop.
*   **Context & Proof:** The `USER_NOTIF` documentation states that the target thread can be interrupted or killed before the response is sent. The `ioctl` will return `ENOENT` in this case.
*   **Cascading Risk Potential:** Medium. A crashed profiler daemon prevents further profiling of the application.
*   **Recommendation:** Explicitly catch and ignore `ENOENT` errors during the `NOTIF_SEND` `ioctl`, as they represent expected, normal race conditions during thread termination.


### 🔴 [Severity: MEDIUM]: Missing BPF Instruction Limit Validation in `newSockFProg`
*   **Dimension:** Performance & Efficiency / Macro-Architecture
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/memory/SockFProg.kt` (or wherever `newSockFProg` is implemented)
*   **Failure Hypothesis:** The kernel strictly limits BPF programs to 4096 instructions (`BPF_MAXINSNS`). If `PolicyDefinition.compile()` generates a filter exceeding this limit, `newSockFProg` might allocate it successfully, but the `seccomp` syscall will fail mysteriously with `EINVAL`.
*   **Context & Proof:** While there is a test checking this, there might not be explicit runtime validation before attempting the syscall. A complex policy with hundreds of file paths or network addresses could exceed the limit.
*   **Cascading Risk Potential:** Medium. `EINVAL` from seccomp is notoriously hard to debug.
*   **Recommendation:** Add an explicit check in `buildFilter` or `newSockFProg` to throw a descriptive `IllegalArgumentException` if the instruction count exceeds `BPF_MAXINSNS` (4096).

### 🔴 [Severity: MEDIUM]: Unhandled `O_CLOEXEC` Omission on Profiler Unix Sockets
*   **Dimension:** Cascading Failure Analysis (The Systems View)
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSocket.kt` (or similar)
*   **Failure Hypothesis:** If the profiler daemon opens UNIX sockets or files without `O_CLOEXEC`, these file descriptors could be inherited by child processes spawned by the profiled application.
*   **Context & Proof:** This could cause the profiler socket to remain open even if the daemon shuts down, or allow a malicious child process to interfere with the profiling session.
*   **Cascading Risk Potential:** Medium. File descriptor leaks and potential IPC interception.
*   **Recommendation:** Ensure all internal profiler sockets and file descriptors are explicitly opened with `O_CLOEXEC`.

### 🔴 [Severity: MEDIUM]: TOCTOU in Path Normalization under Multi-Threaded I/O
*   **Dimension:** Cascading Failure Analysis (The Systems View)
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/compiler/SbobParser.kt`
*   **Failure Hypothesis:** The `SbobParser` attempts to prune redundant paths (e.g., if `/opt/app` is allowed, `/opt/app/config` is redundant). However, if paths involve symlinks that change concurrently, the static string-based pruning might be incorrect.
*   **Context & Proof:** If `/opt/app/config` is initially a normal directory, it is pruned as redundant. If an attacker/concurrent process changes it to a symlink pointing to `/etc`, the policy generated by `SbobParser` might inadvertently allow access to `/etc` if it uses naive path matching.
*   **Cascading Risk Potential:** Medium. Policy generation could be flawed in highly dynamic environments.
*   **Recommendation:** The `SbobParser` should ideally rely on canonical paths resolved by the kernel (e.g., via `realpath`) or clearly document that its static pruning assumes a stable filesystem layout.


### 🔴 [Severity: MEDIUM]: Uncaught Native Exceptions Escaping BPF Installation
*   **Dimension:** Micro-Implementation & FFM ABI Rigor
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt`
*   **Failure Hypothesis:** The `installInternal` method catches `Throwable`, but `nativeScope` or underlying FFM calls might throw non-standard errors (e.g., `LinkageError` if a native symbol is suddenly unresolved on an unsupported glibc version) that should perhaps not be caught indiscriminately, or should be wrapped in a more specific containment failure exception.
*   **Context & Proof:** Catching generic `Throwable` masks potentially critical JVM errors like `OutOfMemoryError` or `StackOverflowError`, wrapping them in `SeccompInstallationState.Failed`. While preventing a raw crash during installation is good, continuing application execution after an OOM might be dangerous if the application assumes the security boundary is up.
*   **Cascading Risk Potential:** Medium. Running an application in an inconsistent state after a critical JVM error.
*   **Recommendation:** Refine the catch block to specifically handle expected exceptions (e.g., `IllegalStateException`, `UnsupportedOperationException`, `IOException`) and let fatal errors (`Error`) propagate, or at least log them as FATAL before updating the state.

### 🔴 [Severity: HIGH]: `SbobParser` Production Crashes due to Syntactic Subpath Pruning of Unresolved/Symlinked Paths
*   **Dimension:** Cascading Failure Analysis (The Systems View)
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/compiler/SbobParser.kt`
*   **Failure Hypothesis:** The `SbobParser` aggregates file paths. If a path contains unresolved components (`.`, `..`) or symlinks, simple string-prefix matching can incorrectly classify one path as a subset of another.
*   **Context & Proof:** Consider `allowedFsReadPaths`. If the profiler observed access to `/opt/app/../etc/passwd` and `/opt/app`, string prefix logic might prune `/opt/app/../etc/passwd` because it starts with `/opt/app`. The resulting policy would only allow `/opt/app`, and when the application tries to access the `passwd` file, it will be denied.
*   **Cascading Risk Potential:** High (Policy Generation). Leads to incomplete policies that cause application crashes in production.
*   **Recommendation:** `SbobParser` must strictly perform `Path.normalize()` and ideally `toRealPath()` before doing prefix comparisons to ensure accurate hierarchy evaluation.

### 🔴 [Severity: LOW]: Overly Broad Catch Block in `ProfilerDaemon.reactorLoop`
*   **Dimension:** Code Maintainability & Engineering Standards
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemon.kt`
*   **Failure Hypothesis:** The main event loop of the profiler daemon might have a catch-all block that suppresses critical interrupts or unexpected structural failures.
*   **Context & Proof:** If `reactorLoop` catches `Exception` and just logs it, it might inadvertently catch `InterruptedException` without restoring the interrupt status, causing the daemon to hang during shutdown requests.
*   **Cascading Risk Potential:** Low. The daemon might need to be forcefully killed (`SIGKILL`) instead of shutting down cleanly.
*   **Recommendation:** Explicitly handle `InterruptedException` (by restoring the interrupt status and exiting the loop) and `ClosedByInterruptException` separately from general IO errors.


### 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) during `seccomp` Filter Installation
*   **Dimension:** OS Invariants / Cascading Failure
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt`
*   **Failure Hypothesis:** The `seccomp` syscall itself might return `EINTR` if a signal is delivered to the thread precisely during the kernel's filter installation phase.
*   **Context & Proof:** `LinuxNative.syscall` is used to invoke `seccomp`. While filter installation is generally fast, if `EINTR` occurs, the installation fails. The current code does not retry the `seccomp` syscall on `EINTR`.
*   **Cascading Risk Potential:** Medium. In a highly active system with many signals (e.g., a JVM handling many async IO events or timers), `ContainedExecutors.wrap` might randomly fail with `IllegalStateException` due to `EINTR`.
*   **Recommendation:** Wrap the `seccomp` syscall (and `prctl` fallback) in an explicit `while (errno == EINTR)` retry loop, as is standard practice for robust Linux system programming.

### 🔴 [Severity: MEDIUM]: Unhandled `IOCTL` fallbacks during legacy JVM syscall tracing
*   **Dimension:** Macro-Architecture & OS Invariants
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemon.kt`
*   **Failure Hypothesis:** The profiler traces syscalls using `USER_NOTIF`. If a JVM performs an `ioctl` on a terminal or specialized device file, the BPF filter might intercept it. However, the profiler might not understand the specific `ioctl` structure to extract meaningful paths or context.
*   **Context & Proof:** `ioctl` arguments are highly dependent on the specific command. If the profiler attempts to read the argument as a string pointer (like it does for `open`), it might read random memory, causing a segmentation fault in the target process or reading garbage data.
*   **Cascading Risk Potential:** Medium. Could lead to garbage data in profiling logs or target process crashes if the profiler attempts to mutate the argument.
*   **Recommendation:** Ensure the BPF filter for the profiler either explicitly ignores `ioctl` (allowing it to pass through) or the `ProfilerDaemon` correctly identifies it as a generic, opaque operation without attempting deep pointer dereferencing.

### 🔴 [Severity: MEDIUM]: Potential Race Condition in Async IO Thread Shutdown
*   **Dimension:** Cascading Failure Analysis (The Systems View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt`
*   **Failure Hypothesis:** When a `ContainedExecutorWrapper` is shut down (`shutdown()`), it delegates to the underlying executor. If tasks are still in the queue, they will be executed. If the global `ProcessStateRegistry` is modified concurrently during this shutdown phase, the late-running tasks might pick up an inconsistent state.
*   **Context & Proof:** The wrapper relies on dynamically checking `ThreadStateRegistry` and `ProcessStateRegistry`. If a task starts executing exactly as the application is tearing down or changing global policies, the state resolution might interleave unpredictably.
*   **Cascading Risk Potential:** Medium. Non-deterministic behavior during application shutdown.
*   **Recommendation:** Ensure `resolveCurrentState` is robust against concurrent modifications, or document that modifying global policies while executors are shutting down is unsupported.


### 🔴 [Severity: MEDIUM]: Missing `BpfProgram<Status>` and `BpfLabel` Type-Safety
*   **Dimension:** Verification via Types & Compiler Features
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/BpfProgram.kt`
*   **Failure Hypothesis:** The `BpfProgram.builder()` uses a builder pattern, but it might lack compile-time guarantees (phantom types) to ensure that `checkArch` is called *before* `loadSyscallNr`, or that `build()` is only called when the program is in a complete state.
*   **Context & Proof:** If a developer modifies `BpfFilter.kt` and accidentally reorders the builder calls, the resulting BPF program might be structurally invalid (e.g., trying to load arguments before checking the architecture), leading to kernel rejection (`EINVAL`) only at runtime.
*   **Cascading Risk Potential:** Medium. Increases the risk of regressions during refactoring.
*   **Recommendation:** Leverage Kotlin's type system (e.g., Phantom Types or a Type-State pattern) to enforce the builder sequence at compile time, matching the architectural constraints outlined in the design documents.


### 🔴 [Severity: CRITICAL]: Race in Asynchronous / Fire-and-Forget Profiler Event Delivery
*   **Dimension:** Micro-Implementation & State Machine Invariants
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionHandler.kt`, `io.mazewall.profiler.internal.ProfilerTraceListener.kt`
*   **Failure Hypothesis:** Removing the synchronous handshake protocol (`WAIT_FOR_ACK`) in the profiler to send events in a "fire-and-forget" manner allows the tracee thread to return from kernel space and resume execution *before* the JVM listener thread has finished reading the trace event and calling `Thread.getStackTrace()`. This results in either empty stack profiles (because the thread is no longer running in the expected call path) or race conditions where events are lost or associated with wrong call frames.
*   **Context & Proof:** During refactoring, the removal of the `WAIT_FOR_ACK` loop caused integration tests verifying stack trace capture to fail consistently, as `bob.stackProfile` became empty.
*   **Recommendation:** Strictly enforce the synchronous `WAIT_FOR_ACK` protocol inside the daemon's session loop (`ProfilerSessionHandler.processNotification`) and release the tracee thread only after the listener thread has written the `PROTOCOL_ACK_BYTE` back to the socket. Wrap the listener's ACK sending code in a `finally` block to prevent tracee starvation.


### 🔴 [Severity: HIGH]: Process-Wide Classloader Deadlock on Profiler Result / State Types
*   **Dimension:** JVM / OS Contention & Classloading Invariants
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt`
*   **Failure Hypothesis:** Under process-wide profiling (`processWide = true`), all JVM threads are intercepted by seccomp `USER_NOTIF`. If classes required by the JVM listener thread (e.g., `ProfilingResult`, `TraceListenerState` subclasses) are loaded lazily, the listener thread will trigger class loading. This class loading will attempt to acquire the JVM ClassLoader monitor. If a tracee thread currently holds that monitor and is blocked in kernel space waiting for the listener to process its event, the listener thread will block indefinitely waiting for the ClassLoader monitor. This causes a circular deadlock.
*   **Context & Proof:** Integration tests for process-wide profiling threw `NoClassDefFoundError` or hung indefinitely when classes like `ProfilingResult` or `TraceListenerState$ReadingHeader` were accessed during profiling but not warmed up beforehand.
*   **Recommendation:** Maintain a robust, static class loading warmup block in `Profiler.kt` that instantiates and calls methods on all core state/result classes (`ProfilingResult`, `BobCompiler`, `TraceListenerState` subclasses) before installing seccomp filters.


### 🔴 [Severity: CRITICAL]: Confused Deputy / Time-of-Check to Time-of-Use (TOCTOU) via Path Modification
*   **Dimension:** Vulnerability Chaining & Concurrency (The Sandbox View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
*   **Failure Hypothesis:** The supervisor daemon reads a string argument (path) from the target process memory using `SupervisorProcessMemoryReader`, validates it with the JVM, and if allowed, directly delegates the `open()` call or `connect()` call from the supervisor itself using that path, handing back the resulting FD via seccomp inject FD.
*   **Context & Proof:** The `handleInjectFd` method opens the file `pathStr` (which was read *previously* by `processNotification` or `readAndHandleJvmResponse`) using `openFileInSupervisor` and injects the resulting file descriptor into the tracee. However, `pathStr` was read from the tracee's memory address space asynchronously. Between the time the memory was read and validated by the JVM, and the time the `SupervisorDaemon` executes `openFileInSupervisor()`, another thread in the tracee could mutate the memory string to point to a restricted file (e.g., from `/tmp/allowed` to `/etc/shadow`). The supervisor will then blindly open `/etc/shadow` and inject the FD back to the tracee.
*   **Recommendation:** Do not use string paths for FD injection when there is an untrusted shared memory boundary. Instead of delegating the `open()` to the supervisor, the supervisor should reply with `SECCOMP_USER_NOTIF_FLAG_CONTINUE` if authorized, letting the kernel perform the syscall safely in the tracee's context using the *current* state of the memory.


### 🔴 [Severity: CRITICAL]: TOCTOU / Pointer Re-targeting via `sockaddrBytes` Mutation during Connect Validation
*   **Dimension:** Vulnerability Chaining & Concurrency (The Sandbox View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
*   **Failure Hypothesis:** Similar to the path-based TOCTOU, the supervisor reads `sockaddrBytes` from the target process, validates it in the JVM, and then uses `connectSocketInSupervisor` to perform the `connect()` syscall from the supervisor process.
*   **Context & Proof:** `connectSocketInSupervisor` uses the `sockaddrBytes` array that was read from the tracee's memory earlier. An attacker in the tracee could swap the memory contents of the `sockaddr` struct (e.g., changing the IP from a harmless destination to an internal, protected service on loopback) between the time the supervisor reads it and the time the supervisor performs the `connect`. The supervisor, acting as a confused deputy, connects to the malicious destination and returns the connected socket FD to the tracee.
*   **Recommendation:** Stop injecting FDs for `connect()` and `open()` based on user-space copies. If the scoping policy authorizes the action, simply use `SECCOMP_USER_NOTIF_FLAG_CONTINUE` so the kernel safely evaluates the syscall in the tracee using the final memory state.


### 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) during Supervisor IPC socket communication
*   **Dimension:** Micro-Implementation & State Machine Invariants
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
*   **Failure Hypothesis:** The JVM validation loop reads from and writes to the supervisor socket. System calls over the socket can be interrupted by signals (`EINTR`).
*   **Context & Proof:** In `SupervisorSessionHandler.kt`, `LinuxNative.poll`, `LinuxNative.memory.write`, etc. are used. The `readAndHandleJvmResponse` and `sendSeccompError`/`sendSeccompContinue` functions do not properly loop on `EINTR` when calling `write` or `ioctl`. If a signal is received during the `ioctl(SECCOMP_IOCTL_NOTIF_SEND)` call, it may fail with `EINTR`, leaving the tracee suspended forever as the notification is never correctly answered.
*   **Recommendation:** Wrap the `ioctl` calls for `SECCOMP_IOCTL_NOTIF_SEND` and `SECCOMP_IOCTL_NOTIF_ADDFD` in an explicit retry loop that checks `if (errno == EINTR) continue`.


### 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) during Supervisor Initialization
*   **Dimension:** Micro-Implementation & State Machine Invariants
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSeccompNotifInstaller.kt`
*   **Failure Hypothesis:** Sending the socket file descriptors over `SCM_RIGHTS` can be interrupted by signals.
*   **Context & Proof:** `SupervisorSocketUtils.sendDescriptor` relies on `sendmsg`, which can fail with `EINTR`. If `EINTR` occurs while `SupervisorSeccompNotifInstaller` is trying to pass the seccomp listener FD to the supervisor daemon, the daemon will not receive the FD, but the JVM might proceed or throw an error, leading to an inconsistent state or unmonitored tracee.
*   **Recommendation:** Wrap `sendmsg` inside `SupervisorSocketUtils.sendDescriptor` in a `while (errno == EINTR)` retry loop.


### 🔴 [Severity: MEDIUM]: Asynchronous Supervisor socket reads timeout failure handling
*   **Dimension:** Verification via Types & Compiler Features
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
*   **Failure Hypothesis:** The `readAndHandleJvmResponse` waits up to 1 second (`POLL_TIMEOUT_MS`) for the JVM to validate the stack trace.
*   **Context & Proof:** If the JVM `JvmStackInspector.inspect` or `scopingPolicy.authorize` takes more than `POLL_TIMEOUT_MS` (e.g., due to garbage collection pauses or complex policy evaluation), `poll` times out. The `SupervisorSessionHandler` logs a severe error and sends an `EPERM` error via `sendSeccompError` to the tracee. Later, when the JVM finally finishes and sends the response, the supervisor receives an unexpected response or ignores it, breaking the synchronization protocol.
*   **Recommendation:** Remove the arbitrary timeout for the JVM validation step. The daemon should wait indefinitely (or loop on poll) for the JVM to respond. If the JVM hangs, the tracee should hang as well. Introducing timeouts at the IPC boundary leads to desynchronization and potential subsequent failure in tracking future system calls.


### 🔴 [Severity: CRITICAL]: Classloader Deadlock in JVM Validation Listener
*   **Dimension:** JVM / OS Contention & Classloading Invariants
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorInstaller.kt`
*   **Failure Hypothesis:** The JVM validation listener runs on a dedicated daemon thread and evaluates `StacktraceScopingPolicy`. During evaluation, it might trigger the classloader.
*   **Context & Proof:** If `scopingPolicy.authorize` executes and lazily loads classes (e.g., custom policy classes, string utilities), it acquires the JVM ClassLoader lock. If the tracee thread that triggered the syscall was holding the ClassLoader lock (because the syscall was an `open()` inside a classloading sequence that wasn't caught by the JDK fast-path), the validation listener will block waiting for the ClassLoader lock, while the tracee thread is blocked in kernel space waiting for the seccomp response. This results in a permanent process-wide deadlock.
*   **Recommendation:** Force eager classloading of all classes required by `JVMValidationListener`, `JvmStackInspector`, and `StacktraceScopingPolicy` before installing the seccomp filter on the thread, similar to the profiler warmup. Also, ensure the custom scoping policies are strictly verified not to perform arbitrary classloading.


### 🔴 [Severity: LOW]: Incomplete FFM Architecture Isolation
*   **Dimension:** Architectural Patterns Compliance (The Integrity View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorInstaller.kt`
*   **Failure Hypothesis:** FFM (`java.lang.foreign`) MemorySegments and Arenas are bleeding outside the `io.mazewall.ffi` boundary.
*   **Context & Proof:** `JVMValidationListener.start` and `runValidationReactor` in `SupervisorInstaller.kt` directly use `Arena.ofShared()` and manipulate memory allocation logic for the `SupervisorResponseSegment`. According to `docs/internals/architectural_map.md#7-core-architectural-paradigms--patterns`, all raw memory/FFM manipulation must be isolated to `io.mazewall.ffi`.
*   **Recommendation:** Move the raw `Arena` and `SupervisorResponseSegment` lifecycle management into a dedicated class inside the `io.mazewall.ffi` package, exposing a safe, higher-level interface to the `enforcer.supervisor` package.

### 🔴 [Severity: LOW]: Suboptimal Gradle Build Configuration for CI/CD
*   **Target Area:** `.github/workflows/ci.yml` and `build.gradle.kts`
*   **Hypothesis:** The GitHub Actions workflow explicitly disables parallel execution and configuration caching for most Gradle tasks, overriding optimal defaults in `gradle.properties`.
*   **Context & Proof:** In `.github/workflows/ci.yml`, steps like `run: ./gradlew build -x jacocoTestCoverageVerification --no-parallel` and publishing steps use `--no-parallel` and `--no-configuration-cache`. This drastically increases CI execution time compared to a parallelized, configuration-cached build.
*   **Recommendation:** Remove `--no-parallel` globally in CI. Selectively re-enable configuration caching (e.g., removing `--no-configuration-cache`) where possible. Investigate if `dependencyCheckAnalyze` and the publishing tasks can be updated to support the configuration cache.

### 🔴 [Severity: LOW]: CI Podman Container Caching Missing
*   **Target Area:** `scripts/run_containerized_tests.sh` and `.github/workflows/ci.yml`
*   **Hypothesis:** The `mazewall-test-runner` Podman image is built synchronously on every CI pipeline run without leveraging external layer caching (e.g., `--cache-from`), causing unnecessary delays.
*   **Context & Proof:** In `scripts/run_containerized_tests.sh`, the command `podman build -t mazewall-test-runner -f infra/dev/Containerfile .` executes without any cache-related flags. In GitHub Actions, since the runner environment is ephemeral, this forces a full re-download of packages and JDK distributions defined in `Containerfile` on every single PR and push.
*   **Recommendation:** Implement Podman/Buildah layer caching in GitHub Actions. Alternatively, use GitHub Container Registry (GHCR) to push/pull a baseline test runner image or use actions like `docker/setup-buildx-action` (if switching back to Docker) to cache intermediate layers effectively.

### 🔴 [Severity: LOW]: Redundant JaCoCo Test Coverage Verification in CI
*   **Target Area:** `.github/workflows/ci.yml` and `build.gradle.kts`
*   **Hypothesis:** The CI workflow executes the Jacoco verification phase separately, breaking standard task dependency chains and adding unnecessary overhead.
*   **Context & Proof:** The CI runs `./gradlew build -x jacocoTestCoverageVerification` and then, two steps later, `./gradlew jacocoTestCoverageVerification`. Because integration tests are run in between, it attempts to aggregate execution data. However, Gradle's task graph (`build.gradle.kts` defines `check` depending on `jacocoTestCoverageVerification`) is already designed to handle this sequentially. Manually orchestrating this with `-x` is brittle and inefficient.
*   **Recommendation:** Refactor the CI workflow to execute a single `./gradlew check` that natively encapsulates unit testing, integration testing (perhaps via a composite task or proper test source set separation rather than a shell script wrapper), and jacoco verification in a single, parallelizable Gradle invocation.

### ✅ [RESOLVED]: Inefficient DependencyCheck Configuration in CI
*   **Status:** RESOLVED (June 2026)
*   **Target Area:** `.github/workflows/ci.yml`
*   **Context & Proof:** The OWASP Dependency-Check plugin is executed without configuration caching, significantly increasing configuration phase time.
*   **Fix:** Removed the `--no-configuration-cache` flag from the `dependencyCheckAnalyze` step in `ci.yml` now that version `10.0.4` fully supports the configuration cache.

### ✅ [RESOLVED]: Gradle Configuration Avoidance Breakage via JitPack Shim
*   **Status:** RESOLVED (June 2026)
*   **Target Area:** `build.gradle.kts`
*   **Context & Proof:** The use of `tasks.whenTaskAdded` disabled Gradle's Task Configuration Avoidance, forcing eager configuration of all tasks during the configuration phase, severely degrading IDE sync and build start times.
*   **Fix:** Replaced `tasks.whenTaskAdded` with the lazy API equivalent: `tasks.matching { it.name == "listDeps" }.configureEach { ... }`. This preserves Task Configuration Avoidance while still injecting the configurations property for JitPack's `listDeps` task.

### ✅ [RESOLVED]: Unhandled Signal Interruptions (`EINTR`) during Supervisor `process_vm_readv` Socket Message Tracing
*   **Status:** RESOLVED (June 2026)
*   **Dimension:** Vulnerability Chaining & Concurrency (The Sandbox View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/memory/SupervisorProcessMemoryReader.kt`
*   **Context & Proof:** The `process_vm_readv` syscall can be interrupted by a signal, failing with `EINTR`. If this happens, the method incorrectly returns `null` instead of retrying.
*   **Fix:** Wrapped the `processVmReadv` call inside `SupervisorProcessMemoryReader.readBytes` in a retry loop on `EINTR` to guarantee memory reads are not interrupted.

### ✅ [RESOLVED]: `poll` EINTR Logic Bug Causes Process Deadlock via Blocking `read`
*   **Status:** RESOLVED (June 2026)
*   **Dimension:** Vulnerability Chaining & Concurrency (The Sandbox View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
*   **Context & Proof:** If `poll` is interrupted by a signal, it returns a false positive for data readiness, leading to a blocking `read` that will never return if the JVM hasn't sent the data, permanently hanging the supervisor thread and the tracee.
*   **Fix:** Wrapped the `poll` call in a loop inside `readAndHandleJvmResponse` that correctly handles `EINTR`, updates the remaining timeout dynamically, and retries the poll instead of returning `1L` to trigger a premature blocking read.

### 🔴 [Severity: MEDIUM]: Bitwise Sign-Extension Bug in `sockaddr` Domain Parsing
*   **Dimension:** FFM ABI & Memory Safety (The Low-Level View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
*   **Failure Hypothesis:** When parsing the address family (domain) from `sockaddrBytes`, the code incorrectly performs bitwise operations on signed bytes, resulting in invalid domain integers for families >= 128.
*   **Context & Proof:** In `connectSocketInSupervisor`, the domain is extracted using: `sockaddrBytes[0].toInt() or (sockaddrBytes[1].toInt() shl 8)`. In Kotlin, `Byte.toInt()` performs sign extension. If the first byte is `0x80` or higher, it will be sign-extended to a negative integer (e.g., `0xFFFFFF80`). This corrupted integer is then passed to `LinuxNative.networking.socket(domain, 1, 0)`, which will fail with `EINVAL` (Invalid argument) because the kernel does not recognize negative domains, preventing connections to legitimate address families that map to values >= 128 (e.g., custom local AF values or specific vendor AF implementations).
*   **Recommendation:** Use bitwise AND to mask out the sign extension: `(sockaddrBytes[0].toInt() and 0xFF) or ((sockaddrBytes[1].toInt() and 0xFF) shl 8)`.

### 🔴 [Severity: LOW]: Potential Buffer Overflow / OutOfBoundsException on Long UNIX Socket Paths
*   **Dimension:** FFM ABI & Memory Safety (The Low-Level View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtils.kt`
*   **Failure Hypothesis:** If `socketPath` exceeds 108 bytes, `setupSockAddrUn` will throw an `IndexOutOfBoundsException` or cause memory corruption when copying path bytes into the `sockaddr_un` FFM struct.
*   **Context & Proof:** In `SupervisorSocketUtils.setupSockAddrUn`, the length of the string is not bounds-checked before copying into the 108-byte `sun_path` struct layout using `MemorySegment.copy(pathBytes, 0, pathSeg, ValueLayout.JAVA_BYTE, 0L, pathBytes.size)`. If the OS temporary directory path (`System.getProperty("java.io.tmpdir")`) is heavily nested, `Files.createTempDirectory` in `SupervisorDaemonManager` could produce a `socketPath` exceeding 108 bytes. This will cause `MemorySegment.copy` to crash the initialization of the supervisor.
*   **Recommendation:** Add an explicit bounds check `require(pathBytes.size < 108) { "Socket path too long" }` in `setupSockAddrUn` and consider using the abstract namespace (`\0` prefix) or `openat`-relative binding if paths get too long.

### ✅ [RESOLVED]: Untrusted Allocation Size Causes `OutOfMemoryError` and Daemon Crash via `connect()`
*   **Status:** RESOLVED (June 2026)
*   **Dimension:** Vulnerability Chaining & Concurrency (The Sandbox View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/memory/SupervisorProcessMemoryReader.kt` and `SupervisorSessionHandler.kt`
*   **Context & Proof:** A tracee can intentionally crash the supervisor daemon by passing an extremely large `addrlen` argument to the `connect` syscall, triggering a fatal `OutOfMemoryError` that is not caught by standard exception handlers.
*   **Fix:** Wrapped the body of `processNotification` in `SupervisorSessionHandler` in a global `try-catch` catching `Throwable`. Any fatal error or OOM now fails-closed safely, logging the error and returning `EPERM` without crashing the daemon thread.
### ✅ [RESOLVED]: Incomplete EINTR Handling in process_vm_readv and Other Syscalls
*   **Status:** RESOLVED (June 2026)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/memory/SupervisorProcessMemoryReader.kt` and other syscalls
*   **Context & Proof:** The `process_vm_readv` call in `SupervisorProcessMemoryReader.readBytes` and `SupervisorProcessMemoryReader.readString` does not check if the `errno` is `EINTR`. If a signal is received during the call, it will return an error, which the current implementation treats as a failure and returns `null`.
*   **Fix:** Wrapped the `processVmReadv` call in `readBytes` in a `while (true)` loop that retries if `errno == NativeConstants.EINTR`, preventing spurious read failures during signal interruptions.
### 🔴 [Severity: LOW]: Memory Alignment verification for `Layouts.kt` FFM Structures
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/Layouts.kt`
*   **Hypothesis:** `Layouts.kt` manually specifies C struct memory layouts using `java.lang.foreign.MemoryLayout`. Does it perfectly match the Linux C ABI on x86_64?
*   **Context & Proof:** We wrote a C program and a Java program to verify `sizeof` and `offsetof` for `msghdr`, `cmsghdr`, `seccomp_data`, `seccomp_notif`, `seccomp_notif_resp`, and `seccomp_notif_addfd`. The sizes and offsets in Java exactly matched the sizes and offsets in C. No issues found in standard FFM layout alignment for x86_64.
*   **Recommendation:** Continue to verify cross-compilation/aarch64 alignments if applicable, but x86_64 layouts are verified correct.
### 🔴 [Severity: LOW]: Memory Segment Scopes and Lifetimes
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
*   **Hypothesis:** `Arena.ofConfined().use { ... }` scopes are heavily utilized. Are there any `MemorySegment` objects escaping their confinement scope?
*   **Context & Proof:** We examined `readAndHandleJvmResponse`, `sendRequestToJvm`, `handleInjectFd`, `openFileInSupervisor` and `connectSocketInSupervisor`. In all instances, the variables derived from `arena.allocate` do not escape the `use { ... }` closure, and primitive values (Int/Boolean) or system calls are properly extracted. No memory leaks or double frees via FFM were observed in these functions.
*   **Recommendation:** FFM scoping here looks solid.
### 🔴 [Severity: HIGH]: TOCTOU Vulnerability in Supervisor Syscall Emulation via `process_vm_readv`
*   **Dimension:** Vulnerability Chaining & Concurrency (The Sandbox View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
*   **Failure Hypothesis:** A Time-of-Check to Time-of-Use (TOCTOU) vulnerability exists when the supervisor handles `USER_NOTIF` events because it reads memory from the tracee's address space (`process_vm_readv`), validates it, and then uses that previously-read value to emulate the syscall on behalf of the tracee.
*   **Context & Proof:** In `SupervisorSessionHandler.kt`, `extractNotificationArgs` calls `readStringFromProcess` which uses `process_vm_readv` to read a file path from the tracee's memory space. This `pathStr` string is then validated against policies (e.g., the daemon-side fast-path bypass `safeBypassPaths` or via `sendRequestToJvm`). Finally, if allowed, `handleInjectFd` calls `openFileInSupervisor` using the *extracted* `pathStr` (which is a safe, copy-by-value String on the daemon side), bypassing the kernel's normal open mechanisms and directly providing an FD to the tracee via `SECCOMP_IOCTL_NOTIF_ADDFD`. Wait, this specific implementation mitigates the traditional TOCTOU! Because the daemon opens the exact string it validated, it does *not* tell the kernel to "continue" and let the kernel re-read the pointer. The daemon itself opens the file and injects the FD.
However, a TOCTOU *does* exist if `SECCOMP_USER_NOTIF_FLAG_CONTINUE` is ever used for paths containing pointers. If the daemon reads a path, validates it, and then sends `CONTINUE`, a sibling thread could have rewritten the string in memory between the `readStringFromProcess` and the `CONTINUE` ioctl. Checking `readAndHandleJvmResponse`, it appears that for `decision == 1` (Allow Continue), it uses `sendSeccompContinue` which indeed sends `SECCOMP_USER_NOTIF_FLAG_CONTINUE`.
If a policy "allows" an `openat` via `decision == 1`, the kernel will execute the syscall, re-dereferencing the pointer in the tracee's memory space. A malicious sibling thread could overwrite the path string after the supervisor validated it but before the kernel actually executes `openat`.
*   **Recommendation:** For any system call that depends on pointer dereferencing (like `open`, `openat`, `execve`), the supervisor MUST NOT use `SECCOMP_USER_NOTIF_FLAG_CONTINUE` to allow the call if it made a security decision based on the memory contents. It MUST emulate the system call (e.g., via `SECCOMP_IOCTL_NOTIF_ADDFD` or modifying the tracee's registers if supported) using the *exact copied memory* it validated. Currently, `decision == 1` is dangerous for pointer-based syscalls.
### 🔴 [Severity: LOW]: Silent Fallback by default behavior evaluation
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/Platform.kt` and usages of `Platform.configuredFallback()`
*   **Hypothesis:** If Landlock or Seccomp is missing, does the system securely fail, or does it bypass containment silently by default?
*   **Context & Proof:** `Platform.configuredFallback()` checks `io.mazewall.fallback` properties and defaults to `Platform.FallbackBehavior.FAIL` if not set. `ContainedExecutors.kt` and `Landlock.kt` correctly call this method and throw an `UnsupportedOperationException` if `FAIL` is the configured behavior.
*   **Recommendation:** The fallback behavior is secure by default (fail-closed) and correctly implemented.
### 🔴 [Severity: MEDIUM]: Architectural Violation - FFM Leaking Outside `io.mazewall.ffi`
*   **Dimension:** Architectural Patterns Compliance (The Integrity View)
*   **Target Area:** Multiple modules, e.g. `LinuxNative.kt`, `Landlock.kt`, `SupervisorSessionHandler.kt`, `SupervisorDaemonManager.kt`, `SeccompInstallationState.kt`, etc.
*   **Hypothesis:** `docs/internals/architectural_map.md` strictly dictates that "all raw memory/FFM/Unsafe manipulations isolated to `io.mazewall.ffi`".
*   **Context & Proof:** `grep -rn "java.lang.foreign" enforcer/src/main/kotlin/io/mazewall/ | grep -v "/ffi/"` reveals extensive usage of `java.lang.foreign.MemorySegment`, `Arena`, and `ValueLayout` in high-level classes like `Landlock.kt`, `SupervisorSessionHandler.kt`, and `LinuxNative.kt`. This completely violates the ArchUnit architectural constraint.
*   **Recommendation:** Move all direct FFM memory allocations (`Arena.ofConfined().use { ... }`) and native struct manipulations into dedicated wrapper classes inside `io.mazewall.ffi`. The outer layers (`enforcer`, `landlock`, etc.) should only interact with safe Kotlin types (ByteArrays, Strings, domain objects).

### ✅ [RESOLVED]: Unhandled EINTR in `SupervisorSocketUtils.sendDescriptor`
*   **Status:** RESOLVED (June 2026)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtils.kt`
*   **Context & Proof:** `sendmsg` can be interrupted by a signal, returning `EINTR`. If not handled, descriptor passing will spuriously fail.
*   **Fix:** Wrapped the `sendmsg` call inside `SupervisorSocketUtils.sendDescriptor` in a retry loop on `EINTR` to guarantee file descriptors are passed correctly.

### ✅ [RESOLVED]: Unhandled `SyscallResult` return types leaking into domain logic
*   **Status:** RESOLVED (June 2026)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/` and `io/mazewall/landlock/`
*   **Context & Proof:** `domainLogicMustHandleSyscallResults` only checked public methods, allowing internal/private domain logic to leak raw FFM `SyscallResult` objects.
*   **Fix:** Refactored internal methods in `Landlock.kt` to encapsulate all `SyscallResult` usages inside clean domain structures (`AddRuleResult`, `OpenResult`) or exceptions. Expanded the check in `ArchitectureTest.kt` to audit all methods in these packages.

### 🔴 [Severity: LOW]: Inconsistent Architecture Test for `java.lang.foreign`
*   **Dimension:** Architectural Patterns Compliance (The Integrity View)
*   **Target Area:** `enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt`
*   **Hypothesis:** The ArchUnit tests ban `java.lang.foreign.MemorySegment.reinterpret`, `Arena.ofAuto`, and `MemorySegment.get`, but they do not generally ban the import and usage of `java.lang.foreign` outside of `io.mazewall.ffi`.
*   **Context & Proof:** `grep -rn "java.lang.foreign" enforcer/src/main/kotlin/io/mazewall/ | grep -v "/ffi/"` returns many hits. The `ArchitectureTest.kt` lacks a strict package boundary check for the `java.lang.foreign` package.
*   **Recommendation:** Add an ArchUnit test: `noClasses().that().resideOutsideOfPackage("io.mazewall.ffi..").should().dependOnClassesThat().resideInAPackage("java.lang.foreign..")` to enforce the constraint defined in `architectural_map.md`.

### ✅ [RESOLVED]: Unhandled `SyscallResult` during Shutdown
*   **Status:** RESOLVED (June 2026)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManager.kt`
*   **Context & Proof:** In `triggerDaemonShutdown`, `LinuxNative.networking.connect` is executed, and if successful, `write` is called. However, it blindly ignores potential `EINTR` or `ECONNREFUSED` (which could mean the daemon is already shutting down or socket is busy).
*   **Fix:** Wrapped the `connect` and `write` system calls in retry loops that catch `EINTR` to guarantee delivery of the daemon shutdown byte.

### ✅ [RESOLVED]: Profiler Trace Listener State Mutability Bug
*   **Status:** RESOLVED (June 2026)
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt`
*   **Context & Proof:** The `ProfilerTraceListener` thread might leak resources or deadlock if an unhandled exception crashes the listener loop before `closed.set(true)` or `socketFd` is released.
*   **Fix:** Wrapped the listener thread's run execution in a `try-catch` catching `Throwable`. On fatal crash, it logs the error, marks the listener as closed, and closes the socket FD to ensure deterministic resource cleanup.
### 🔴 [Severity: LOW]: Memory Segment Scopes and Lifetimes (Re-evaluation)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
*   **Hypothesis:** `Arena.ofConfined().use { ... }` scopes are heavily utilized. Are there any `MemorySegment` objects escaping their confinement scope?
*   **Context & Proof:** As previously noted, scopes are solid, but memory allocation could still be further refined.
*   **Recommendation:** FFM scoping here looks solid.
### 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) in socket IO
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt`
*   **Hypothesis:** If `socketFd.close()` is interrupted, will it cause resource leak?
*   **Context & Proof:** Trace listener uses standard sockets. They can throw exceptions.
*   **Recommendation:** Verify that close routines handle interruptions properly.
### 🔴 [Severity: MEDIUM]: Uncaught exceptions in `ContainedExecutorWrapper.kt` during filter installation
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapper.kt`
*   **Hypothesis:** If installing a policy fails, does it clean up ThreadLocals?
*   **Context & Proof:** Wrapping tasks needs robust try-finally for thread local registries.
*   **Recommendation:** Verify that executor wrappers properly handle seccomp installation failures and clean state.
### 🔴 [Severity: MEDIUM]: Uncaught Native Exceptions in Landlock `LandlockState.kt`
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/landlock/LandlockState.kt`
*   **Hypothesis:** If allocating rulesets fails, does it leak FDs?
*   **Context & Proof:** `Landlock` uses FDs. If it crashes mid-setup, FD must be closed.
*   **Recommendation:** Verify `use` is thoroughly applied or manual close happens on error paths.
### 🔴 [Severity: MEDIUM]: TOCTOU in Path Normalization `PathNormalizer.kt`
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/sbob/PathNormalizer.kt`
*   **Hypothesis:** Can an attacker rename directory to bypass path normalizer?
*   **Context & Proof:** `PathNormalizer` does static analysis. Does the system ensure paths aren't modified post-normalization?
*   **Recommendation:** Verify path resolution constraints are verified against Landlock or Seccomp hooks safely.

### 🔴 [Severity: HIGH]: Missing ArchUnit test for FFM architecture boundary violations
*   **Target Area:** `enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt`
*   **Hypothesis:** `docs/internals/architectural_map.md` states "ArchUnit Isolation: all raw memory/FFM/Unsafe manipulations isolated to `io.mazewall.ffi`."
*   **Context & Proof:** As noted in "Architectural Violation - FFM Leaking Outside `io.mazewall.ffi`", there is extensive usage of `java.lang.foreign` outside of the FFM boundary packages. Currently, `ArchitectureTest.kt` does not have an overarching rule checking `noClasses().that().resideOutsideOfPackage("io.mazewall.ffi..").should().dependOnClassesThat().resideInAPackage("java.lang.foreign..")`. Such a test should be added, but it would currently fail.
*   **Recommendation:** Implement the ArchUnit test and incrementally refactor `Landlock.kt`, `SupervisorSessionHandler.kt`, `LinuxNative.kt`, etc., so they rely entirely on `io.mazewall.ffi` safe types.

### 🔴 [Severity: MEDIUM]: Unreliable Test Teardown for Mocked Native Engines
*   **Target Area:** `enforcer/src/test/kotlin/io/mazewall/landlock/LandlockCoverageTest.kt` and `enforcer/src/test/kotlin/io/mazewall/LinuxNativeCoverageTest.kt`
*   **Hypothesis:** `LinuxNative.setEngine(mock)` is used extensively to inject mock kernel behaviors. However, `LinuxNative.resetToDefault()` or an equivalent teardown is missing in many of these tests.
*   **Context & Proof:** `grep -rn "LinuxNative.setEngine" enforcer/src/test/kotlin/io/mazewall/` shows 15 usages, but `LinuxNative.resetToDefault` has only 2 occurrences (and `LinuxNative.setEngine(RealNativeEngine)` appears once in `NativeEngineTest.kt`). In `LandlockCoverageTest.kt` and `LinuxNativeCoverageTest.kt`, there is no `@AfterEach` method or `finally` block ensuring the engine is reset. If one of these tests fails or throws an exception midway, the static `LinuxNative` singleton will remain mocked for all subsequent tests running in the same JVM, causing spurious test failures across the suite.
*   **Recommendation:** Add an `@AfterEach` block in all test classes that use `LinuxNative.setEngine` to unconditionally call `LinuxNative.resetToDefault()`, ensuring global state is properly isolated between tests.

### 🔴 [Severity: MEDIUM]: Uncaught Native Exceptions Escaping Landlock Installation
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/landlock/LandlockState.kt`
*   **Hypothesis:** `LandlockSession.applyRuleset` handles FFM resources but may not correctly propagate or contain exceptions during intermediate installation phases.
*   **Context & Proof:** In `LandlockSession.applyRuleset`, `nativeScope` and `try-catch` are used. However, if `Landlock.createRuleset` throws an unexpected runtime exception (e.g., an FFM `IllegalStateException` due to memory alignment issues on a weird kernel, rather than a managed `SyscallResult.Error`), the exception bypasses the standard `state = LandlockState.Failed(err)` setting because it's outside the inner `try` block that wraps `added.restrictSelf(processWide)`.
*   **Recommendation:** Wrap the entire logic from `state = LandlockState.CreatingRuleset(abi)` onwards inside a comprehensive `try-catch` block that correctly transitions the state to `LandlockState.Failed(e)` for any `Throwable`, ensuring the failure state is strictly recorded before throwing.

### 🔴 [Severity: MEDIUM]: Uncaught exceptions in `ContainedExecutorWrapper.kt` during filter installation
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapper.kt`
*   **Hypothesis:** If `ContainedExecutors.installOnCurrentThread(policy, scopingPolicy)` fails midway (e.g., Landlock throws an exception before Seccomp is installed, or the supervisor connection fails), the `ThreadStateRegistry` might be left in a partially updated or corrupted state because `installOnCurrentThread` updates `ThreadStateRegistry.state` incrementally as it installs filters, but doesn't roll back on failure.
*   **Context & Proof:** `ContainedExecutors.installInternal` calls `applyLandlockIfNecessary` which updates `ThreadStateRegistry.state`. If the subsequent `installSeccompFilter` fails, the thread state registry will reflect Landlock applied, but the seccomp filter might not be, or worse, the `SupervisorSession` might not be created properly. While `ContainedExecutorWrapper` uses `.use {}` to close the `AutoCloseable` return value of `installOnCurrentThread`, it doesn't clean up the `ThreadStateRegistry.state` if the *installation itself* throws an exception.
*   **Recommendation:** `ContainedExecutors.installInternal` should probably take a snapshot of the current state, and use a `try-catch` to restore the original `ThreadStateRegistry.state` (and potentially Landlock/Seccomp state, though those are harder to revert) if the installation throws an error, or the Wrapper should handle it.

### 🔴 [Severity: MEDIUM]: TOCTOU in Path Normalization `PathNormalizer.kt`
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/sbob/PathNormalizer.kt`
*   **Hypothesis:** `PathNormalizer.normalizeAndPrune` uses `Path.normalize()` which is purely syntactic and does not resolve symlinks dynamically at the kernel level.
*   **Context & Proof:** The method states "resolves all paths... and prunes redundant subpaths". However, it only uses `normalize()` (which removes `..` and `.`), not `toRealPath()` or `toAbsolutePath()`. If a path `/opt/app/../etc/passwd` is specified, `normalize()` resolves it to `/etc/passwd`. But if `/opt/app` was a symlink to `/var/app` and `/var` was allowed, the string-based parsing is ignorant of the actual kernel VFS topology. This can create vulnerabilities where an attacker constructs paths that look harmless syntactically but point to sensitive locations via symlinks. The `Landlock.applyUserRules` then registers these string paths into the kernel.
*   **Recommendation:** `PathNormalizer` should strictly document that it performs static analysis, and if real security guarantees are needed, it should invoke `Path.toRealPath()` to resolve symlinks before pruning, or rely entirely on kernel-level Landlock rules (which handle symlink resolution dynamically, though Landlock `openat` flags do not follow symlinks by default unless `O_NOFOLLOW` is omitted).
