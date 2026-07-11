# JVM Invariant Syscall Floor: Research & Open Problems

**Status: DEFERRED** — Problem scoped and documented. Implementation blocked pending
a verifiable solution to GraalVM coverage and source analysis completeness.

**Related backlog entry**: [issue-075-jvm-invariant-syscall-floor-is-incomplete.md](../backlog/issue-075-jvm-invariant-syscall-floor-is-incomplete.md) (Medium: JVM Invariant Floor)

---

## Problem Statement

`BpfFilter.getJvmCriticalNrs()` contains a hardcoded floor of 7 syscalls:

```kotlin
setOf(FUTEX, SCHED_YIELD, RT_SIGRETURN, RT_SIGACTION, MADVISE, GETTID, CLOSE)
```

This floor was established empirically on one JVM configuration (Temurin G1GC on x86-64).
Different GC algorithms, JDK versions, and JVM vendors make additional syscalls from
worker threads that are not captured by this list. If those syscalls are blocked by a
policy, the JVM crashes silently or deadlocks.

Known missing entries (best-effort, not verified as complete):

| Syscall              | JVM Context                                                          | Status     |
|----------------------|----------------------------------------------------------------------|------------|
| `rt_sigprocmask`     | POSIX signal mask management during GC handshakes (JDK 22+)         | Confirmed  |
| `mmap`               | JIT code cache, ZGC virtual space, GC heap expansion                | Confirmed  |
| `mprotect`           | JIT code emission, Shenandoah barrier patching                       | Confirmed  |
| `userfaultfd`        | ZGC multi-mapping colored pointer implementation                     | Confirmed  |
| `ioctl(UFFDIO_*)`    | ZGC page fault registration via userfaultfd                          | Confirmed  |
| `memfd_create`       | GraalVM JVMCI code cache (anonymous executable memory)               | Probable   |
| `sched_getaffinity`  | Loom carrier thread NUMA scheduling (JDK 21+)                        | Probable   |
| `tgkill`             | Thread-local handshake signals replacing futex in JDK 22+            | Probable   |
| `epoll_wait`         | Loom's ForkJoinPool I/O poller                                       | Probable   |
| `eventfd2`           | Loom carrier unpark signal                                           | Probable   |
| `pipe2`              | JVM signal handler pipe (os_posix.cpp)                               | Probable   |

---

## Option A: Strace / USER_NOTIF Profiling

### How It Would Work
Run the profiler against a representative workload. Collect syscalls made by JVM threads.
Commit the result as a classpath resource file loaded at runtime.

### Why This Is Fundamentally Incomplete

A BoB derived from strace or `USER_NOTIF` profiling captures only syscalls made by code
paths *exercised during the profiling run*. The JVM has several syscall sources that are
**non-deterministic and pressure-triggered**:

- **GC phases**: ZGC concurrent relocation (`userfaultfd`, `ioctl(UFFDIO_COPY)`) only
  fires when allocation pressure triggers a GC cycle. A profiling workload that stays
  below the GC threshold will miss these entirely.
- **JIT compiler background threads**: C2 compilation of a method requires crossing a
  minimum invocation threshold. Short-running profiling workloads may never trigger C2.
- **Loom carrier rescheduling**: Only triggers when a virtual thread yields at a blocking
  point (`LockSupport.park`, I/O). Missing from any compute-only profiling workload.
- **Signal-based safepoints** (JDK 22+): Triggered at specific GC pause intervals, not
  by application code.
- **JVM heap expansion**: `mmap` for new GC region allocation only fires under memory
  pressure, not in a pre-warmed JVM with pre-allocated heap.

**Verdict**: Probabilistic coverage → probabilistic security. This is unacceptable for
a security library whose entire value proposition is preventing unexpected syscalls.

### Practical Problems

- Running the profiler for each supported JVM vendor × version × GC combination
  requires significant CI resources (CPU, time, money).
- The profiler uses `SECCOMP_RET_USER_NOTIF` + out-of-process daemon, which adds latency
  and is not designed for "enumerate all possible syscalls" workloads.
- Even with a comprehensive stress harness, coverage cannot be verified — there is no
  tool that tells you "you have exercised all kernel-interface code paths in the JVM."

---

## Option B: OpenJDK Source Code Analysis

### How It Would Work
Enumerate every call site that invokes a Linux syscall within the HotSpot JVM source.
Produce a complete list. Version-tag it against JDK major releases. Commit it as a
Kotlin constant in `JvmInvariantFloor.kt`.

### Why This Is Hard to Verify as Complete

**The user's objection is correct.** Source analysis is theoretically complete, but the
OpenJDK source is ~2 million lines. Verifying 100% coverage requires:

1. Tracing all `::syscall()`, `os::Linux::*`, `os_posix::*` call paths.
2. Understanding conditional compilation: GC selection (`-XX:+UseZGC`, `-XX:+UseShenandoahGC`)
   results in different code being compiled in. A G1-only analysis misses ZGC paths.
3. Following indirect dispatch through the OS abstraction layer (`os::*` functions that
   delegate to platform-specific implementations).
4. Accounting for native library calls (libc, libpthread) that may make additional syscalls.

Even with careful analysis, a missed `#ifdef LINUX_ZGC` branch could result in an
uncovered path.

### The GraalVM Problem

GraalVM CE is NOT a drop-in recompilation of OpenJDK. The JVMCI interface allows
GraalVM's compiler to replace HotSpot's C2. More critically:

- **GraalVM Native Image (Substrate VM)** is a completely different runtime that does NOT
  use HotSpot's OS abstraction layer at all. It uses `com.oracle.svm.core.posix.*` which
  has its own syscall call sites.
- **GraalVM JVM mode** (running on HotSpot with GraalVM compiler) is closer to OpenJDK
  but adds `memfd_create` for JVMCI code cache and may use different signal handling.
- GraalVM source is partially closed (EE) or uses a different repository structure (CE),
  making comprehensive analysis harder.

**Verdict**: For HotSpot-based JVMs (OpenJDK, Temurin, Corretto, Amazon), source analysis
is feasible but labor-intensive. For GraalVM and OpenJ9, it requires separate analysis
of different codebases. This is a one-time cost per major JDK release but requires
domain expertise in JVM internals.

---

## Option C: Docker/OCI Seccomp Profile as Reference

### How It Would Work
The Docker default seccomp profile (maintained by the container community) already
enumerates a "safe for any Linux process" allowlist. It's available at:
https://github.com/moby/moby/blob/master/profiles/seccomp/default.json

The JVM-relevant superset of Docker's profile could be used as a conservative starting
point for the floor.

### Problems

- Docker's profile is a process-level allowlist (all 300+ allowed syscalls), not a
  thread-level JVM-internal subset.
- It is designed to be maximally permissive (anything safe for containerized apps),
  not minimally permissive (only what the JVM itself needs internally).
- Using it as the floor would allow far too many syscalls on sandboxed threads,
  reducing the security value of mazewall significantly.

**Verdict**: Useful as a cross-reference during source analysis to verify we have not
missed anything obvious. Not suitable as the floor itself.

---

## Option D: `SECCOMP_RET_LOG` Monitoring

### How It Would Work
Install a `LOG`-mode seccomp filter before containment, run the JVM, read kernel audit
log, build floor from it.

### Problems

- `SECCOMP_RET_LOG` requires `CAP_SYSLOG` or `/proc/sys/kernel/perf_event_paranoid ≤ 1`.
- Incompatible with rootless container deployments (the primary target environment).
- Even if available, has the same completeness problem as Option A (only captures
  exercised paths, not all possible paths).

**Verdict**: Not viable for rootless environments. Same fundamental incompleteness as profiling.

---

## Option E: Exhaustive JVM Stress Harness + Source Analysis Combined

### How It Would Work
1. **Source analysis** produces a candidate floor (OpenJDK source enumeration).
2. **Stress harness** validates it empirically by running under each GC mode with
   allocation pressure, JIT pressure, and Loom load to trigger rare code paths.
3. Any syscall observed in the stress run that is NOT in the candidate floor is a bug
   in the source analysis — fixing it improves the analysis.

### Why This Is The Most Defensible Approach

- Source analysis provides **theoretical completeness** (the claim: "we read the source").
- Stress harness provides **empirical validation** (the check: "we triggered these paths").
- Discrepancies between the two are actionable: either the source analysis missed something
  (fix the floor), or the stress harness triggered an unexpected path (investigate why).
- Together they provide the strongest possible evidence of correctness without
  requiring kernel-level tracing tools or privileged capabilities.

### Implementation: `JvmFloorWorkload`

The project now includes `io.mazewall.enforcer.JvmFloorWorkload`, a synthetic stress test designed to
trigger the JVM subsystems that historically cause seccomp/Landlock issues:

1. **JIT Compiler**: Compute-intensive hash loops to trigger C2 compilation and executable memory mappings.
2. **GC Handshakes**: Large object allocations and manual `System.gc()` to force cross-thread coordination.
3. **Loom/Concurrency**: Virtual thread yielding and carrier thread NUMA scheduling.
4. **NIO/Networking**: Native selector initialization and socket stack loading.
5. **OS Thread Coordination**: Thread creation, sleeps, and joining.

You can run this workload via Gradle to profile it on any new platform:
```bash
./gradlew :enforcer:runJvmFloor
```

### Open Implementation Questions

1. **Who writes the stress harness?** This requires JVM internals expertise to trigger
   ZGC concurrent phases, JIT C2 compilation, and Loom carrier scheduling reliably.
2. **How is the harness maintained?** New JDK releases may add new paths. The harness
   must be updated alongside the floor.
3. **What is the test signal when the harness finds a new syscall?** Should it be a
   CI failure (strict) or a logged warning (lenient)?

---

## Recommended Path Forward

1. **Short term (now)**: Keep the existing hardcoded floor but explicitly add `RT_SIGPROCMASK`
   and `MMAP`/`MPROTECT` (non-EXEC variants), which are confirmed missing and low-risk to add.
   These are provably needed from existing `design-specs/containment-design.md §3e` analysis.

2. **Medium term**: Implement Option E as a project-internal tooling task:
   - Write a `jvm-stress` Gradle task that runs a multi-threaded allocation/JIT/Loom
     pressure harness under strace inside the test container.
   - Cross-reference strace output against the floor.
   - Use this as validation, not as the floor source itself.

3. **Long term**: Formal source analysis of OpenJDK `os/linux/` directory per major
   JDK release (22, 23, 24, 25). Document each entry with its source file and line
   number. Submit as a versioned companion to each mazewall release.

---

## Open Questions for Future Resolution

1. Should GraalVM Native Image be in scope? It is a fundamentally different runtime
   and may require a completely separate floor.

2. For the `ioctl` entry: ZGC needs `ioctl(UFFDIO_*)`. Should the floor include all
   `ioctl` (broad attack surface), or should `BpfFilter` add argument inspection for
   `ioctl` keyed on the UFFDIO request codes?

3. Should the floor version-tag be enforced (throw/warn if JVM version exceeds the
   validated version) or informational only?

4. Can the JVM stress harness be run as part of CI without exceeding reasonable resource
   budgets? (Likely needs its own scheduled job, not part of every PR build.)
