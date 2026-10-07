---
title: "Technical Design: Containment Engine & Incremental Filter Stacking"
scope: "enforcer"
target_files: ["enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt", "enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt", "enforcer/src/main/kotlin/io/mazewall/enforcer/BpfFilter.kt", "enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt"]
keywords: ["seccomp", "bpf", "landlock", "filter stacking"]
---

# Technical Design: Containment Engine & Incremental Filter Stacking

This document covers the implementation details of the enforcement tier in `mazewall`:
how policies become live BPF filters, how filters stack incrementally, and how Landlock
integrates with the seccomp pipeline. Read this before modifying `ContainedExecutors.kt`,
`PureJavaBpfEngine.kt`, `BpfFilter.kt`, or `Landlock.kt`.

---

## 1. The Enforcement Pipeline

```
Policy (syscallActions: Map<Syscall, SeccompAction>, defaultAction: SeccompAction, allowMmapExec, ...)
    │
    ▼
BpfFilter.build(arch, policy)
    │  - architecture check prologue (AUDIT_ARCH_* constant)
    │  - clone3 ENOSYS trap (unconditional)
    │  - argument-inspection sequences for mmap/mprotect, clone, prctl
    │  - linear scan over restricted syscall numbers (sorted IntArray)
    │  - default ALLOW (DENY_LIST) or DENY (ALLOW_LIST) at the tail
    │
    ▼  List<BpfInstruction>
PureJavaBpfEngine.install(policy)  /  installOnProcess(policy)
    │  1. prctl(PR_SET_NO_NEW_PRIVS, 1)
    │  2. pack sock_fprog struct via FFM Arena
    │  3. syscall(seccomp, SECCOMP_SET_MODE_FILTER, flags, &prog)
    │  4. fallback: prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)   [thread-local only]
    │  5. verify: prctl(PR_GET_SECCOMP) == 2
    │
    ▼
ContainedExecutors.installInternal(processWide, vararg policies)
    │  - deduplicates against THREAD_BLOCKED / PROCESS_BLOCKED state
    │  - enforces 32-filter depth limit
    │  - synchronized(processLock) to prevent TOCTOU race
    │  - updates thread-local or process-wide tracking state
```

### 1a. Architecture: Decoupled Transaction & Feature Detection

The enforcement engine follows a strict **Dependency Inversion** pattern to ensure testability and version-resiliency:

*   **TransactionManager:** The FFM `Arena` lifecycle and `NativeTransaction` boundaries are decoupled from `LinuxNative`. This allows `mazewall` to be tested using a `MockNativeEngine` without requiring a real Linux kernel, and enables future optimizations like thread-local arena pooling.
*   **KernelFeatureMatrix:** Instead of static version checks, `mazewall` uses a cached, probe-based `KernelFeatureMatrix` (via `Platform.featureMatrix`). This matrix detects capabilities like Seccomp TSYNC, Landlock ABI versions, and USER_NOTIF support at initialization, allowing the engine to fail-closed or safely degrade based on `FallbackBehavior`.
*   **SyscallInspectionPipeline:** Argument-inspection generation (for `mmap`, `clone`, etc.) is implemented as a pipeline (`SyscallInspectionPipeline`). This enforces the **Open/Closed Principle**, allowing new inspections (e.g., for `openat2`) to be added without modifying the core `BpfFilter` compiler.

---

## 2. Why a Linear BPF Scan (Not a BST)

BPF jump offsets are 8-bit unsigned integers — the maximum forward jump is 255 instructions.
A Binary Search Tree layout for even a moderately sized syscall set (e.g. `PURE_COMPUTE_UNSAFE`'s
~40 syscalls) can require jumps that exceed this limit, requiring complex instruction
rewriting or label patching.

The linear scan emits two instructions per restricted syscall number. The logic
is inverted based on the policy mode:

**DENY_LIST mode:**
```
BPF_JEQ(nr)  → jt=0, jf=1     # if nr matches: jt=0 (no skip) → fall into RET_DENY
BPF_RET(DENY)                  # if nr does not match: jf=1 (skip this RET_DENY)
```

**ALLOW_LIST mode:**
```
BPF_JEQ(nr)  → jt=0, jf=1     # if nr matches: jt=0 (no skip) → fall into RET_ALLOW
BPF_RET(ALLOW)                 # if nr does not match: jf=1 (skip this RET_ALLOW)
```

In Classic BPF, `jt` and `jf` are **instruction skip counts** (not jump labels).
`jt=0` means "if condition is true, skip 0 instructions" (execute the next instruction immediately).
`jf=1` means "if condition is false, skip 1 instruction" (skip over the `RET_DENY`).

For 40 syscalls this produces ~80 BPF instructions — far below both the 255-offset
and the 4096-instruction kernel BPF limits. Performance: BPF is evaluated in the kernel
before the syscall; the linear scan overhead is negligible compared to the syscall itself.

---

## 3. Argument-Inspection Sequences

`BpfFilter` does **not** block these syscalls by number — it emits multi-instruction
BPF sequences that inspect arguments. **Do not replace these sequences with simple
`BPF_JEQ` checks against the syscall number.** Doing so silently removes the nuanced
protections.

### 3a. `mmap` and `mprotect` — block `PROT_EXEC`

`struct seccomp_data` byte offsets: `nr`=0, `arch`=4, `instruction_pointer`=8 (8 bytes), `args[0]`=16, `args[1]`=24, `args[2]`=32, ...
For `mmap`/`mprotect`, `prot` is the 3rd argument → `args[2]` → **byte offset 32**.

```
BPF_JEQ(syscall_nr == mmap, match, skip_3)
BPF_LD offset=32                             # load args[2] (prot) lower 32 bits
BPF_JSET 0x04, match, skip_1               # (BPF_JMP|0x40|BPF_K): if PROT_EXEC bit set, jt=0 (deny); else jf=1 (skip deny)
BPF_RET(DENY)
# identical 3-instruction sequence for mprotect (syscall_nr == mprotect)
```

> **`JSET` semantics:** `jt=0, jf=1` — if `(ACC & 0x04) != 0` (PROT_EXEC IS set): jt=0, execute `RET_DENY` immediately; else: jf=1, skip over `RET_DENY`.

This blocks only `mmap` / `mprotect` calls where the caller requests executable memory.
The JVM's own JIT and GC `mmap`/`mprotect` calls do not use `PROT_EXEC` in the way
that shellcode injection does — they are whitelisted by this check automatically.

### 3b. `clone` — allow only `CLONE_THREAD`

`clone` flags are the 1st argument → `args[0]` → **byte offset 16**.
`CLONE_THREAD = 0x00010000`, `CLONE_VM = 0x00000100`, mask = `0x00010100`.

```
BPF_JEQ(syscall_nr == clone, match, skip_4)
BPF_LD offset=16                          # load args[0] (clone flags)
BPF_ALU|BPF_AND|BPF_K (0x54) 0x00010100  # mask to CLONE_VM | CLONE_THREAD
BPF_JEQ(0x00010100, skip_1, match)        # both bits set → JVM thread creation → skip deny
BPF_RET(DENY)
```

> **`BPF_AND` note:** There is no standalone `BPF_AND` opcode. This is `BPF_ALU | BPF_AND | BPF_K` = opcode `0x54`. The operand `K` is the mask value.

The JVM creates threads via `clone(CLONE_THREAD | CLONE_VM | CLONE_SIGHAND | ...)`.
Process forking (`fork`, `vfork`, or `clone` without `CLONE_THREAD`) hits `DENY`.
An agent who naively adds `Syscall.CLONE` to a policy's block list would bypass
this inspection and deadlock the JVM at the next thread creation.

### 3b. Int-ABI arguments — `EqualsAny32` low-word comparisons
For `int`-defined arguments (prctl option, socket domain) use `ArgCheck.EqualsAny32`: emit
LD_ABS of ONLY the args[i] LO word followed by per-value JEQ. The HI word of the u64 slot is
unspecified garbage on int ABI and must never participate in the comparison (issue-075 p3).
Adopted: UnsafePrctlInspector, SocketAddressFamilyInspector.

### 3c. `clone3` — always return `ENOSYS`

`clone3` is blocked unconditionally with `ENOSYS` (not `EPERM`) to force libc to
fall back to the inspectable `clone`. This is not controlled by `allowNonThreadClone`.

### 3d. `prctl` — whitelist safe options

```
BPF_JEQ(syscall_nr == prctl, match, skip_8)
BPF_LD(args[0])                    # load option
BPF_JEQ(PR_SET_NAME (15), allow, ...)
BPF_JEQ(PR_GET_NAME (16), allow, ...)
BPF_JEQ(PR_GET_SECCOMP (21), allow, ...)
BPF_JEQ(PR_SET_SECCOMP (22), allow, ...)
BPF_JEQ(PR_SET_NO_NEW_PRIVS (38), allow, ...)
BPF_JEQ(PR_GET_NO_NEW_PRIVS (39), allow, ...)
BPF_RET(DENY)
```

The JVM calls `prctl(PR_SET_NAME)` constantly for thread naming. Blocking `prctl`
outright (via `Syscall.PRCTL` in the block list) is **not safe and must never be done**:
it would block the whitelisted options and prevent `PR_SET_SECCOMP` from being called
during filter-stacking. The argument-inspection path above restricts `prctl` to the
safe subset while leaving installation and naming intact.

> **`allowUnsafePrctl = true`:** Setting this flag on the policy skips the argument
> inspection entirely, leaving `prctl` unrestricted. Only use this when filter stacking
> is explicitly required beyond the whitelisted options.

### 3e. Intentionally Unrestricted JVM Syscalls

The following syscalls are **deliberately not present in any argument-inspection sequence
or block-list**. They are left completely unrestricted because blocking them causes
catastrophic JVM instability:

| Syscall        | Reason left unrestricted                                                                                                                |
|----------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `futex`        | JVM thread parking/unparking and monitor synchronization. Blocking causes deadlock at the next `synchronized` block or `Object.wait()`. |
| `sched_yield`  | Thread scheduler cooperation during spinlock contention.                                                                                |
| `rt_sigreturn` | Return from JVM signal handlers (HotSpot uses `SIGSEGV` for safepoint polling). Blocking causes instant JVM abort.                      |
| `rt_sigaction` | HotSpot installs its own signal handlers at JVM init. Blocking prevents the JVM from starting.                                          |
| `madvise`      | GC page lifecycle management (ZGC, G1).                                                                                                 |
| `gettid`       | Thread identification used by GC and diagnostic paths.                                                                                  |
| `close`        | JVM and FFM close file descriptors constantly. Blocking causes fd leaks and runtime instability.                                        |

Do not add any of these to a policy's block-list. See `designs/core/security-considerations.md §9`
for the full safepoint/GC deadlock analysis.

### 3f. `allowMmapExec = false` Is a Hidden Default for ALL Policies

`allowMmapExec` defaults to `false` on every `Policy.Builder`, including **DENY_LIST**
(default-ALLOW) policies like `NO_NETWORK` and `NO_EXEC`. Regardless of a policy's
`defaultAction`, if `allowMmapExec` is `false`, the BPF program emits the `mmap(PROT_EXEC)`
argument-inspection sequence that blocks `mmap` calls with the `PROT_EXEC` bit set.

**Consequence for process-wide installation**: When `ContainedExecutors.installOnProcess()`
is called with any policy where `allowMmapExec = false`, the BPF filter applies to **all
threads**, including the JVM's JIT compiler background threads. The JIT compiler allocates
code-cache pages via `mmap(PROT_EXEC)`. These calls are blocked, causing:

```
os::commit_memory(addr, size, 1) failed; error='Operation not permitted' (errno=1)
# Native memory allocation (mmap) failed to map N bytes.
# JVM crashes fatally.
```

**Correct pattern when blocking network but NOT JIT:**
```kotlin
// WRONG — kills the JIT compiler process-wide:
ContainedExecutors.installOnProcess(Policy.NO_NETWORK)

// CORRECT — restricts network, leaves JIT alone:
ContainedExecutors.installOnProcess(
    Policy.builder().base(Policy.NO_NETWORK).allowMmapExec().build()
)
```

The preset `NO_EXEC` documents this and is **not** the process-wide HotSpot recipe.
Use `Policy.NO_EXEC_HOTSPOT` (or `.allowMmapExec()`) when installing on a JIT JVM.
`NO_NETWORK` and any custom DENY_LIST
policy must be treated with equal care.

### 3g. ALLOW_LIST Policies and Lazy Class Loading

When a policy uses `defaultAction = ACT_ERRNO` (allow-list mode), all syscalls not in the
explicit allow set are blocked — including `openat` and `open`. The JVM loads classes
lazily: a class is only loaded from its `.jar` via `openat` the first time it is referenced.

**Consequence**: If `openat` is not in the allow-list, **any class not yet loaded at filter
installation time cannot be loaded afterward**. This produces:

```
java.lang.NoClassDefFoundError: io/mazewall/seccomp/SeccompInstallationState$Failed
```

even though the class is on the classpath. The class loader makes an `openat` call,
which is blocked, causing `ClassNotFoundException`, which is wrapped as `NoClassDefFoundError`.

**Rule**: When writing code that will execute under an ALLOW_LIST policy that excludes
`openat`, all classes that code will reference must be explicitly touched (loaded)
**before** the filter is installed. This is distinct from `JitWarmup` (which was a
misguided global warm-up): this is **targeted pre-loading of the specific class graph**
that will execute under the restricted policy.

```kotlin
// Pre-load all internal state classes before installing the ALLOW_LIST filter.
SeccompInstallationState.Failed::class.java       // touched → loaded now
SeccompInstallationState.Verified::class.java     // touched → loaded now
// ... install ALLOW_LIST filter ...
// SeccompInstallationState.Failed can now be instantiated without openat.
```

This requirement does NOT apply to `PURE_COMPUTE` (which uses Landlock for filesystem
enforcement and does not block `openat` in the BPF filter), or to any DENY_LIST policy
(where `openat` is allowed by the default-ALLOW action).

---

## 4. Incremental Filter Stacking

### Why stacking exists

Seccomp filters are monotonic: once a syscall is blocked on a thread, it cannot be
unblocked. The kernel accumulates filters in a linked list; each new filter is evaluated
in order, and the most-restrictive result wins. `ContainedExecutors` stacks filters
incrementally to allow multiple `installOnCurrentThread` calls (e.g. from nested
executor wrapping or the profiler) without wasting filter slots.

### How deduplication works

`ContainedExecutors` maintains an immutable **`ContainerState`** data class, updated atomically via `ThreadStateRegistry` (ThreadLocal) and `ProcessStateRegistry` (AtomicReference). This ensures a consistent view of the sandbox state across concurrent installations.

Before installing, `installInternal` computes:
- `newBlocks`: Syscalls in the new policy that map to a *higher priority (more restrictive) action* than the currently installed action.
- `needsMmapProtection`, `needsCloneProtection`, `needsPrctlProtection`: Gates for argument-inspection blocks.

**Optimization (Optimized Stacking):** Seccomp BPF filters are additive. If a previous filter already restricts `mmap(PROT_EXEC)`, non-thread `clone`, or unsafe `prctl` calls, `FilterInstallationPlanner` skips compiling duplicate argument-inspection blocks for these syscalls in the new stacked filter, preserving kernel instruction memory and the 32-filter depth budget.


### The `synchronized(processLock)` requirement

The deduplication check and the state update are inside `synchronized(processLock)`.
This prevents two threads from simultaneously calculating "this syscall is not yet
blocked" and both installing redundant filters, which would waste depth slots.
**Do not remove or weaken this lock.**

### The 32-filter depth limit

The kernel hard limit is 32 seccomp filters per thread (thread-local + process-wide combined).
- Depth ≥ 32: `IllegalStateException` is thrown before the install.
- Depth > 10: a `WARNING` log is emitted. Do not suppress this.

Exceeding 32 causes the next `seccomp()` call to return `EINVAL`, silently leaving
the policy partially unenforced. The current limit of 32 is enforced by the kernel
regardless of any library-level tracking.

### Process-wide vs. thread-local path

```
installOnProcess()  →  PureJavaBpfEngine.installOnProcess()
    - uses SECCOMP_FILTER_FLAG_TSYNC
    - TSYNC synchronises the new filter to **all threads in the same thread group,
      including those created after the call**
    - if seccomp(2) + TSYNC fails → throws `IllegalStateException` immediately;
      **never falls back to thread-local installation**
    - Kernel 5.7+ changed TSYNC semantics: pre-5.7, any mismatch returns `EINVAL`;
      5.7+ returns `EINVAL` only for true incompatibilities. In all cases,
      `mazewall` treats any TSYNC failure as a hard error.
    - updates PROCESS_BLOCKED, PROCESS_FILTER_DEPTH, PROCESS_ALLOWS_* atomics

installOnCurrentThread()  →  PureJavaBpfEngine.install()
    - uses flags=0 (thread-local only)
    - falls back to prctl(PR_SET_SECCOMP) on old kernels
    - updates THREAD_BLOCKED, FILTER_DEPTH, THREAD_ALLOWS_* ThreadLocals
```

`SECCOMP_FILTER_FLAG_TSYNC` and `SECCOMP_FILTER_FLAG_NEW_LISTENER` are mutually
exclusive. `NEW_LISTENER` is used exclusively by `Profiler`. Never combine them.

---

## 5. Landlock Integration

### Ordering requirement

Landlock installation calls `landlock_create_ruleset(2)`, `landlock_add_rule(2)`,
and `landlock_restrict_self(2)` — all standard syscalls. If seccomp is installed
first with a policy that blocks `openat` (e.g. `PURE_COMPUTE_UNSAFE`), these Landlock
syscalls fail before Landlock can activate.

**Enforced order in `ContainedExecutors.installInternal`:**
1. `applyLandlockIfNecessary(processWide, policy)`  ← first
2. `installSeccompFilter(...)`                      ← second

Wrapper wrap methods only call `installOnCurrentThread`, which reaches `installInternal`.

Dedicated child processes use the internal `installOnDedicatedProcess` entry. It accepts
filesystem policies only with Landlock TSYNC support, rejects fallback, and delegates to
the same process-wide installation transaction. It therefore records successful irreversible
stages before returning or throwing and preserves the ordering above. Portal workers require
this permanent baseline before dispatcher construction or readiness publication.

Do not change this order.

Installation fallback is captured once by `InstallationAttempt` before capability
probes/native stages. Platform rejection, CET failure handling, Landlock ABI/right
validation and errno classification, and receipt construction use that selection.
Standalone `LandlockSession` construction captures its own selection. Failed/bypassed
receipts preserve the original failure or unsupported-feature reason and the newly
applied Seccomp/Landlock progress snapshot. `requireInstalled()` rethrows a recorded
failure; successful inherited coverage can therefore have an empty attempt delta.
Receipt/progress/failure classes are preloaded before restrictive class-read rules
can make diagnostic allocation fail. This does not broaden the compiled policy.

`LandlockSession` captures its backend and structured kernel capabilities at
construction. `LandlockRuleset` retains the selected native owner; path operations,
add-rule/restrict calls, and owned descriptor cleanup use it explicitly. Applied
ABI diagnostics consume the captured capability value rather than probing again
after restriction. `Rejected`/`ProbeFailed` capabilities preserve errno/cause and
cannot be collapsed into ABI-zero unsupported bypass. Standalone legacy helpers
still select their own context; application-owned unified capture is subsequent
runtime work.

Supervised installation and cached task registration receive the attempt's selected
native backend explicitly. Reusing a shared daemon authorizes the calling thread
through that backend; daemon startup resources and retirement retain the manager's
lifecycle owner. The explicit installer captures notification support before native
callbacks. This preserves authority across Landlock-to-USER_NOTIF installation and
does not imply unified application-owned capability/runtime or daemon-lifecycle
capture. Registration leases remain receipt-owned and close after task execution.

`ContainmentRegistryEffect.InstallationFailed` records terminal failure independently
of policy coverage and Seccomp engine-state rank. The interpreter retains the first
failure without rewinding applied stages. Registry merges carry thread/process
quarantine, and precomputed coordinator publications preserve an already-recorded
failure. Ordinary and dedicated-process installation entry reject quarantined scopes;
operator bypass or repaired probes cannot silently make a failed carrier reusable.

CET locking is a current-thread, verify-and-lock step before Landlock/Seccomp.
`ARCH_SHSTK_STATUS` uses Linux UAPI `0x5005` (not unlock `0x5004`) and only reports
enabled bits. Confirmation requires pre-enabled SHSTK, native LOCK success zero,
and enabled-status verification; late ENABLE is not used. `CetLocked` publishes
thread/backend-bound proof through the same effect interpreter. Quarantine and proof
survive later publication, and process merges never promote another thread's proof.
Requests for process-wide CET coverage fail before native installation. The
[CET guide](../../intel-cet-jvm-sandbox-integration.md) records exact runtime and
acceptance prerequisites.

### Per-thread application (THREAD_LANDLOCK_APPLIED_*)

`THREAD_LANDLOCK_APPLIED_READS` and `THREAD_LANDLOCK_APPLIED_WRITES` are `ThreadLocal<Set<String>?>`
that ensure the Landlock ruleset is applied only when the requested paths are not already covered
by an existing domain. Applying it a second time on the same thread creates a second
intersective Landlock domain that **narrows** permissions further. This can block JVM
classpath reads and cause `NoClassDefFoundError` at unpredictable points during lazy classloading.

### ABI version negotiation

Landlock `Landlock.kt` negotiates the highest ABI version the running kernel supports
via `landlock_create_ruleset(size=0, flags=LANDLOCK_CREATE_RULESET_VERSION)`. Using
a feature flag from a newer ABI than the kernel supports **silently drops those rules**
without returning an error. Always document the minimum ABI version required when
adding new Landlock access-right flags.

### JVM classpath whitelist (`allowJvmClasspath()`)

If Landlock is active and a restricted thread triggers lazy classloading of a class
whose `.jar` file is not in the allowed read paths, the kernel returns `EACCES` and
the JVM throws `NoClassDefFoundError`. `WorkerPolicyBuilder.allowJvmClasspath()` reads
`java.home` and `java.class.path` at policy-build time to prevent this.

Always call `allowJvmClasspath()` when writing tests that activate Landlock on a
thread that may still lazy-load test infrastructure classes.

### ALLOW_LIST syscall floors: the bootstrap-read closure (`pread64`)

For **seccomp** allow-list floors (distinct from the Landlock path rules above), lazy
boot-class reads are served by *positional* reads on the modules image. A floor containing
`READ`+`LSEEK` but missing `pread64` fails those reads mid-stream with `EPERM`, which the JVM
surfaces as `ClassFormatError: Incompatible magic value <garbage>` — deterministic corruption,
not a clean IOException (issue-20260823-190000; proven via AllowListTest 2/2 fail → 0/2 pass).

Use `io.mazewall.enforcer.engine.JvmFloorPresets.fullJvmFloor()` (or at minimum
`BOOTSTRAP_READ_CLOSURE`) instead of hand-rolled lists, and validate narrow floors once with
`-Dio.mazewall.selfVerify=true` in CI. See `security-considerations.md §9.1`.

### Landlock TSYNC (ABI 8 / Linux 7.0+)

Historically, Landlock rulesets were strictly thread-scoped and could not be applied retroactively to sibling threads. To lock down a multi-threaded JVM process on older kernels, a wrapper launcher (e.g., C or Rust) must apply the ruleset before invoking `execve` on the JVM.

Linux 7.0 (ABI 8) introduces `LANDLOCK_RESTRICT_SELF_TSYNC` which synchronizes the ruleset to all existing threads in the process atomically. However, `mazewall` keeps this flag **disabled by default** (always passing `flags=0L` during enforcement ruleset application) due to:
1. **Sibling Thread Transparency:** The JIT compiler and GC worker threads often require system path permissions that sandboxed application threads lack. Retroactive lockdown causes process deadlocks or aborts.
2. **Test Suite Collisions:** TSYNC sandboxes the parent Gradle worker process during execution, crashing completely unrelated sibling tests with `AccessDeniedException`.

### `applyRestrictiveBarrier()` vs. `applyRuleset(policy)`

- `applyRuleset(policy)` — enforcement path; used by `ContainedExecutors`.
- `applyRestrictiveBarrier()` — applies a restrictive Landlock domain. This is used by the **Iterative Profiler (Tier A)** to trigger path denials that are then caught and resolved. In older design iterations, it was used with `MAZEWALL_PROFILER_AUDIT=true` to trigger kernel audit events, but this is now deprecated for transparent profiling as Landlock lacks a permissive mode.

---

## 6. `PureJavaBpfEngine` Installation Sequence

One immutable native-engine view owns the plain installation sequence. BPF packing,
`no_new_privs`, raw seccomp, prctl compatibility fallback, EINTR retries, and mode
verification receive the same `NativeEngine` explicitly. The enclosing installation
attempt supplies its captured backend to both CET and this pipeline. Standalone
engine entry captures its own view once. No scoped ambient engine override or
mutable singleton provider setter is used. Native I/O still crosses trait methods;
the architecture rules recognize `snapshot()` as trait selection and independently
ban mutable trait lookups inside the plain engine/state pipeline.

For reference: the exact steps performed by `PureJavaBpfEngine.installInternal()`:

```
1. prctl(PR_SET_NO_NEW_PRIVS, 1)         → required before any unprivileged seccomp call
2. Arch.current()                         → resolve arch (x86_64 / aarch64)
3. BpfFilter.build(arch, policy)          → compile BPF program
4. Arena.ofConfined().use { arena ->
     LinuxNative.newSockFProg(arena, filters)   → pack sock_fprog struct (FFM)
     syscall(seccompNr, SECCOMP_SET_MODE_FILTER, flags, &prog)
   }
5a. If syscall returns 0 → success
5b. If thread-local and syscall fails:
       prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)  ← older kernel fallback
    If process-wide (TSYNC) and syscall fails → IllegalStateException (no fallback)
6. prctl(PR_GET_SECCOMP) == 2             → verify filter is active
   (skipped if Syscall.PRCTL is in the blocked set — prctl would be blocked)
```

---

## 7. FFM Struct Layouts

Supervisor and profiler daemon startup share the platform `ChildStartupTransaction` resource
mechanism, not a lifecycle reducer. Endpoint, process, output pump, and shutdown hook remain
provisional until readiness commits a `ChildProcessResources` owner. Failed authorization
(`PR_SET_PTRACER`, including `EPERM`/`EACCES`), launch, hook registration, readiness, or
interruption triggers rollback. Cleanup attempts all owned releases, bounds child reaping
and pump joining, and uses the same launcher that created the endpoint. A supervisor is not
published as running before readiness; an unexpected exit after publication retains the
supervisor's fail-closed halt policy. Intentional retirement first removes the running state
so pump EOF cannot be mistaken for an unexpected exit. Output observation does not take the
manager lock while cleanup joins the pump.
Failed retirement remains owned and retryable before another daemon can be spawned, including
provisional startup rollback. `stop()` retries that provisional owner even when no running context
was published. Fatal cleanup errors outrank ordinary startup failures, retaining earlier failures
as suppressed evidence; every independent release is attempted. Completed resource releases are
not repeated. See the [startup migration guide](../api/daemon-startup-migration.md).

Transient off-heap structs use `Arena.ofConfined()` for automatic deallocation.
The daemon's reusable notification and response buffers use process-lifetime shared
`SegmentPool.owned(...)` pools. Each session holds generation-specific `SegmentLease`
objects and closes those leases during cleanup; it never returns a raw native address
or closes a global pool. Partial session-buffer acquisition returns any acquired lease
and closes the session's confined ACK arena. See the
[SegmentPool migration guide](../api/segment-pool-migration.md) for pool ownership and
the limits of the checked segment accessor. Key layouts:

### `sock_filter` (8 bytes)
```
offset 0: code  (unsigned short / JAVA_SHORT, 2 bytes)
offset 2: jt    (unsigned byte  / JAVA_BYTE,  1 byte)
offset 3: jf    (unsigned byte  / JAVA_BYTE,  1 byte)
offset 4: k     (unsigned int   / JAVA_INT,   4 bytes)   ← MUST be JAVA_INT, not JAVA_LONG
```

### `sock_fprog` (16 bytes on x86_64)
```
offset 0: len     (short / JAVA_SHORT, 2 bytes)  — number of sock_filter instructions
offset 2: padding (6 bytes for alignment)
offset 8: filter  (address / ValueLayout.ADDRESS, 8 bytes)  — pointer to sock_filter array
```

Getting `k` wrong (using `JAVA_LONG` instead of `JAVA_INT`) produces a silently-corrupt
BPF program. The kernel BPF verifier may or may not catch this depending on the values
involved — it is not safe to rely on the verifier as a correctness check.

---

## 8. `errno` Capture Pattern

```kotlin
// Correct pattern (from LinuxNative.kt):
val capturedState = CAPTURED_STATE_LAYOUT.allocate(arena)
val handle = Linker.nativeLinker().downcallHandle(
    addr,
    descriptor,
    Linker.Option.captureCallState("errno")
)
val result = handle.invokeWithArguments(capturedState, ...)
val errno = capturedState.get(ValueLayout.JAVA_INT, errnoOffset)
// Read errno BEFORE any other FFM call — the captured state is not thread-safe
// across multiple calls.
```

Do not call `Native.getLastError()` — this method does not exist in the standard
JDK FFM API. Do not read errno after performing another FFM downcall, as the
captured state segment reflects only the most recent call.

---

## 9. Logging & Metrics inside a Sandboxed Thread

Applying strict sandboxing rules (like `Policy.PURE_COMPUTE_UNSAFE` or restricted Landlock paths) to individual worker threads introduces a critical operational challenge: **logging and metrics execution**.

### The Problem
If a sandboxed task thread attempts to execute synchronous logging or metrics updates (e.g., standard file writes, TCP/UDP socket transmissions, or JMX updates):
1. The kernel intercepts the filesystem write (`write`, `writev`) or network system call (`sendto`, `sendmsg`, `connect`).
2. The system call is aborted, returning `EPERM` or `EACCES`.
3. The sandboxing engine catches this and throws a `ContainmentViolationException`, crashing the task prematurely.

### The Solution: Asynchronous Logging & Metrics
To avoid crashing JVM tasks when logging under a sandboxed thread, you must ensure that all logging and telemetry output is decoupled from the task execution thread:

* **In-Memory Decoupling**: The sandboxed task thread must write its log events and metrics data directly to an in-memory queue (e.g., a lock-free Log4j2 `Disruptor` ring buffer, a `ConcurrentLinkedQueue`, or a lock-free telemetry buffer).
* **Out-of-Process / Helper Thread Offloading**: An unrestricted, uncontained background helper thread (which runs outside the thread-scoped sandbox) consumes the events from the queue and performs the actual systems-level I/O operations (writing to the disk or pushing metrics over network sockets).

---

## 10. Future Roadmap: Process-Wide Namespaces & cgroups (Tier 1 Expansion)

To reinforce process-wide (Tier 1) security boundaries without breaking JVM thread orchestration, the roadmap includes introducing optional native integrations for **Linux Namespaces** and **cgroups**:

1. **Process-Wide Namespaces (Mount / Network / PID):**
   - **Why:** Running the entire JVM within its own dedicated namespaces at startup establishes a physical boundary that mitigates ACE escapes to the host.
   - **Implementation:** Leveraged strictly at startup/initialization (Tier 1) via a custom native launcher or early process bootstrap (e.g., via `unshare` before JVM thread pool creation). Thread-local namespace isolation is explicitly avoided due to GC signal routing and thread-dumping breakages.
2. **cgroups v2 Resource Restrictions:**
   - **Why:** Mitigate Denial of Service (DoS) attacks (e.g., CPU exhaustion, memory ballooning) initiated by sandboxed tasks.
   - **Implementation:** Bind the JVM process or specific system cgroup slices to strict resource boundaries at initialization.

---

## 11. Experimental: Stacktrace-Enforced Scoping (L4 Call-Path Binding)

In addition to static syscall filtering, `mazewall` supports dynamic **Stacktrace-Enforced Scoping** (using `StacktraceScopingPolicy` and `JvmStackInspector`). This binds a system call's permission check to the calling JVM execution stack trace in real time.

### The Safepoint Deadlock Problem
* Thread creation (`Thread.start()`) and process spawning (`ProcessBuilder.start()`) both rely on `clone` (or `clone3`) syscalls under the hood.
* When a syscall is supervised under `USER_NOTIF`, the kernel suspends the tracee thread.
* The supervisor must obtain the JVM stacktrace of the suspended thread using `Thread.getStackTrace()`. This forces a JVM safepoint.
* However, because the tracee thread is suspended in `Thread.start()` holding internal classloader and thread monitors, it cannot reach the safepoint.
* This results in a permanent circular deadlock.

### The JVM-Independent Solution (BPF-Level Separation & State-Based Propagation)

Rather than relying on JVM-specific configuration arguments (like `-Djdk.lang.Process.launchMechanism=vfork`), `mazewall` implements a clean, system-level design to enforce stacktrace-based scoping for process spawning safely:

1. **BPF-Level Clone Flag Inspection (Preventing Deadlocks):**
   JVM thread creation (`Thread.start()`) utilizes the `clone`/`clone3` syscalls with the `CLONE_THREAD` flag (value `0x00010000`). Blocking or suspending this path results in JVM-internal deadlocks.
   The BPF program is configured to inspect `clone` flags and **unconditionally allow** calls where `CLONE_THREAD` is set. Non-thread-creating `clone` calls (which indicate process creation, such as those from `posix_spawn`), along with `fork` and `vfork`, are routed to `USER_NOTIF`.

2. **Parent Stack Trace Capture on Spawn Entry:**
   When the parent JVM thread ($T_{parent}$) calls `vfork`, `fork`, or a process-spawning `clone`:
   - The seccomp filter traps the entry.
   - The JVM validation listener captures the stack trace of $T_{parent}$ and runs the spawn policy callback. Only an approved spawn with a nonempty stack may register authorization before the syscall continues.
   - Each `SupervisorRuntimeContext` owns a `SpawnAuthorizationStore`. The key is the parent's process ID (TGID) plus `/proc/<pid>/stat` start-time ticks, so reuse of a PID does not reuse authorization. Unavailable TGID or process identity rejects the spawn; there is no TID-only fallback.

3. **State-Based Propagation on Child `execve`:**
   When the child process ($PID_{child}$) calls `execve`/`execveat` to run the target binary:
   - The seccomp filter intercepts `execve`.
   - The supervisor daemon reads `PPID` from `/proc/$PID_{child}/stat` to verify it is a descendant of the JVM.
   - For an exec with no live JVM stack, the JVM validation listener resolves the parent's current process identity and atomically consumes authorization from its own session. Missing, expired, consumed, closed, or unresolvable authorization denies exec, even when no policy callback is installed.
   - Authorization lasts at most the existing ten-second monotonic TTL and is valid at its exact boundary. Each registration permits one consume, including when the subsequent exec policy rejects it. The last task lease clears pending authority without closing the reusable store. Listener termination and installation-owner retirement close it permanently.
   - Stack containers and frames are copied at registration and when constructing or reading a successful result.

The store holds one outstanding authorization per parent process identity. It does not bind a registration to a particular future child, and concurrent spawns from the same process can replace one another. Multiple exec attempts (including helper-to-target exec or PATH retries) need separately validated authority; they cannot replay one spawn entry. This experimental stack propagation does not replace the process-wide Tier 1 baseline, child ancestry checks, or the secure-exec path's existing fail-closed rewrite requirements. Thread-scoped containment remains an incomplete ACE boundary.

### Supervisor session and reply ownership

Supervisor/profiler installation uses one 30-second monotonic handshake budget,
covering the install notification, listener readiness and caller proceed waits.
`mazewall.supervisor.installTimeoutMillis` may lower this budget to 1–30000 ms;
invalid values fail before installation. Interruptions do not renew the deadline
and the caller's interrupt status is restored. A reducer retains the last completed
stage (prepared, filter applied, descriptor transferred, ready) when setup fails;
a late successful kernel installation cannot erase a previous failure or its
irreversible application. The permanent supervisor lifetime rejects later task
registrations after listener failure, including failure observed during startup.
Applied-stage publication is complete only after its callback returns or throws.
Timeout cleanup joins any in-flight publication and observes the same callback
failure without invoking it twice. This callback must be short and nonblocking;
its completion is required even after the handshake budget expires.

Default installation IPC creates a private CLOEXEC socket with nonblocking connect,
then restores its stream flags for the existing listener. SCM_RIGHTS sends use
MSG_DONTWAIT and deadline-bounded writable polling, with at most eight send attempts.
Permission failures are terminal. Supervisor and profiler production installers use
this shared transport; injected connect/send callbacks are fault-injection seams
and cannot provide the production deadline guarantee when they block arbitrarily.
The local seccomp listener has one atomic owner: the failing caller may reclaim it
until the coordinator claims it for send, after which only the coordinator closes
it. A late connection is closed before callback transfer. Socket ownership passes
to the listener callback on entry, including startup failure. Invalid supervisor
acknowledgments publish failed listener health before releasing readiness, and
cleanup failure cannot suppress that health notification. Neither a setup timeout
nor listener closure removes an installed kernel filter. Notification admission
and helper isolation are separate processing stages described below and in the
[cross-module liveness contract](../core/notification-liveness-contract.md).

The shared `SeccompSessionHandler` accepts one notification at a time and owns its
30-second monotonic deadline. `SeccompNotifHandler.beginNotification` returns
reactor-confined `NotificationWork`; every poll yields `Await` or a terminal reply
effect. Idle and interrupted reactor polls also advance pending work. Supervisor
helper completions are observed by the reactor; their completion callbacks have
resource-disposal authority only. A single CAS lease admits either the completed
reply or the deadline's one EPERM send attempt. ENOENT, hard native failure, and a
throwing terminal effect consume that lease permanently and terminate the session.
Normal SEND/RECV interruption retries have an eight-attempt ceiling; timeout denial
does not retry. Native notification/reply layouts and private control frames are
unchanged.

Before preparation, a daemon-owned admission ledger permits at most 128 admitted
notifications total and eight per TID, shared across all listener sessions. Each
notification owns an idempotent counted lease, released on completion, failure,
timeout, or listener teardown. Admission rejection bypasses argument inspection
and JVM validation and makes one EAGAIN terminal send attempt, including under
EINTR. Helper-queue EAGAIN replies also use one attempt. These limits count work
received by the reactor; they do not limit notifications still queued in the
kernel or impose a traps-per-second rate limit.

`ControlSocketExchange` moves a frame through partial nonblocking writes and reads,
using the same notification deadline throughout. It changes status flags only on
the daemon-owned private control endpoint, never on an imported tracee socket.
EAGAIN yields; EINTR has a finite per-exchange budget. Secure-exec rewrite exchange
is advanced before the terminal reply is admitted. No timer thread, watchdog, or
blocking JVM worker is introduced by this protocol.

The production daemon now uses an authority-helper conversation for every
notification. Tracee argument reads, proc inspection, cwd capture, descriptor
import, path resolution, open execution and secure-exec target/NUL preparation
run in an independently terminable helper process. The reactor admits the job,
polls immutable bounded snapshots, advances the JVM control exchange and owns
all reply ioctls. It checks ID_VALID after preparation, before releasing a JVM
decision, and before accepting a result. A late or invalid result can only be
disposed. The older synchronous coordinator and direct executors remain fixture
seams; the daemon factory explicitly selects authority-helper work.

The helper pins `/proc/<tid>` and the corresponding pidfd, rechecks the pinned
task after pidfd acquisition, and uses proc-relative handles thereafter. Memory
reads use read-only positional access through an opened `mem` capability, so
task exit cannot redirect a read to a recycled numeric PID. Memory ranges
are checked against readable mapping coverage before and after each memory read;
pathnames are read in page-sized chunks and stop at the first NUL. Non-readable
or unmapped arguments fail with EFAULT. `/proc/mem` reads and the mapping checks
are not atomic against concurrent tracee mmap/mprotect operations; this remains
a bounded argument snapshot, not a guarantee of atomic memory capture. Directory capabilities
are retained through authorization and execution. Relative opens use the tracee's
captured cwd or imported dirfd, including after rename. Absolute opens use its root
through a distinct typed capability key, never a numeric tracee-dirfd sentinel.
Relative operations reject negative dirfds other than AT_FDCWD with EBADF before
capability lookup. Absolute opens ignore the supplied dirfd as the kernel does,
with openat2 RESOLVE_IN_ROOT; the existing absolute-path RESOLVE_BENEATH rejection
and open_how values remain enforced. Canonical bypass roots are captured from the
daemon before notification processing; the helper's classpath cannot widen them.
The existing filesystem symlink/rename races and thread-containment limitations
still apply; this is not a claim of race-free policy path resolution.

Accept with a non-null peer-address output (including a null length pointer) has an explicit limitation:
Linux process_vm_writev accepts only a numeric PID, whereas `/proc/task/mem`
writes can force access through read-only mappings and change accept's EFAULT
semantics. The helper returns typed
`UnsupportedTraceeAuthority.AcceptPeerMemory`; the sole reply interpreter logs
the limitation and denies with EPERM before accepting or importing a socket.
Address-less accept is supported. No numeric-PID write or forced-write fallback
is performed. Restoring this route requires a separately verified memory-write
capability ([backlog](../../backlog/issue-20260910-171157-430-restore-accept-peer-address-writes-through-identit.md)).

The notification deadline no longer waits for these tracee filesystem/memory
operations on the reactor. As with any JVM service, scheduling and native-kernel
progress are external assumptions: SIGKILL cannot immediately reap a process
stuck in uninterruptible kernel sleep. Failed bounded reaping keeps the slot
unavailable, reports failure, and never permits replacement or a second reply.
This does not claim universally bounded kernel resource reclamation or complete
accept peer-memory functionality.

`SupervisorSessionHandler` coordinates `SupervisorSessionMachine` transitions, bounded argument inspection, the JVM verdict exchange, route effects, and session teardown. `JvmVerdictExchange` owns the existing fixed-size control frame and returns a sealed verdict, timeout, malformed-frame, or transport-failure outcome. It rejects mismatched notification IDs and invalid path lengths/UTF-8, and closes the control connection on an unusable response.

An unusable exchange remains terminal even if closing its connection fails. Its typed outcome retains the cleanup exception alongside the primary failure; the session records both and refuses subsequent notifications. Preparation-only recovery cannot reinterpret an execution or exchange failure as successfully handled work.

Open, socket, and secure-exec route executors return `SupervisorReplyEffect` values. They cannot send a kernel reply. The single `SupervisorReplyInterpreter` owns CONTINUE, ERROR, value replies, atomic ADDFD-with-SEND, and secure-exec terminal operations. A notification reaches `Replied` only after confirmed native success. A failed native reply or post-reply cleanup terminates the session; it never triggers a second reply.

`SupervisorSessionHandler` reaches those route executors only through one
`SupervisorRouteDispatcher`. The dispatcher owns route preparation, authority and socket
helpers, executor lifecycle, and terminal-effect mapping. Route helpers are implementation
details of `io.mazewall.enforcer.internal.supervisor`; other packages may depend only on the
supervisor protocol/facade types and cannot execute a route directly.

Enforcement compilation rejects a default `ACT_NOTIFY` action and explicitly supervised syscall numbers without a known supervisor route. Profiling is exempt because it observes rather than authorizes operations. Unknown supervisor kinds deny before JVM validation and cannot gain authority from an allow or FD-injection verdict. An FD-injection verdict is accepted only for open, connect, and accept routes.

Open fast-path authorization requires a resolved path and a copied pathname. Unresolved names deny with EPERM regardless of `.class`, `.jar`, or `META-INF/` spelling. Resolved JVM bypass paths use the open executor and atomic FD injection, never CONTINUE; other resolved paths require JVM validation. This removes tracee-memory pointer resumption without claiming race-free filesystem resolution: the existing open executor and filesystem preparation limitations above still apply. Ordinary CONTINUE is confined to the audited spawn category after JVM authorization; fork/vfork and clone authorization depend on parent identity and register flags, not clone's stack/TID pointer contents. Secure exec retains its separate, experimental rewrite protocol and documented limitations.

Supervised `connect` imports the original socket with `pidfd_getfd` inside the authority helper and connects that same open-file description. It returns the terminal value zero, not a replacement FD. Socket type, binding, options, and status flags therefore remain attached to the original tracee descriptor. Supported address-less `accept`/`accept4` create a new descriptor and retain atomic ADDFD-with-SEND, including requested CLOEXEC behavior. Native `EINPROGRESS`, `EAGAIN`, permission errors, and timeout errno are returned unchanged; neither route changes shared file status flags or retries by creating another socket.

The supervisor JVM owns one bounded `SocketHelperPool`, with four reusable independently terminable helper JVM slots and at most 64 queued operations. Internal settings `mazewall.supervisor.socketHelpers` (1–32) and `mazewall.supervisor.socketQueue` (1–4096) are validated before executor creation. Production prewarms all slots with ordinary counted jobs before announcing daemon readiness, under one five-second startup budget; failure prevents readiness and runs normal pool cleanup. Fixtures can retain lazy startup. Slots execute one operation at a time, retain clean children, and replenish failed/cancelled children only after reaping. Queue saturation releases request ownership and returns `EAGAIN`. Private mode-0700 UNIX endpoints carry owned CLOEXEC SCM_RIGHTS descriptors. Standalone socket-operation frames carry copied socket addresses (at most 128 bytes), accept flags, errno, and peer-address snapshots; no confined arena crosses the process boundary. Authority frames additionally carry the immutable notification registers so only the child performs tracee-memory reads.

The retained standalone-operation protocol also carries capability-relative opens. A request
contains a copied relative UTF-8 pathname (0–4095 bytes, rejecting NUL, malformed
UTF-8 and absolute names), the three 64-bit open_how fields, and an owned
SCM_RIGHTS directory duplicate. The helper executes openat2 against that
capability and transfers an owned result descriptor; the reactor alone can
inject it. Existing open flags/mode and openat2 RESOLVE_BENEATH composition
remain unchanged. Empty pathnames reach the kernel for normal errno validation.
Import permission errors deny without fallback. Cancellation,
late-result disposal and capacity accounting use the notification's existing
helper session, shared with its socket routes. Direct executors and this earlier
operation-stage seam remain available to fixtures; production preparation and
execution instead use the complete authority conversation below.

The authority protocol is a separate two-phase operation in the same bounded
pool: copied notification identity/registers and captured bypass roots produce a
prepared snapshot; only the reactor then releases its fast-path or JVM-authorized
decision. Proc/pidfd/memory/directory handles remain owned by the child for that
conversation. The authenticated parent supplies the notification identity; an
ID_VALID check binds the prepared authority to the outstanding notification before
execution. Kernel Yama allows descendants of the registered PR_SET_PTRACER daemon;
the broker does not change ptrace policy. EPERM/EACCES remain terminal. Both ends
verify SO_PEERCRED against the expected direct parent/child before exchanging
authority. Private pipe frames echo the notification ID, cap pathnames at 4095
UTF-8 bytes, socket addresses at 128 bytes, and bypass roots at 1024 entries.
Status reads are capped at 64 KiB and maps at 4 MiB. Oversized/malformed input
fails closed. Only successful result descriptors cross SCM_RIGHTS, with the
sender closing its owner after transfer and the receiver taking one CLOEXEC owner.
The child never receives a seccomp listener or terminal-reply capability.

Each reactor notification owns a helper request session under its listener owner.
Notification timeout or listener teardown removes queued work, retires active
helpers, and disposes late accepted descriptors. Cancellation returns without
waiting for helper startup or process reaping; bounded cleanup workers terminate
children independently of the reactor. It does not interrupt JVM operation
threads. Helper slots remain unavailable until the old child has been reaped,
including cancellation during factory startup. Explicit session/pool joins use
one aggregate ten-second cleanup deadline rather than a fresh wait for each job;
failed helper release remains retryable and cleanup failure is reported.
The reactor retains all reply authority and a closed notification can only dispose
a late result. The compatibility fixture entrypoint retains its reply gate.
The existing kernel response format is unchanged. Child startup and reap each
have a five-second bound. Cleanup tracks successful reap, channel closure and
endpoint deletion independently, without repeating completed releases.
Cancellation does not undo a connect already completed by the kernel or change
the original open-file description's flags.

Before opening IPC, a helper verifies its expected direct parent and registers an independent JDK ProcessHandle exit monitor. Owner death (including SIGKILL) halts the helper on the monitor thread, releasing imported descriptors even while the operation thread is blocked in native accept/connect. A parent that died before registration fails the direct-parent check; death during registration completes the exit notification. This private helper mechanism adds no FFM layouts or downcalls.

Descriptor ownership also covers setup before a reply can be interpreted. The imported listening socket's cleanup scope encloses accept-buffer allocation and initialization. A completed route effect is held by a single-use `SupervisorReplyHandoff` while the handler creates its reply arena and response buffer. If setup fails, the handoff disposes any unconsumed AddFd or opened secure-exec descriptor before terminal failure accounting. Transfer goes directly to the sole reply interpreter and retires the handoff; neither a failed disposal nor a post-transfer failure can close the same descriptor again. The original setup failure stays primary, with cleanup failures retained as suppressed evidence.

A fatal supervisor worker failure completes its future exceptionally after resource cleanup, allowing the session owner to process `ReplyFailed` and shut down without a policy-denial reply. The worker then rethrows the original fatal failure; it is not converted to ordinary denial or successful handling. A child crash or malformed private reply produces `EIO` and retires that helper; the child has no kernel-reply authority. Fatal prerequisite-close failures also release any already acquired successor descriptor before propagation.

Reply interpretation and its post-reply cleanup are also terminal failure boundaries. A failure after confirmed ADDFD-with-SEND cannot enter pre-reply denial recovery. A fatal failure on the calling thread is rethrown with its original identity outside the future callback. For a pending async completion, the callback first terminates the session and finishes cleanup, then explicitly delivers the original fatal failure to the replying worker's uncaught-exception handler before that worker exits. This explicit delivery is necessary because `CompletableFuture` captures callback throws; no exceptional dependent completion is left unobserved. Shutdown or uncaught-handler failures remain attached to the primary failure as suppressed evidence.

The process boundary is also the authorization boundary: `SupervisorInstaller`'s JVM validation listener registers and consumes its session's spawn authorization. The sidecar routes carry the resulting JVM decision alongside their request context; neither argument preparation nor a route executor mirrors, consumes again, or independently grants that authority. Duplicating the store across processes would risk replay or incorrectly deny a legitimately consumed authorization.

The native `O_PATH` flag is verified against the host C headers as `0x00200000`; secure-exec, Landlock and profiler classification share this platform value ([resolved issue](../../backlog/resolved/issue-20260909-074606-812-correct-o-path-native-flag-value-against-linux-hea.md)). One secure-exec limitation remains explicitly tracked: `pidfd_getfd` plus local close does not remove an injected descriptor from the tracee's FD table ([backlog](../../backlog/issue-20260909-074418-909-correct-secure-exec-cleanup-of-injected-tracee-des.md)). Failed rewrite remains denied, but the injected remote descriptor can remain until tracee teardown. No remote-close protocol is introduced.

---



## Reversible JVM Tracking vs Irreversible Kernel/Daemon Effects

The following table clarifies which effects in mazewall are reversible and which are permanent:

| Effect | Reversible? | JVM tracking |
|---|---|---|
| Seccomp filter | No (thread/process lifetime) | `ContainerState.filterDepth` / actions — never clear after apply |
| Landlock domain | No (nest inward only) | `landlockPolicy` — never rewind after apply |
| Supervisor task session | Reference-counted registration lease | Closing a lease preserves outer leases and the installed runtime |
| Supervisor installation / USER_NOTIF listener | Platform-thread lifetime | An explicit owner joins the installing thread, then requests reactor-owned socket/arena cleanup |
| `InstallationReceipt` | Diagnostic only | `installed` is independent of `landlockApplied` |
| `sanitizeThreadState` | Forbidden | Throws |

Process-wide Seccomp planning reads only `processState`; a restriction on the calling
thread cannot satisfy a policy requested for every peer thread. Thread planning uses
the combined state. Each successful native stage publishes immediately: Landlock
at successful `restrict_self`, before outcome allocation, ruleset/arena cleanup, or
diagnostic emission; Seccomp before verification or supervisor readiness can
fail. The supervised coordinator uses a precomputed publication closure targeting
the installing thread's holder; it never publishes to its own ThreadLocal.
No wrapper or failure handler restores a previous policy snapshot.

`PartialInstallationException.progress` records this attempt's successful irreversible
stages, not a difference against shared process state that another installation may
change. Fatal errors retain their identity and carry
the same typed progress as suppressed diagnostic evidence. Partial failure does not
authorize execution; normal fail-closed propagation remains the default.

Repeated and nested supervised tasks acquire idempotent registration leases in the
same installation runtime. They do not create a compatibility runtime or retire the
validation listener. A different scoping-policy object is rejected on an already
supervised platform thread: use a new thread for a different authorization contract.
If native filter installation succeeds but connection or descriptor transfer fails,
the installation retains irreversible metadata and a failed owner record. Its runtime
is closed; every subsequent contained task on that thread is rejected before policy
planning, even when the next policy does not request supervision. Operator fallback
cannot convert a permanently failed listener into a usable installation.
Raw executor ownership stays with the caller; a dedicated daemon lifetime observer
joins each installing platform thread, without finalizer/cleaner inference. Retirement
requests stop, and the listener completes socket and arena cleanup in its own reactor.

Migration: callers that manually invoked `SupervisorInstaller.registerThread` or
`unregisterThread` must scope the `InstallationReceipt` itself with `use`; closing
the receipt releases only its private registration lease. It never closes an
installation listener or reverses containment. Wrapped executors manage receipts.
The old hidden `Unit`-returning `install*` overloads no longer exist: every installation call
returns that receipt, so Kotlin callers use `installOnCurrentThread(...).use { receipt -> ... }`
where a supervisor may be involved and Java callers use try-with-resources.

**Related issues:**
- WONTFIX: [issue-102-permanent-thread-pool-contamination-classloader-leaks-and-st](../../backlog/resolved/issue-102-permanent-thread-pool-contamination-classloader-leaks-and-st.md)
- WONTFIX: [issue-103-containedexecutors-thread-local-state-persistence-and-poison](../../backlog/resolved/issue-103-containedexecutors-thread-local-state-persistence-and-poison.md)
- [issue-20260821-113003-report-already-active-landlock](../../backlog/resolved/issue-20260821-113003-report-already-active-landlock.md)
