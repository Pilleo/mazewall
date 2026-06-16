# Code Issues Backlog

## Recent Findings (Project Review June 2026)

### 🔵 [Severity: ENHANCEMENT]: Compile-Time Feature Proof Tokens and Scope-Safe Policy Builders (Type-State Pattern)
**Context:** Currently, `ContainedExecutors.kt` throws a runtime `UnsupportedOperationException` if process-wide containment is applied with Landlock rules because Landlock has historically been considered thread-scoped only. However, process-wide Landlock is supported on some newer kernels/setups. Blocking it unconditionally at compile-time or throwing runtime failures limits support on modern systems.
**Needed:** Implement compile-time feature proof tokens and type-state parameterized builders.
1. Define a `ProcessWideLandlockToken` that can only be obtained at runtime by checking support (`Landlock.isSupportedProcessWide()`).
2. Parameterize `PolicyBuilder` with a `Scope` type-state, requiring the token to configure Landlock filesystem rules on a process-wide policy.
3. Implement a `LandlockFallback` enum (`FailClosed`, `WarnAndBypass`) for process-wide policy installations when runtime kernel support is absent.
4. This ensures that Landlock's conditional process-wide availability is verified at runtime before configuration, preventing illegal rulesets while preserving compilation safety.

### 🔵 [Severity: ENHANCEMENT]: Strong Type-Safety for `prctl` (Sealed Command Hierarchies)
**Context:** The JVM `prctl` wrapper currently accepts raw `Int` or `Long` for commands and arguments. Since `prctl` accepts vastly different option structures and argument counts/types depending on the first parameter (the `option` command, e.g. `PR_SET_SECCOMP` vs `PR_SET_NAME`), this is extremely error-prone and can easily lead to memory corruption or invalid register states.
**Needed:**
1. Define a sealed class hierarchy representing valid `PrctlCommand`s (e.g. `SetNoNewPrivs`, `SetName(name: String)`, `SetSeccomp(mode: Int)`).
2. Use type-safe serialization inside the downcall wrapper to unpack the sealed command parameters into correct native arguments.
3. Update `NativeProcess` to accept the typed `PrctlCommand` instead of raw long/integer arguments, eliminating the risk of misaligned arguments at compile time.

### 🔵 [Severity: ENHANCEMENT]: Verified-by-Construction BPF Bytecode (BpfProgram<Status>)
**Context:** BPF filters are constructed via string builders or manual instruction lists and passed directly to the kernel. A typo or structural error in a jump target or instruction boundary results in a runtime error or, worse, a kernel validation failure that triggers a fallback/bypass.
**Needed:**
1. Introduce a phantom type `BpfProgram<Status>` where `Status` is `Unverified` or `Verified`.
2. Provide a builder DSL that generates instructions into `BpfProgram<Unverified>`.
3. Require passing `BpfProgram<Unverified>` through an in-memory/in-app BPF static verifier or a local compilation dry-run to produce `BpfProgram<Verified>`.
4. Enforce that `PureJavaBpfEngine.install` only accepts `BpfProgram<Verified>`, guaranteeing that only mathematically verified filters can ever be loaded into the kernel.

### 🔵 [Severity: ENHANCEMENT]: Context-Scoped Resource Ownership (File Descriptors)
**Context:** Linux file descriptors (`FileDescriptor`) are managed as raw integers. If a file descriptor is closed twice (double close), or used after close (use-after-free), or leaked, it can lead to severe security bugs or incorrect resource assignment when another thread opens a new file.
**Needed:**
1. Refactor `FileDescriptor` to be a context-scoped resource wrapper using `AutoCloseable` or `Arena` scope.
2. Require native transactions to check the validity of a `FileDescriptor` token before performing system calls on it.
3. Implement a compile-time ownership tracking mechanism where resource lifetimes are bounded by FFM `Arena` scopes, preventing use-after-free at compile time.

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

### 🔵 [Severity: ENHANCEMENT]: Polymorphic `TraceEvent` Hierarchy (Semantic Property Access)
**Target:** `io.mazewall.profiler.engine.TraceEvent`
**Context:** `TraceEvent` is currently a sealed class but only distinguishes between `Generic` and `File`. Crucially, both variants still expose a raw `args: LongArray` as the primary way to access syscall parameters. This forces downstream consumers (like `BobCompiler` and `SyscallPathResolver`) to use **positional/index-based access** (e.g., `event.args[2]` for `mmap` protection flags). 
**Problem:** This "primitive obsession" at the event layer is brittle and error-prone. A single register mismatch in the mapping logic (as seen with `SYMLINKAT`) causes silent analysis failures. Furthermore, it prevents the compiler from ensuring that all parameters for a specific syscall are present and correctly typed before they reach the analysis engine.
**Status:** **PARTIALLY IMPLEMENTED.** (The sealed class structure exists, but the semantic property extraction does not).
**Needed:** Transition to a fully specialized sealed hierarchy where syscall-specific parameters are exposed as **named, typed properties**.
1. **Specialized Variants:** Implement types like `SocketEvent(val domain: Int, val type: Int)`, `MmapEvent(val addr: Long, val len: Long, val prot: Int)`, and `ExecEvent(val path: String, val args: List<String>)`.
2. **Type-Safe Analysis:** Refactor `BobCompiler` to use exhaustive `when` expressions on these types. Instead of checking `if (event.syscallName == "MMAP") { use(event.args[2]) }`, it should use `is MmapEvent -> use(event.prot)`.
3. **Internal Decoupling:** The register-to-property mapping should be encapsulated within the `TraceEvent` factory or a specialized `EventMapper`, keeping the rest of the profiler engine completely unaware of raw register indices.

### 🔵 [Severity: ENHANCEMENT]: Formal Monoidal Composition for `BillOfBehavior`
**Target:** `io.mazewall.profiler.BillOfBehavior`
**Context:** `BillOfBehavior` has a manual `plus` operator, but it isn't formally modeled as a Monoid. Merging complex behavior profiles (e.g., merging a JVM floor with an application-specific trace) is a core operation for generating policies.
**Needed:** Formally implement the Monoid pattern for `BillOfBehavior`.
1. Define an `identity` (Empty SBoB).
2. Ensure the `plus` operation is associative and correctly merges sets and maps (including deep merging of stack profiles).
3. This allows using standard functional aggregators like `list.reduce(BillOfBehavior::plus)` or `list.fold(BillOfBehavior.empty, ...)` with algebraic certainty.

### ✅ [RESOLVED] [Severity: ENHANCEMENT]: Type-Safe "Must-Check" Enforcement for `SyscallResult`
**Target:** `io.mazewall.SyscallResult`
**Context:** While `SyscallResult` is a sealed class (Success/Error), nothing in the type system prevents a developer from invoking a native method and completely ignoring the returned `SyscallResult`. In security-critical native code, an unhandled error (like a failed `seccomp` installation) is catastrophic.
**Needed:** Use generics and phantom types to enforce that a result is "consumed."
1. Redefine as `SyscallResult<out T, out Handled : Boolean>`.
2. Native methods return `SyscallResult<T, False>`.
3. Methods like `onSuccess`, `onFailure`, `recover`, or `getOrThrow` return `SyscallResult<T, True>` or the raw value.
4. While Kotlin doesn't have native "must-use" attributes like Rust's `#[must_use]`, this type-state allows for ArchUnit or Lint checks that ensure no `SyscallResult<_, False>` remains in the final expression of a block.

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

### 🔵 [Severity: ENHANCEMENT]: Investigate "Loom-Safe" Profiling for Virtual Threads
**Target:** `io.mazewall.profiler.Profiler`
**Context:** Currently, `Profiler.profile` explicitly forbids execution on virtual threads to prevent "Carrier Poisoning" (trapping the underlying OS thread and affecting unrelated virtual threads).
**Needed:** Explore mechanisms to allow profiling virtual threads without global side effects.
1. Evaluate using `ReentrantLock` or `synchronized` blocks inside the workload to "pin" the virtual thread to its carrier during sensitive sections.
2. Alternatively, investigate if a process-wide `USER_NOTIF` handler can distinguish between virtual threads based on their `TID` and only trap those explicitly opted into profiling.

### 🔵 [Severity: ENHANCEMENT]: Leverage Kotlin Contracts for Static Analysis
**Target:** `io.mazewall.enforcer` and `io.mazewall.LinuxNative`
**Context:** The compiler is often unaware of the side effects of validation functions or the invocation guarantees of scoped lambdas. This leads to redundant checks and prevents initializing `val` properties within blocks like `withTransaction`.
**Needed:** Implement Kotlin Contracts across core utilities.
1. **Validation Contracts**: Update `validateLinuxAndNotVirtual()` to use `returns() implies ...`.
2. **Result Contracts**: Update `SyscallResult.isSuccess()` to use `returns(true) implies (this is Success)`.
3. **Scope Contracts**: Update `withTransaction`, `nativeScope`, and `SyscallResult` combinators (`onSuccess`, `map`) with `callsInPlace` (`EXACTLY_ONCE` or `AT_MOST_ONCE`).
4. This improves DX by enabling smart-casting and local `val` initialization in native scopes.

## Foundational Architecture & Test-Harness Enablers

### 🔵 [Severity: ENHANCEMENT]: Phantom Types for Context-Aware Capability Tokens
**Target:** `io.mazewall.NativeTransaction` and `io.mazewall.LinuxNative`
**Context:** Currently, `NativeTransaction` acts as a blanket capability token, allowing any transaction to perform any native operation (read-only or read-write). This means an auditing or profiling phase can accidentally invoke a mutating system call (like `prctl` or `socket`) when it only intended to read memory.
**Needed:** Implement context-sensitive capability tokens using **Phantom Types**. 
1. Define marker interfaces `ReadOnly` and `ReadWrite`.
2. Refactor `NativeTransaction` to `NativeTransaction<Mode>`.
3. Update `NativeEngine` methods to demand specific modes via context receivers, e.g., `context(_: NativeTransaction<out ReadOnly>)` for `processVmReadv` and `context(_: NativeTransaction<ReadWrite>)` for `prctl`. This ensures at compile-time that restricted scopes cannot perform mutating operations.


### 🔵 [Severity: ENHANCEMENT]: Type-State Enforced BPF DSL
**Target:** `io.mazewall.seccomp.BpfProgram.Builder`
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

### 🔴 [Severity: HIGH]: Tier S Profiler is blind to background threads (No TSYNC/Inheritance)
**Target:** `io.mazewall.profiler.Profiler.kt`, `io.mazewall.profiler.engine.ProfilerInstaller.kt`
**Context:** Seccomp filters and `USER_NOTIF` file descriptors are per-thread by default. The current Tier S `Profiler.profile { ... }` only installs the filter on the calling thread. Background JVM threads (GC, JIT, ForkJoinPool) completely bypass the profiler, leading to an incomplete "JVM Floor" baseline.
**Needed:** Implement process-wide tracing support in Tier S. Two potential paths:
1. **`SECCOMP_FILTER_FLAG_TSYNC`:** Synchronize the filter to all existing threads in the thread group at installation time.
2. **`SECCOMP_FILTER_FLAG_NEW_LISTENER` + Clone Tracking:** Ensure new child threads automatically inherit the seccomp filter and notify the same supervisor daemon.
This is critical for generating a production-grade JVM Syscall Floor that accounts for background management tasks.

### 🔴 [Severity: HIGH]: Blacklist policies trigger silent Landlock filesystem lockdown due to `io_uring` check
**Target:** `io.mazewall.enforcer.ContainedExecutors.kt` (specifically `needsLandlock` calculation)
**Context:** In `ContainedExecutors.kt`, `needsLandlock` is implicitly triggered if `io_uring_setup` is allowed, even if no filesystem paths are specified. This causes Landlock to be applied with an empty ruleset, permanently locking down the filesystem for the thread. This trigger is currently undocumented in the code, making it difficult for agents to diagnose the root cause of the "silent lockdown" symptom observed in `Landlock.kt`.
**Needed:** Add a cross-reference comment to the `io_uring` trigger in `ContainedExecutors.kt`. Long-term, decouple the `io_uring` safety check from the automatic filesystem lockdown or provide a clear warning/opt-out mechanism.

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

### 🔴 [Severity: HIGH]: `ProfilerDaemon` fails to resolve `SYMLINKAT` path parameters due to invalid argument grouping
**Target:** `io.mazewall.profiler.engine.ProfilerDaemon` (specifically `getPathArgs`)
**Failure Hypothesis:** The `ProfilerDaemon` maps `SYMLINKAT` into the same argument-parsing branch as `RENAMEAT`, `RENAMEAT2`, and `LINKAT`. However, `SYMLINKAT` uses a completely different argument layout than those double-descriptor syscalls. This mismatch causes the profiler to read invalid memory addresses, failing path resolution completely and leading to production Landlock crashes.
**Context & Proof:** In `ProfilerDaemon.kt`, `SYMLINKAT` is matched in the following branch of `getPathArgs()`:
```kotlin
            "RENAMEAT", "RENAMEAT2", "LINKAT", "SYMLINKAT" ->
                listOfNotNull(
                    tryRead(pid, args[ARG_OLD_PATH], args[ARG_OLD_DIR_FD]),
                    tryRead(pid, args[ARG_NEW_PATH], args[ARG_NEW_DIR_FD]),
                )
```
Where:
- `ARG_OLD_DIR_FD` = 0, `ARG_OLD_PATH` = 1
- `ARG_NEW_DIR_FD` = 2, `ARG_NEW_PATH` = 3

But the Linux signature for `symlinkat` is:
`int symlinkat(const char *target, int newdirfd, const char *linkpath);`
Which translates in seccomp notification args to:
- `args[0]` = `target` (string pointer)
- `args[1]` = `newdirfd` (integer FD)
- `args[2]` = `linkpath` (string pointer)
- `args[3]` = (unused / undefined register garbage)

Consequently, `ProfilerDaemon` executes:
1. `tryRead(pid, args[1], args[0])` -> Treats `newdirfd` (e.g. `3`) as a string pointer. `process_vm_readv` tries to read memory at address `3`, failing with `EFAULT`.
2. `tryRead(pid, args[3], args[2])` -> Treats register garbage (from `args[3]`) as a string pointer, failing with `EFAULT` or `EPERM`.

As a result, neither the symlink target nor the symlink creation path is resolved. SBoB output misses the rule, causing Landlock to deny `symlinkat` in production and crash the application.
**Cascading Risk Potential:** High usability, stability, and sandbox coverage defect. Completely prevents applications from creating symbolic links via `symlinkat` in production sandboxes.
**Needed:** Move `"SYMLINKAT"` out of the `RENAMEAT` branch. Create a dedicated branch in `getPathArgs()` for `"SYMLINKAT"`:
```kotlin
            "SYMLINKAT" ->
                listOfNotNull(
                    tryRead(pid, args[0]), // Target raw string (treated as absolute or relative to linkpath)
                    tryRead(pid, args[2], args[1]) // Resolves linkpath (args[2]) relative to newdirfd (args[1])
                )
```

### 🔴 [Severity: HIGH]: `IterativeProfiler` fails to resolve wrapped exception chains, breaking progressive profiling
**Target:** `/profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt` (specifically `extractViolationPath`)
**Failure Hypothesis:** Progressive profiling (Tier A) relies on catching VFS permission failures, extracting the blocked file path, adding it to the policy, and retrying the task. If a library or framework catches the underlying `AccessDeniedException` and wraps it in a custom runtime or checked exception (which is standard behavior in modern enterprise Java frameworks), the profiler will fail to extract the path because it only inspects the top-level exception.
**Context & Proof:** In `IterativeProfiler.kt`, `extractViolationPath` only checks:
```kotlin
val path = when {
    t is AccessDeniedException -> t.file
    else -> {
        val msg = t.message
        ...
```
If a framework catches `AccessDeniedException` and wraps it (e.g. in `RuntimeException`), `t is AccessDeniedException` is `false`. It falls into the `else` block to parse the exception's message string using regex/flat string matching. However, standard Java exception wrapper messages (e.g. `"java.lang.RuntimeException: java.nio.file.AccessDeniedException: /var/lib/app/file"`) do not contain any of the `ContainmentViolationDetector.DENIED_PHRASES` like `"Permission denied"` or `"Operation not permitted"`. As a result, `phraseIdx` evaluates to `-1` and the method returns `null`. The profiler aborts the auto-discovery loop prematurely and rethrows the wrapper exception, permanently breaking rule discovery.
**Cascading Risk Potential:** High usability and stability failure. Breaks progressive sandbox profiling for any workload that uses third-party libraries that wrap exceptions.
**Needed:** Modify `IterativeProfiler.extractViolationPath` to traverse the exception cause chain using a recursive or iterative helper (similar to `ContainmentViolationDetector.isContainmentViolation`). If any cause in the chain is an `AccessDeniedException`, extract the path directly from it. If the cause is a raw `IOException`, parse its message for the file path and denied phrases.

### 🔴 [Severity: HIGH]: Excessive Landlock directory capability leak on unlinked/deleted files ending in ` (deleted)`
**Target:** `io.mazewall.profiler.engine.ProfilerDaemon` (specifically `resolveFdPath`) and `io.mazewall.landlock.Landlock.kt` (specifically `addRule`)
**Failure Hypothesis:** When a profiled application accesses a temporary file and deletes it while keeping its file descriptor open, Linux procfs `/proc/<pid>/fd/<fd>` symlinks resolve with ` (deleted)` appended to the path. The profiler logs this path, and when applied in production, Landlock's fallback mechanism opens the parent directory, exposing the entire directory to the sandbox.
**Context & Proof:** If an application opens a file (e.g. `/var/log/app/tmp_file`) and unlinks it immediately, `ProfilerDaemon.resolveFdPath` calls `readlink` on `/proc/$pid/fd/$fd`, which returns `/var/log/app/tmp_file (deleted)`. The profiler records this exact string in the SBoB JSON. In production, `Landlock.addRule()` tries to open `/var/log/app/tmp_file (deleted)`. Since that path does not exist, `handleInitialOpenFailure` catches the `ENOENT` error and falls back to the parent directory by calling `File("/var/log/app/tmp_file (deleted)").parent ?: "/"`, which resolves to `/var/log/app`. Landlock then opens `/var/log/app` and adds a rule allowing full access. This leaks access to all sibling files and folders inside that directory.
**Cascading Risk Potential:** High security privilege leak. An attacker can access, corrupt, or delete other sensitive logs and files in the parent directory, breaching the single-file isolation model.
**Needed:** In `ProfilerDaemon.kt`, strip any trailing `" (deleted)"` suffix from resolved paths before returning them. Additionally, in `Landlock.kt`'s `handleInitialOpenFailure`, ignore fallback attempts for paths ending with `" (deleted)"` or validate if the path string represents a deleted file marker before reverting to the parent.

### 🔴 [Severity: HIGH]: `ProfilerDaemon` memory-reading fails to resolve paths on page boundaries or large strings
**Target:** `io.mazewall.profiler.engine.ProfilerDaemon` (specifically `readStringFromProcess`)
**Failure Hypothesis:** If `process_vm_readv` reads a path string that does not contain a null terminator in the returned buffer (due to page boundaries or large lengths), the profiler returns `null`, breaking rule compilation.
**Context & Proof:** In `ProfilerDaemon.kt`'s `readStringFromProcess()`, a loop searches `localBuf` for `0.toByte()`. If `process_vm_readv` performs a partial read (e.g. at the end of a mapped page boundary) or if the path is longer than `maxLen` (4096 bytes) and no null terminator is present, the index loop reaches `bytesRead`. The condition `len < bytesRead` then evaluates to `false`, causing the method to return `null`. The profiler thus fails to capture the path, producing empty rulesets that crash in production.
**Cascading Risk Potential:** High usability and stability failure. Breaks path resolution on complex memory allocations, leading to broken policies and production crashes.
**Needed:** If `len == bytesRead`, copy and return the best-effort string `localBuf.copyToString(bytesRead)` rather than returning `null`. Alternatively, increase the buffer size and perform a secondary read if a null terminator is not found.

### 🔴 [Severity: HIGH]: `IterativeProfiler` crashes deterministically on relative-path filesystem violations
**Target:** `/profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt` (specifically `extractViolationPath`) and `/enforcer/src/main/kotlin/io/mazewall/Policy.kt` (specifically `validatePath`)
**Failure Hypothesis:** When a profiled workload attempts to access a file using a relative path (e.g. `Paths.get("data.txt")`), a `java.nio.file.AccessDeniedException` is thrown containing the relative path. The `IterativeProfiler` extracts this relative path and attempts to add it to the policy via `allowFsRead(path)`. However, `Policy.Builder.validatePath` strictly mandates absolute paths, throwing `IllegalArgumentException: Path must be absolute`, which crashes the profiling loop instead of resolving or canonicalizing the path.
**Context & Proof:** If a task performs `Files.readString(Paths.get("relative/file.txt"))`, Java throws `AccessDeniedException` where `t.file` is `"relative/file.txt"`. `extractViolationPath` returns `"relative/file.txt"`. `profile` calls `updatePolicyForViolation(currentPolicy, "relative/file.txt")`, which calls `builder.allowFsRead("relative/file.txt")`. Since `"relative/file.txt"` does not start with `"/"`, `validatePath` throws `IllegalArgumentException`. The retry loop in `IterativeProfiler` is immediately aborted, crashing the workload.
**Cascading Risk Potential:** High usability and stability failure. Completely prevents progressive/iterative profiling of any applications that rely on relative file paths.
**Needed:** In `IterativeProfiler.extractViolationPath`, if the extracted path is relative, resolve it to an absolute path relative to the JVM CWD (or a provided working directory) before returning it. Alternatively, canonicalize all paths in `updatePolicyForViolation` using `Paths.get(path).toAbsolutePath().normalize().toString()`.

### 🔴 [Severity: HIGH]: `IterativeProfiler` infinite retry loop and failure on disjoint prefix file paths
**Target:** `/profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt` (specifically `updatePolicyForViolation`)
**Failure Hypothesis:** The `IterativeProfiler` checks if read is already allowed using a naive string `startsWith` check. If the workload accesses a path whose prefix matches an already allowed path but is a different, longer directory name (e.g., `/var/log-extra` when `/var/log` is allowed), the check falsely returns `true`. The profiler then attempts to add a *write* rule instead of a *read* rule, causing subsequent read attempts to continue failing and forcing the profiler into an infinite discovery retry loop that aborts after 20 retries.
**Context & Proof:** If `currentPolicy` allowed read to `/var/log`, and a trapped read occurs on `/var/log-extra`, `isCurrentlyReadAllowed` evaluates to `true` (since `"/var/log-extra".startsWith("/var/log")` is true). So `updatePolicyForViolation` executes the `then` branch: `if (isCurrentlyReadAllowed) { builder.allowFsWrite(path) }`. Thus, it adds a write rule for `/var/log-extra` but NEVER adds a read rule! On the next retry, the thread tries to read `/var/log-extra` again, gets denied, and the same logic is executed. This continues until the retry count hits `maxRetries` (20), at which point the profiler crashes.
**Cascading Risk Potential:** High stability and usability bug. Blocks iterative profiling for applications with sibling directories sharing identical prefixes.
**Needed:** Use proper component-based `Path.startsWith` logic instead of raw string `startsWith`. Map the strings in `allowedFsReadPaths` to `Path` structures and normalize them, then compare using `java.nio.file.Path.startsWith`.

### 🔴 [Severity: HIGH]: `ProfilerDaemon` `SYMLINKAT` Mapping Error
**Target:** `io.mazewall.profiler.engine.ProfilerDaemon.kt` (specifically `getPathArgs`)
**Context:** `SYMLINKAT` parameters are mapped as `(oldDirFd, oldPath, newDirFd, newPath)`, but the Linux kernel signature is `(target, newdirfd, linkpath)`. This causes the profiler to attempt to read memory from registers that do not contain string pointers, resulting in failed path resolution for symlink creation.
**Needed:** Correct the argument mapping for `SYMLINKAT` to match the `(target, newdirfd, linkpath)` signature.

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

### 🔴 [Severity: HIGH]: STRICT_SANDBOX crashes on Linux kernels < 6.10 (Landlock ABI < 5) due to unblocked `ioctl`
**Target:** `io/mazewall/landlock/Landlock.kt` and `io/mazewall/Policy.kt`
**Context:** The `Policy.PURE_COMPUTE` preset uses `PURE_COMPUTE_UNSAFE` as its base and calls `allowJvmClasspath()`. Calling `allowJvmClasspath()` populates `allowedFsReadPaths`, which implicitly sets `enforceLandlock = true`. 
When `Landlock.applyRuleset()` is invoked, it checks `getAccessMask()`. If the system's Landlock ABI is < 5 (Linux < 6.10), Landlock cannot restrict `ioctl` operations. The code correctly verifies that if Landlock cannot restrict `ioctl`, the seccomp policy *must* block it: `else if (policy.isSyscallAllowed(Syscall.IOCTL)) { unsupportedErrors.add(...) }`.
However, `PURE_COMPUTE_UNSAFE` does **not** block `Syscall.IOCTL` (likely because standard out `isatty` requires it). Therefore, running `PURE_COMPUTE` on any kernel older than Linux 6.10 (e.g., Ubuntu 24.04 uses 6.8) results in a fatal `UnsupportedOperationException` on startup. 
**Needed:** Either `PURE_COMPUTE_UNSAFE` / `PURE_COMPUTE` must explicitly block `ioctl` (and accept that `isatty` fails, perhaps redirecting it), OR the Landlock ABI < 5 check for `ioctl` should only be a warning if the policy is an out-of-the-box preset. Alternatively, `PURE_COMPUTE` should be adjusted to block `ioctl` explicitly.

### 🔴 [Severity: MEDIUM]: Excessive container privileges and deprecated Audit architecture in compose.yml files
**Target:** /infra/dev/compose.yml and /demos/vulnerable-web-app/compose.yml
**Context:** The SECURITY_CONSIDERATIONS.md document clearly states that Landlock Audit is deprecated for transparent profiling because it lacks a permissive mode and causes EACCES crashes. It explicitly mandates an unprivileged profiling strategy (Tier H or Tier A). However, infra/dev/compose.yml still grants AUDIT_READ, AUDIT_CONTROL, network_mode: host, and userns_mode: host citing the deprecated Audit subsystem. Even worse, demos/vulnerable-web-app/compose.yml grants SYS_ADMIN and SYS_PTRACE, completely invalidating the claim that the demonstration runs in a restricted, unprivileged container environment. Furthermore, the demo compose file references a broken path ${PWD}/../../podman-seccomp.json.
**Needed:** 
1. Remove AUDIT_READ, AUDIT_CONTROL, network_mode: host, and userns_mode: host from infra/dev/compose.yml.
2. Remove SYS_ADMIN, AUDIT_READ, and SYS_PTRACE from demos/vulnerable-web-app/compose.yml. 
3. Fix the seccomp annotation path in the demo compose file to point correctly to the infra/dev/podman-seccomp.json file.

### 🔴 [Severity: LOW]: ContainmentViolationDetector misses \b word boundaries
**Target:** /enforcer/src/main/kotlin/io/mazewall/enforcer/ContainmentViolationDetector.kt
**Context:** The AGENTS.md documentation strictly specifies using word boundary regexes (?i)\bOperation not permitted\b... for Priority 2 matching to prevent false positives. However, containsDeniedPhrase uses msg.contains(it, ignoreCase = true), which performs unbounded substring matching.
**Needed:** Update DENIED_PHRASES matching to use a compiled Regex with \b boundaries as specified in the documentation.

### 🔴 [Severity: MEDIUM]: Redundant BPF Argument Inspection Blocks in Stacked Filters cause performance and size bloat
**Target:** `/enforcer/src/main/kotlin/io/mazewall/enforcer/FilterInstallationPlanner.kt` (specifically `calculateNewFilter`)
**Failure Hypothesis:** Seccomp BPF filters are additive. If a previous filter already restricts `mmap(PROT_EXEC)`, non-thread `clone`, or unsafe `prctl` calls, there is no need to compile and install duplicate argument inspection blocks for these syscalls in a new stacked filter.
**Context & Proof:** `FilterInstallationPlanner.calculateNewFilter` evaluates:
```kotlin
val needsMmapProtection = !policy.allowMmapExec && state.currentlyAllowsMmapExec
```
If a previous filter already blocked `mmap` exec, `state.currentlyAllowsMmapExec` is `false`, so `needsMmapProtection` is `false`. Thus, the protection itself does not trigger `needsNewFilter = true`.
However, if `needsNewFilter` is triggered by a *different* new syscall block, we compile the new policy filter `toInstall`. Because `policy.allowMmapExec` is `false` (the new policy also blocks it), the constructed `toInstall` has `allowMmapExec = false`. When `BpfFilter.build(arch, toInstall)` compiles the filter, it unconditionally adds the complete `mmap`/`mprotect` argument-inspection BPF instruction block.
As a result, both the existing filter and the new stacked filter contain identical redundant BPF instruction sets. Each subsequent stacked filter adds yet another copy, bloating the kernel's BPF filter list, increasing runtime overhead on every `mmap`/`mprotect`/`clone`/`prctl` call, and wasting the 32-filter depth limit budget.
**Cascading Risk Potential:** Medium performance and kernel instruction memory footprint degradation on highly nested or stacked sandbox setups.
**Needed:** In `FilterInstallationPlanner.calculateNewFilter`, when constructing `toInstall` in the `else` branch of `policy.defaultAction == ACT_ALLOW`:
If `state.currentlyAllowsMmapExec` is `false`, call `builder.allowMmapExec()` so that the new filter skips compilation of the redundant inspection block. Apply the same optimization for `allowNonThreadClone` and `allowUnsafePrctl`.

### 🔴 [Severity: HIGH]: Public `PureJavaBpfEngine.install` bypasses Loom Carrier Poisoning safeguards and JIT warmups
**Target:** `io.mazewall.seccomp.PureJavaBpfEngine` & `io.mazewall.enforcer.ContainedExecutors`
**Failure Hypothesis:** A client application or direct invocation of the public `PureJavaBpfEngine.install()` or `PureJavaBpfEngine.installOnProcess()` bypasses the critical virtual thread safety guards, JIT warmups, and thread-local state tracking implemented in `ContainedExecutors`.
**Context & Proof:** In `PureJavaBpfEngine.kt`, the `install` and `installOnProcess` methods are public and implement `SeccompEngine`. Unlike `ContainedExecutors.installOnCurrentThread`, `PureJavaBpfEngine` contains no `checkVirtualThread()` assertion. If a developer or library calls `PureJavaBpfEngine.install()` from within a Loom Virtual Thread, it will successfully execute `prctl(PR_SET_NO_NEW_PRIVS)` and `seccomp(...)` on the underlying OS carrier thread. This causes carrier thread poisoning, permanently restricting all other virtual threads scheduled on it. Furthermore, it completely bypasses the `ContainerStateRegistry` thread-local state updates and `performJitWarmup()`, leading to JIT compiler deadlocks/traps and state inconsistencies during stacked filter installation.
**Cascading Risk Potential:** High security containment and stability bypass. Bypasses core safety guards, poisoning carrier threads and corrupting subsequent stacked sandboxes.
**Needed:** Declare `PureJavaBpfEngine` and `SeccompEngine` as `internal` to prevent direct external access. Additionally, add a virtual thread check `if (Thread.currentThread().isVirtual) { ... }` inside `PureJavaBpfEngine.installInternal` as a defense-in-depth safety measure.

### 🔴 [Severity: CRITICAL]: StraceProfiler completely fails to trace `io_uring` file operations natively
**Target:** `io.mazewall.profiler.strace.StraceProfiler`, `docs/internals/profiler_design.md`
**Context:** The `profiler_design.md` document claims that Tier P (`StraceProfiler`) natively captures paths and async execution of `io_uring` (stating "Tier P (Root) | Paths and async captured natively"). This is fundamentally impossible under the current implementation and kernel constraints.
1. `StraceProfiler` executes `strace -f -e trace=file,network`. The `trace=file` class traces syscalls that take a string path argument (e.g., `openat`, `stat`). It *does not* include `io_uring_enter`.
2. Even if `io_uring_enter` were traced, the file paths exist entirely in the shared memory Submission Queue Entries (SQEs), not as standard string arguments to a syscall.
3. When the kernel processes these SQEs (often via `io-wq` kernel threads), the VFS operations occur entirely within kernel space. No user-space syscall boundary is crossed, so `ptrace` (which powers `strace`) is completely blind to them.
Consequently, if a workload relies on `io_uring` for file access, `StraceProfiler` will silently miss all accessed paths, producing broken policies. The claim in the documentation that `strace` captures `io_uring` paths natively is objectively false.
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

### 🔴 [Severity: HIGH]: `Landlock.applyRestrictiveBarrier()` Silent Fail-Open
**Target:** `io.mazewall.landlock.Landlock.kt`
**Context:** The method ignores the return values of `prctl(PR_SET_NO_NEW_PRIVS)` and the `landlock_restrict_self` syscall. If the kernel fails to apply the ruleset (e.g. invalid FD, EPERM), the method returns silently, and the `IterativeProfiler` continues running WITHOUT filesystem containment, leading to zero discovered paths.
**Needed:** Add strict result verification and throw an exception on failure.

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
*   **Context & Proof:** `ContainedExecutors` relies on `applyContainment` wrapping each task. If `shutdownNow()` is called on the underlying executor, threads may be interrupted during the delicate FFM downcalls (e.g. `seccomp` or `prctl`). The JVM does not guarantee atomic execution of these FFM boundaries against thread interruptions.
*   **Cascading Risk Potential:** Medium stability risk. Could lead to memory leaks or JVM crashes if native Arenas are accessed after the thread is aggressively killed during shutdown sequences.
*   **Recommendation:** Document the need for graceful shutdown (`shutdown()` and `awaitTermination()`) when using `ContainedExecutors`, and explore adding explicit resource cleanup hooks that are resilient to thread interruptions.

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

### 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) in `poll` and `recvmsg` in `ProfilerDaemon`
*   **Dimension:** OS Invariants
*   **Target Area:** `io.mazewall.profiler.engine.ProfilerDaemon`
*   **Failure Hypothesis:** The `ProfilerDaemon` uses a `poll` and `recvmsg` loop to multiplex and read incoming `USER_NOTIF` events from trace listeners over Unix Domain Sockets. If an asynchronous signal interrupts these downcalls, they will fail with `EINTR`. The daemon may not correctly retry the interrupted calls, potentially losing events, desynchronizing the protocol, or incorrectly terminating the connection.
*   **Context & Proof:** `ProfilerDaemon.reactorLoop` and `ProfilerSessionHandler.waitForParentAck` make blocking calls via `LinuxNative.getFileSystem().poll` and `LinuxNative.getNetwork().recvmsg`. The kernel routinely interrupts these calls with `EINTR` (e.g. for GC safepoints or user-defined signals). While `recvmsg` might have been patched in the listener, the daemon-side `poll` loop lacks an explicit `EINTR` retry.
*   **Cascading Risk Potential:** Medium. Can lead to non-deterministic trace event drops or premature termination of the profiler daemon connection, especially during heavy workload profiling on multi-core VMs.
*   **Recommendation:** Wrap all blocking network/file system downcalls (`poll`, `recvmsg`, `sendmsg`) in standard POSIX `while (res.returnValue < 0 && res.errno == EINTR) { ... }` loops.

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


## Resolved & WONTFIX Historical Backlog

### 🟢 [RESOLVED]: Temporal State Mutation Leak in `ContainerStateRegistry` via Thread-Local Delegates
**Target:** `io.mazewall.enforcer.ContainerStateRegistry`
**Context:** `ContainerStateRegistry` exposed multiple properties backed by a custom `ThreadLocalDelegate` alongside process-wide state variables under a single interface.
**Needed:** Split `ContainerStateRegistry` into two distinct, strongly-typed components: `ProcessStateRegistry` and `ThreadStateRegistry`. Enforce explicit lifecycle bounds and sanitization routines on the `ThreadStateRegistry` when task execution terminates.
**Resolved:** The registry was split into `ProcessStateRegistry` and `ThreadStateRegistry`. Additionally, `ThreadStateRegistry` now includes an explicit `sanitize()` method that purposefully throws an `UnsupportedOperationException`, documenting why clearing ThreadLocals violates immutable OS sandbox semantics and thus explicitly enforcing the lifecycle bound as "OS thread lifetime".

### 🟢 [RESOLVED]: Tight Coupling and Dependency Inversion Violation in `ProfilerSessionHandler`
**Target:** `io.mazewall.profiler.engine.ProfilerSessionHandler`
**Context:** The `ProfilerSessionHandler` in the `:profiler` module was tightly coupled to concrete implementations.
**Needed:** Refactor `ProfilerSessionHandler` to accept `ProfilerMemoryReader` and `ProfilerTransport` as constructor parameters (context dependencies), allowing mock transports and mock memory readers to be injected during testing.
**Resolved:** The class now accepts the abstract interfaces `ProfilerTransport` and `ProfilerMemoryReader` as constructor parameters, fully decoupling it and enabling tests (like `ProfilerDaemonTest`) to inject mock implementations.

### 🟢 [RESOLVED]: BPF Compiler Macro-Architecture Documentation Drift
**Target:** `io.mazewall.BpfFilter.kt` and `docs/internals/containment_design.md`
**Fix:** `docs/internals/containment_design.md` was updated to accurately reflect the early `BPF_RET` early-return optimization used in `BpfFilter.kt`. The BPF compiler now uses symbolic labels, eliminating the manual `BPF_LD offset=0` restoration logic entirely.

### 🟢 [RESOLVED]: Landlock.applyRestrictiveBarrier() silent fail-open
**Target:** /enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt
**Context:** In applyRestrictiveBarrier(), the calls to LinuxNative.prctl(PR_SET_NO_NEW_PRIVS) and LinuxNative.syscall(LANDLOCK_RESTRICT_SELF_NR) return a SyscallResult. The method ignores the returnValue (and errno) of these calls. If the restrictive barrier fails to apply (e.g., due to Landlock configuration limits or permission errors), the profiler will proceed with no restrictions, bypassing the intended restrictive barrier entirely.
**Needed:** Add checks for returnValue < 0 for both prctl and syscall, throwing an IllegalStateException on failure to adhere to the fail-closed doctrine, matching the logic in enforceRuleset().

### 🟢 [RESOLVED]: Missing `creat` and `mknod` syscalls bypass `PURE_COMPUTE_UNSAFE` filesystem restrictions
**Target:** `io.mazewall.core.Syscall`, `io.mazewall.core.Arch`, `io.mazewall.Policy.PURE_COMPUTE_UNSAFE`
**Fix:** Added `CREAT`, `MKNOD`, and `MKNODAT` to `Syscall` enum and mapped them in `Arch.kt` for amd64 and aarch64. Added these syscalls to the blocklists in `Policy.PURE_COMPUTE_UNSAFE` and `Policy.NO_EXEC`.

### 🟢 [RESOLVED]: Nested Seccomp Stacking Security Containment Bypass on already-blocked Syscalls
**Target:** `io.mazewall.enforcer.FilterInstallationPlanner`
**Failure Hypothesis:** When a user stacked policy contains a more restrictive or more severe action for a syscall that is already blocked by a previously applied policy, the planner incorrectly skips the filter installation under a false optimization path because it only checks if the syscall is "blocked".
**Context & Proof:** `FilterInstallationPlanner.calculateNewFilter` calculates `newBlocks = blockedInPolicy - state.currentlyBlocked`. Any syscall with an action priority > ACT_ALLOW is in `blockedInPolicy`. If a syscall (e.g. `EXECVE`) was blocked by Policy 1 with a lenient action (like `ACT_LOG`), `currentlyBlocked` already contains it. When Policy 2 is nestedly stacked to block `EXECVE` with a severe action (like `ACT_KILL_PROCESS`), `newBlocks` evaluates to empty because it was already blocked. As a result, the optimizer sets `needsNewFilter` to `false`, silently skipping the installation of the second filter. The thread continues executing with only the weaker `ACT_LOG` filter in place, completely bypassing the intended `ACT_KILL_PROCESS` containment.
**Cascading Risk Potential:** High security containment bypass. A stacked policy that is intended to restrict thread capabilities further is ignored, causing RCE/compromised code to execute under weaker sandbox rules than designed.
**Fix:** Modified `currentlyBlocked` to track `Map<Syscall, SeccompAction>` rather than `Set<Syscall>`. In `calculateNewFilter`, `newBlocks` now includes any syscall in the new policy that maps to a *higher priority (more restrictive) action* than the currently installed action for that syscall.

### 🟢 [RESOLVED]: Redundant BPF Argument Inspection Blocks in Stacked Filters
**Target:** `io.mazewall.enforcer.FilterInstallationPlanner`
**Context:** When a stacked policy does not require a full whitelist escalation, a new `Policy` is built dynamically (`toInstall`). However, if `mmap(PROT_EXEC)` is already blocked by a previous filter, the planner still generates and installs the BPF argument inspection instructions again if the new policy also blocks it.
**Fix:** Updated `FilterInstallationPlanner.calculateNewFilter` to explicitly set `builder.allowMmapExec()`, `builder.allowNonThreadClone()`, and `builder.allowUnsafePrctl()` if the `state` indicates they are already protected. This prevents redundant multi-instruction blocks from wasting the 32-filter limit.

### 🟢 [RESOLVED]: Excessive Landlock Directory Capability Leak via Parent Fallback on Non-Existent Path Rules
**Target:** `io.mazewall.landlock.Landlock.kt` (specifically `addRule`)
**Failure Hypothesis:** When a user specifies a file-specific filesystem access rule for a file path that does not yet exist, Landlock's fallback handler opens the parent directory but fails to strip directory-specific actions (`READ_DIR`, `MAKE_DIR`, `REMOVE_DIR`) from the access mask, violating the principle of least privilege.
**Context & Proof:** If a user calls `allowFsWrite("/var/lib/app/settings.json")` (non-existent file) under a custom policy, `addRule` falls back to the parent directory `/var/lib/app` with `isFallback = true`. The `calculateFinalAccess` method only strips `dirOnlyFlags` when `!isFallback && File(resolvedPath).isFile`. Because `isFallback` is `true`, the `dirOnlyFlags` (`READ_DIR | MAKE_DIR | REMOVE_DIR`) are NOT stripped from `writeFlags`. The resulting ruleset grants the thread complete authority to list files (`READ_DIR`), create directories (`MAKE_DIR`), and delete directories (`REMOVE_DIR`) inside the parent `/var/lib/app`, exposing other sensitive files or directories to manipulation or deletion.
**Cascading Risk Potential:** High boundary bypass and integrity risk. An attacker can write to, create, or delete arbitrary files/folders under the parent directory, breaching the intended scope of a single-file rule.
**Fix:** Adjusted `calculateFinalAccess` to strip `dirOnlyFlags` whenever `isFallback` is `true` or if the resolved path is an existing file. This ensures that parent directory fallbacks never leak directory manipulation capabilities.

### 🔴 [Severity: HIGH]: SbobParser Production Crashes due to Syntactic Subpath Pruning of Unresolved/Symlinked Paths
**Target:** `io.mazewall.SbobParser` (specifically `pruneSubpaths`)
**Failure Hypothesis:** SbobParser's subpath pruning operates purely syntactically without resolving symlinks. If a staging environment contains a symlinked directory and a real nested directory, pruning will discard the nested path. When the parsed policy is applied, the symlink is rejected, and because the nested path was pruned, the entire tree is left blocked, causing production application crashes.
**Context & Proof:** In `SbobParser.kt`, `pruneSubpaths` syntactically normalizes and sorts path strings. If a profiled workload accessed both `/var/log` (a symlink) and `/var/log/app` (a real directory), the SBoB JSON lists both. `pruneSubpaths` prunes `/var/log/app` because it syntactically starts with `/var/log`. In production, when `Landlock.addRule` is invoked for `/var/log`, `O_NOFOLLOW` triggers a symlink rejection `ELOOP`, so the rule is skipped and no filesystem rule is added. Since `/var/log/app` was pruned, no rule is added for `/var/log/app` either. The application is completely blocked from accessing `/var/log/app` and crashes.
**Cascading Risk Potential:** High usability and stability risk. Causes deterministic, hard-to-debug runtime crashes in production environments when deploying SBoB policies across varying file systems or symlinks.
**Needed:** SbobParser's subpath pruning must be aware of symlink and directory boundaries, or `addRule` must not prune paths that could fail to resolve. A safer solution is to have SbobParser retain all paths and let `Landlock.applyRuleset` perform dynamic pruning after resolving canonical/real paths in the actual environment, or avoid pruning paths syntactically if they could be symlinks.

### 🟢 [RESOLVED]: Trace Listener Socket Interruption Deadlock due to unhandled `EINTR`
**Target:** `/profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt` (inside `start`)
**Fix:** Extracted `NativeSocketInputStream` and added an explicit retry loop for `EINTR` (errno 4). Verified via targeted unit test with mocked native calls.

### 🟢 [RESOLVED]: Missing `sendmmsg` and `recvmmsg` system calls bypass `NO_NETWORK` and `PURE_COMPUTE_UNSAFE` restrictions
**Target:** `io.mazewall.core.Syscall`, `io.mazewall.Policy.PURE_COMPUTE_UNSAFE`, `io.mazewall.Policy.NO_NETWORK`, and `/profiler/src/main/kotlin/io/mazewall/profiler/compiler/BobCompiler.kt`
**Failure Hypothesis:** A blacklist-based seccomp policy that aims to prevent all outbound networking fails to block alternative or modern socket-sending system calls. An attacker with arbitrary code execution can bypass `NO_NETWORK` or `PURE_COMPUTE_UNSAFE` by invoking these unblocked network system calls.
**Context & Proof:** `Policy.NO_NETWORK` and `Policy.PURE_COMPUTE_UNSAFE` block standard socket operations like `CONNECT`, `SENDTO`, `SENDMSG`, and `SOCKET`. However, they fail to account for `sendmmsg` (system call 307 on x86_64, 269 on aarch64) and `recvmmsg` (system call 299 on x86_64, 268 on aarch64). Because blacklist-based policies default to allowing any system call not explicitly blocked (`defaultAction = ACT_ALLOW`), `sendmmsg` and `recvmmsg` remain unconditionally allowed.
If an attacker achieves native arbitrary code execution (ACE) or has access to a pre-existing socket file descriptor, they can directly invoke `syscall(307, fd, msgvec, vlen, flags)` to transmit network packets, completely bypassing the socket blocklists. Additionally, these system calls are omitted from `Syscall.kt` and thus are also ignored by the `BobCompiler` during trace compilation, creating a complete blind spot in both enforcement and profiling.
**Cascading Risk Potential:** High security sandbox evasion. Enables arbitrary outbound network transmission on contained threads despite active network blocklists.
**Fix:** Added `SENDMMSG` and `RECVMMSG` to `Syscall.kt` and mapped them in `Arch.kt` for x86_64 and aarch64. Added these variants to the block lists in `Policy.PURE_COMPUTE_UNSAFE` and `Policy.NO_NETWORK`.

### 🟢 [RESOLVED]: Design Documentation Drift in Landlock thread-local variable and restrictive method names
**Target:** `/docs/internals/containment_design.md`
**Fix:** `docs/internals/containment_design.md` was updated to accurately reference `THREAD_LANDLOCK_APPLIED_READS`/`THREAD_LANDLOCK_APPLIED_WRITES` and `applyRestrictiveBarrier()`.

### 🟢 [RESOLVED]: `installOnProcess` process-wide seccomp synchronization (TSYNC) fails deterministically on standard JVMs
**Target:** `io.mazewall.seccomp.PureJavaBpfEngine`
**Failure Hypothesis:** Process-wide seccomp installation via `TSYNC` requires `no_new_privs` to be enabled on all threads in the thread group. In standard JVMs, background threads are spawned before `no_new_privs` is set, causing TSYNC to fail with `EACCES` under non-root configurations. The current exception error message is also highly misleading.
**Context & Proof:** The Linux kernel requires `no_new_privs` to be set on all sibling threads in the thread group for `SECCOMP_FILTER_FLAG_TSYNC` to succeed. When the JVM starts, GC threads, JIT threads, and VM helper threads are spawned at startup. In `PureJavaBpfEngine.installInternal`, the main thread calls `setNoNewPrivs()`, which only sets the flag on the *calling* thread. Pre-existing background threads do not get it. When `TSYNC` is attempted, the kernel returns `EACCES` (-13). The method catches this failure and throws an exception claiming "Your kernel may be too old to support SECCOMP_FILTER_FLAG_TSYNC", which is factually incorrect and misleads operators.
**Resolved:** Clarified the exception message to clearly state that `TSYNC` failed due to missing `no_new_privs` on sibling threads, advising operators to run with OCI/Kubernetes `allowPrivilegeEscalation: false` or pre-set `no_new_privs` using an external launcher. Additionally, added a platform diagnostics API (`Platform.diagnose()`) to verify the `no_new_privs` state in-app.

### 🟢 [WONTFIX]: Permanent thread pool contamination, classloader leaks, and state pollution via un-cleared `ThreadLocal` variables
**Target:** `/enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt` and `ContainerStateRegistry.kt`
**Context:** Standard JVM thread pools reuse worker threads. Since the sandbox tracks thread-scoped seccomp and Landlock states using `ThreadLocal` registers but never clears them when a wrapped task finishes, the thread-scoped security state leaks permanently into subsequent tasks on the same thread, causing unexpected `IllegalStateException` throws or ClassLoader memory leaks during redeploys.
**Resolution (WONTFIX):** See resolution for `ContainedExecutors Thread-Local State Persistence and Poisoning` below. Clearing `ThreadLocals` breaks critical deduplication and violates immutable OS sandbox semantics. Users must manage thread pool lifecycles directly (via `shutdown()`) for restricted tasks.

### 🟢 [RESOLVED]: Profiler connection failure on signal interruption inside `recvDescriptor`
**Target:** `/profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemon.kt` (specifically `recvDescriptor`)
**Fix:** `recvDescriptor` in `ProfilerTransport.kt` was updated to wrap the `recvmsg` call in a loop that continues on `EINTR` (`errno == 4`).

### 🟢 [RESOLVED]: Seccomp Filter Bypass via `pkey_mprotect`
**Target:** `io.mazewall.BpfFilter`, `io.mazewall.core.Syscall`, `io.mazewall.seccomp.MmapProtectionTest`
**Failure Hypothesis:** The BPF filter correctly intercepts `mprotect` and `mmap` calls to prevent `PROT_EXEC` via argument inspection (checking `args[2]`). However, it misses modern Linux memory protection variants, specifically `pkey_mprotect` (`SYS_pkey_mprotect` / 329 on AMD64). Since this syscall is not explicitly hooked for argument inspection and may be allowed under loose policies or fallback behavior, an attacker who can call `pkey_mprotect` can mark memory as executable (`PROT_EXEC`), completely bypassing the Seccomp `NO_EXEC` protections designed to stop dynamic shellcode generation.
**Context & Proof:** `pkey_mprotect` takes the same `prot` parameter as `mprotect` but also takes a `pkey`. The current `BpfFilter.kt` only restricts `arch.mmap` and `arch.mprotect`. In `Syscall.kt`, there is no representation of `pkey_mprotect`. Thus, if `pkey_mprotect` is not explicitly blocked or handled via argument inspection like `mprotect`, it will fall back to the default action. Under `Policy.NO_EXEC`, `pkey_mprotect` isn't explicitly blocked, so it would fall to `ACT_ALLOW`, allowing unrestricted `PROT_EXEC` usage. This has been proven via `bypass_pkey.c` where `mprotect` with `PROT_EXEC` is blocked but `pkey_mprotect` with `PROT_EXEC` succeeds in bypassing.
**Vulnerability Chain Potential:** Very high. If an attacker achieves arbitrary code execution (or memory corruption) they can just use `pkey_mprotect` instead of `mprotect` to bypass JIT / dynamic shellcode protections in the sandbox.
**Fix:** Added `PKEY_MPROTECT` to `Syscall`, mapped its number per architecture, and in `BpfFilter.buildFromActions` added it to the same argument inspection block that currently restricts `PROT_EXEC` in `mprotect` and `mmap`. Added tests to `MmapProtectionTest.kt` to guarantee blocking.
**Failure Hypothesis:** A thread pool processing multiple tasks with a whitelist policy (where `defaultAction != ACT_ALLOW`) will unconditionally attach a new, redundant Seccomp BPF filter on every task execution, eventually crashing the thread when the filter limit is reached.

### ✅ [DONE] [Severity: ENHANCEMENT]: Leverage Value Classes for Primitive Safety (Internal)
**Target:** `io.mazewall.LinuxNative.kt`, `io.mazewall.ffi.Layouts.kt`
**Context:** We currently use raw `Int` for File Descriptors and `Errno`, and `Long` for masks and addresses. This is prone to "parameter swapping" bugs.
**Needed:** Introduce `@JvmInline value class` for internal types like `FileDescriptor`, `Errno`, and `MemoryAddress`. Use these internally to enforce compile-time safety. To maintain Java compatibility, keep the public API surface (e.g., `ContainedExecutors`) using primitives, but use value classes for all internal FFM and logic layers.

### ✅ [DONE] [Severity: ENHANCEMENT]: Result-Oriented Functional Error Handling
**Target:** `io.mazewall.NativeEngine.kt` and callers
**Context:** We currently rely on manual `returnValue < 0` checks and `LinuxNative.errno()` calls. This is a common source of missed error handling.
**Needed:** Wrap internal syscall returns in a monadic `Result<T>` or a custom `SyscallResult` type. This forces developers to explicitly handle the `Failure` branch before accessing the result, aligning with modern functional programming safety standards.

### 🟢 [RESOLVED]: `ContainedExecutors.kt` violates single-responsibility at the API surface
**Target:** `io.mazewall.enforcer.ContainedExecutors`
**Fix:** `ContainedExecutors` was refactored, and the inner class `ContainedExecutorWrapper` was extracted into its own file (`enforcer/internal/ContainedExecutorWrapper.kt`).

### 🟢 [WONTFIX]: `ContainedExecutors` Thread-Local State Persistence and Poisoning
**Target:** `io.mazewall.enforcer.ContainedExecutors.kt` and `ContainerStateRegistry.kt`
**Context:** `ContainedExecutorWrapper` calls `applyContainment()` on every task execution, but it never clears the tracking `ThreadLocals`. Because worker threads are reused in a pool, any subsequent task scheduled on the same OS thread will inherit the `mazewall` state of the previous task, even if it's supposed to be uncontained or have a different policy. The original proposal was to implement a `try-finally` cleanup to clear all registers in `ContainerStateRegistry` when a contained task completes to prevent ClassLoader memory leaks on application redeploys.
**Resolution (WONTFIX):** Seccomp filters and Landlock domains are permanent and immutable for the lifetime of an OS thread. They cannot be removed or reverted. If we clear the `ThreadLocal` JVM tracking state when a task completes:
1. The JVM loses track of the permanent OS restrictions.
2. The next task on the same thread will evaluate an "empty" JVM state and redundantly re-apply the identical Landlock domain and Seccomp filters.
3. This completely breaks deduplication. If a thread processes 16 tasks, it hits the Landlock `E2BIG` stacked domain limit and crashes. If it processes 32 tasks, it hits the Seccomp stacked filter limit and crashes.
4. If a task with a *different* policy runs, the OS will silently enforce the intersection of both policies, leading to obfuscated `EPERM` crashes. Keeping the `ThreadLocal` intact allows the JVM to fail-fast with an `IllegalStateException` ("Cannot expand Landlock filesystem permissions on an already restricted thread"), properly warning the user that they are violating the immutable OS sandbox semantics.

**The Correct Solution:** Developers MUST NOT share thread pools between differently-sandboxed tasks. Restricted tasks must run on a dedicated `ExecutorService` that is shut down (`executor.shutdown()`) when the application/container stops. Shutting down the executor kills the OS threads, inherently cleaning up both the ClassLoader references and the permanent OS sandboxes without any memory leaks.

### 🟢 [RESOLVED]: Profiler daemon reactor loop spins at 100% CPU on delayed ACK bytes
**Target:** `io.mazewall.profiler.engine.ProfilerSessionHandler.kt` (specifically `handleShutdownRequest`)
**Fix:** `handleShutdownRequest` in `ProfilerSessionHandler.kt` was modified to use `transport.recv(..., 0)` to properly consume bytes instead of peeking.

### 🟢 [RESOLVED]: Profiler daemon `waitForParentAck` enters infinite polling loop on timeout
**Target:** `io.mazewall.profiler.engine.ProfilerSessionHandler.kt` (specifically `waitForParentAck`)
**Fix:** `waitForParentAck` in `ProfilerSessionHandler.kt` was corrected to return `false` if `pollRes.returnValue == 0L`, indicating a timeout.

### 🟢 [RESOLVED]: Profiler Daemon Socket Connection Polling Loop Smell
**Target:** `io.mazewall.profiler.internal.ProfilerSocket.kt` (specifically `connectWithRetry`)
**Fix:** Implemented zero-latency event-driven startup synchronization. The `ProfilerDaemon` now prints a `MAZEWALL_DAEMON_READY` sentinel to stdout when listening. The `ProfilerDaemonManager` uses a `CountDownLatch` and a dedicated stdout reader thread to wait for this sentinel before attempting a connection. This eliminates the artificial 1-second startup delay and reduces connection polling latency to 10ms.

### ✅ [RESOLVED]: `JitWarmup` and `-Xint` removed from `ContainedExecutors`
**Context:** `JitWarmup.perform()` attempted to pre-trigger class loading and JIT compilation before seccomp was applied to avoid lazy-class-loading `EPERM` crashes. This only applied to `PURE_COMPUTE_UNSAFE` (which blocks `openat`). `PURE_COMPUTE` (with Landlock) does not block `openat`, making warmup unnecessary for the recommended preset. `-Xint` was added to `IsolatedProcessTester` as a band-aid to prevent JIT-related failures in test subprocesses.
**Fix:** `JitWarmup.kt` deleted. Both `JitWarmup.perform()` call sites removed from `ContainedExecutors`. `-Xint` removed from `IsolatedProcessTester`. `ContainedExecutors` KDoc updated to document the `PURE_COMPUTE` vs `PURE_COMPUTE_UNSAFE` class-loading contract.

### ✅ [RESOLVED]: `allowMmapExec=false` silently kills JIT on process-wide DENY_LIST policies
**Target:** `Policy.NO_NETWORK` KDoc, `containment_design.md §3f`
**Context:** `allowMmapExec` defaults to `false` on ALL policies, including DENY_LIST presets like `NO_NETWORK`. When installed process-wide via `installOnProcess()`, the BPF filter applies to JIT compiler background threads, blocking their `mmap(PROT_EXEC)` code-cache allocation calls. Result: fatal JVM abort (`os::commit_memory failed; error='Operation not permitted'`). Discovered by removing `-Xint` from `IsolatedProcessTester` — the flag had been masking this crash in integration tests.
**Fix:** Added `### JIT Compiler Warning` to `Policy.NO_NETWORK` KDoc documenting the footgun and the correct workaround (`Policy.builder().base(NO_NETWORK).allowMmapExec().build()`). Added `§3f` to `containment_design.md` with the full failure pattern. Fixed `testNioStability()` in `ProcessContainmentTest` to use the correct derived policy.

### ✅ [RESOLVED]: ALLOW_LIST policies that block `openat` require targeted class pre-loading
**Target:** `AllowListTest.preWarm()`, `containment_design.md §3g`
**Context:** When `defaultAction = ACT_ERRNO` (ALLOW_LIST), `openat` is blocked unless explicitly in the allow set. Classes referenced by `PureJavaBpfEngine` immediately after filter installation (specifically `SeccompInstallationState$Failed`) are loaded lazily via `openat`. After the filter blocks `openat`, these classes can no longer be loaded → `NoClassDefFoundError`. The old `JitWarmup` attempted to solve this globally but was fragile and non-deterministic. The correct fix is targeted: explicitly touch the exact class graph that will be used post-installation, in the specific test/component that uses the restrictive ALLOW_LIST policy.
**Fix:** Extended `AllowListTest.preWarm()` to touch all `SeccompInstallationState` subclasses before the filter is installed. Added `§3g` to `containment_design.md` documenting the rule and its scope.

### ✅ [RESOLVED]: `LandlockTest` isolated subprocesses crash on JIT startup inside nested seccomp container
**Target:** `LandlockTest.kt` (integrationTest), 12 policy builders
**Context:** All 12 test methods in `LandlockTest` that run in isolated subprocesses (via `IsolatedProcessTester.runIsolatedMethod()`) used `.base(Policy.NO_EXEC)` without `.allowMmapExec()`. The `Policy.NO_EXEC` preset has `allowMmapExec = false` by default, which emits `mmap(PROT_EXEC)` argument-inspection in the BPF filter. Inside the Testcontainer's nested seccomp environment (which already restricts `mmap(PROT_EXEC)` at the host level), the isolated subprocess JVM crashes immediately at startup when the JIT compiler tries to allocate code cache pages. Manifests as: `os::commit_memory(..., 65536, 1) failed; error='Operation not permitted' (errno=1)`. These tests are validating Landlock filesystem restrictions — not mmap(PROT_EXEC) behavior — so allowing JIT is correct.
**Fix:** Added `.allowMmapExec()` to all 12 policy builders in `LandlockTest.kt`.

### 🟢 [RESOLVED]: Sealed Class State Machines for Kernel Lifecycles
**Target:** `io.mazewall.landlock.LandlockSession` and `io.mazewall.seccomp.SeccompInstallationState`
**Fix:** Transitioned both Seccomp and Landlock installation lifecycles to compile-time safe, compiler-enforced Type-State transition chains using sealed classes and interfaces.

### 🟢 [RESOLVED]: Immutability and Consistency in `ProcessStateRegistry`
**Target:** `io.mazewall.enforcer.ProcessStateRegistry`
**Fix:** Replaced the concurrent mutable `ConcurrentHashMap` with an `AtomicReference<Map<Syscall, SeccompAction>>` containing an immutable Map, and updated updates to run in Compare-And-Swap (CAS) merge loops.

### 🟢 [RESOLVED]: `SbobParser` fails to parse JSON Unicode escape sequences (`\uXXXX`)
**Target:** `io.mazewall.SbobParser`
**Fix:** Completely removed the handwritten, custom `JsonTokenizer` state machine and switched to `kotlinx.serialization` for parsing SBoB JSON files, which handles Unicode escapes natively and correctly out of the box.

### 🟢 [RESOLVED]: Sealed Interface `NativeArg` for Syscall Parameter Safety
**Context:** The `NativeEngine` and `LinuxNative.syscall` methods used `Any?` for system call arguments, which were then converted to `Long` via a runtime `when` expression in `RealNativeHelper.toLong`. This was a type-safety hole that allowed passing unsupported types, leading to runtime `IllegalArgumentException`.
**Fix:** Introduced the `NativeArg` sealed interface and its concrete subclasses (`LongArg`, `IntArg`, `MemoryArg`, `FdArg`, `PidArg`, etc.). Refactored `NativeEngine`, `LinuxNative`, and call sites to use `NativeArg`, ensuring compile-time safety and eliminating dynamic runtime casts.

### ✅ [RESOLVED]: Monadic Combinators for `SyscallResult`
**Context:** Currently, native system calls return `SyscallResult` which requires manual `when` branching or `.getOrThrow()` calls. This leads to imperative boilerplate and potential unhandled errors.
**Fix:** Refactored `SyscallResult` to `SyscallResult<out T>` and added standard functional combinators like `map`, `flatMap`, `recover`, `onSuccess`, and `onFailure`.

### ✅ [RESOLVED]: Value Class Completeness (Primitive Obsession)
**Context:** While value classes were introduced for `FileDescriptor` and `Errno`, many other native concepts (like `MemoryAddress`, `Pid`, and `Uid`) were still passed as raw `Long` or `Int` primitives.
**Fix:** Introduced `Pid`, `Uid`, and `MemoryAddress` value classes and integrated them into the `NativeEngine` interfaces and `RealNativeHelper`.

### ✅ [DONE] [Severity: MEDIUM]: Lack of Compile-Time Enforced Memory and Lifetime Safety for FFM Native Bindings
**Target:** `io.mazewall.enforcer` (core FFM bindings and MemorySegment management)
**Context:** Currently, FFM native memory allocations (`MemorySegment`) and lifecycle management (`Arena`) are managed through raw, imperative calls. While standard unit tests and JIT warmups verify these boundaries at runtime, developers can easily introduce memory safety bugs (such as Use-After-Close temporal violations, offset alignment spatial errors, or thread-confinement violations) that bypass compiler checks. Kotlin's modern type system and compiler features could be leveraged to enforce these invariants statically.
**Needed:** Define and adopt the following compile-time safety patterns across the codebase:
1. **Temporal Safety via Scoped Lambdas:** Hide raw `Arena` creation behind scoped inline utility functions (e.g., `nativeScope { ... }`) that receive `Arena` as a Context Parameter or receiver, ensuring segments cannot escape their bounded lifetimes.
2. **Spatial/ABI Safety via Kotlin Value Classes (or Java Records):** Wrap raw `MemorySegment` structs in Kotlin `@JvmInline value class` wrappers that encapsulate layout offsets. This guarantees that offset math is hidden from developers and type-checked by the compiler with zero runtime allocation overhead.
3. **Confinement Safety via Sealed State Hierarchies:** Model segment ownership transitions (e.g., thread-confined vs. shared states) using sealed interfaces to enforce safe multi-threaded segment handling via compile-time exhaustive checks.
4. **Static ArchUnit Checks:** Enforce lint-level restrictions on raw `MemorySegment` access (e.g., prohibiting raw `.get()` or `.set()` calls outside of dedicated `bindings` or wrapper classes).

**Memory Management (MM) Blind Spots & Mitigations:**
Even with compile-time enforcements, the lack of a native borrow checker introduces four critical blind spots:
*   **The Escape Bypass (Temporal Leak):** Scoped lambdas (`nativeScope`) do not prevent `MemorySegment` references from being returned and escaping the closed arena scope.
    - *Mitigation:* Restrict `nativeScope` functions to return only primitive values, arrays, or deep-copied Java heap structures. Enforce this via ArchUnit checks that block returning raw segments or wrappers from scopes.
*   **Dangling Native Pointers (Un-tracked Lifetime Dependencies):** Storing a pointer (memory address) to a struct allocated in a short-lived arena inside a struct allocated in a longer-lived arena results in a dangling pointer when the child arena is closed.
    - *Mitigation:* Establish strict coding standards requiring hierarchically nested structs to be allocated within the *same* `Arena` instance.
*   **GC-Managed Auto Arenas (`Arena.ofAuto()`):** GC-managed segments can be cleaned up while their raw memory addresses are still referenced by native code or kernel structures.
    - *Mitigation:* Never use `Arena.ofAuto()` for segments whose addresses are passed to the Linux kernel (like BPF filters or UNIX sockets). Use confined, explicitly closed arenas bound to thread lifecycles.
*   **Reinterpret Bounds Bypasses:** Developers can extract raw segments from type-safe wrappers and invoke `MemorySegment.reinterpret(Long.MAX_VALUE)` to reset bounds.
    - *Mitigation:* Block `reinterpret()` calls at the linter/ArchUnit level except within audited native bootstrap bindings.

### ✅ [DONE] [Severity: HIGH]: Interface Segregation Violation and Fat Class Smell in `LinuxNative` / `RealNativeEngine`
**Target:** `io.mazewall.LinuxNative`, `io.mazewall.NativeEngine`, `io.mazewall.RealNativeEngine`
**Context:** `LinuxNative` and `RealNativeEngine` implemented *all* segment-specific native engine interfaces directly, turning them into monolithic, fat classes.
**Needed:** Decouple `LinuxNative` from the massive inheritance hierarchy. Instead of inheriting all traits, `LinuxNative` should delegate to individual, modular sub-engines (e.g. `engine.fileSystem`, `engine.networking`) that implement only their specific interface. `MockNativeEngine` can then be composed of specific mock sub-engines.
**Resolved:** Redefined `NativeEngine` as a container of `fileSystem`, `networking`, `process`, and `memory` sub-engines. `RealNativeEngine` now delegates to specialized internal objects, and `LinuxNative` entry point was refactored to use property-based access, removing monolithic top-level delegates.

### ✅ [RESOLVED]: Landlock Symlink Rejection Bypass via Canonicalization
**Context:** The Landlock documentation states that rules explicitly use `O_NOFOLLOW` to reject symlinks and prevent attackers from redirecting path rules. However, `addRule` called `SandboxedPath.of` which used `toRealPath()`, silently bypassing this protection.
**Fix:** Switched to syntactic normalization (`Paths.get(path).toAbsolutePath().normalize()`) in `SandboxedPath.of`. This defers symlink resolution to the kernel, which then correctly rejects links via `O_NOFOLLOW`.

### ✅ [RESOLVED]: Blacklist policies trigger silent Landlock filesystem lockdown due to `io_uring` check
**Context:** Landlock was automatically triggered if `io_uring` syscalls were allowed. If no FS rules were provided, this resulted in a total FS lockdown.
**Fix:** Removed the `io_uring` check from `Landlock.shouldApplyLandlock`.

EOF

ystem`, `engine.networking`) that implement only their specific interface. `MockNativeEngine` can then be composed of specific mock sub-engines.
**Resolved:** Redefined `NativeEngine` as a container of `fileSystem`, `networking`, `process`, and `memory` sub-engines. `RealNativeEngine` now delegates to specialized internal objects, and `LinuxNative` entry point was refactored to use property-based access, removing monolithic top-level delegates.

### ✅ [RESOLVED]: Landlock Symlink Rejection Bypass via Canonicalization
**Context:** The Landlock documentation states that rules explicitly use `O_NOFOLLOW` to reject symlinks and prevent attackers from redirecting path rules. However, `addRule` called `SandboxedPath.of` which used `toRealPath()`, silently bypassing this protection.
**Fix:** Switched to syntactic normalization (`Paths.get(path).toAbsolutePath().normalize()`) in `SandboxedPath.of`. This defers symlink resolution to the kernel, which then correctly rejects links via `O_NOFOLLOW`.

### ✅ [RESOLVED]: Blacklist policies trigger silent Landlock filesystem lockdown due to `io_uring` check
**Context:** Landlock was automatically triggered if `io_uring` syscalls were allowed. If no FS rules were provided, this resulted in a total FS lockdown.
**Fix:** Removed the `io_uring` check from `Landlock.shouldApplyLandlock`.

EOF

