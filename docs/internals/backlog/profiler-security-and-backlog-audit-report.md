---
title: "Profiler Module Security & Backlog Audit Report"
severity: "ENHANCEMENT"
status: "resolved"
priority: 10
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
  - ":enforcer"
target_files: []
effort: "medium"
---

# 🛡️ Profiler Module Security & Backlog Audit Report

**Date:** July 27, 2026
**Auditor:** Jules (Security Auditor & Systems Engineer)
**Target Modules:** `:profiler`, `:enforcer`

---

## 1. Executive Summary

This report presents a thorough logical and structural security audit of the `mazewall` profiler module and its interactions with the FFM API, seccomp user notification mechanics, and the enforcer containment boundary.

Additionally, we have performed a comprehensive, item-by-item verification of the active (unresolved) backlog issues. We analyzed each issue's hypothesis against the actual implementation, identifying which items are still valid security/architectural risks and which have already been resolved or are invalid/duplicate.

---

## 2. In-Depth Backlog Item Verification & Review

We reviewed 10 critical active backlog files pertaining to the profiler module. Below is our definitive analysis and status for each item:

### 1. Refactor Profiler Daemon to use Coroutines (`issue-019`)
* **Category:** Code Health / Architecture
* **File:** `docs/internals/backlog/code_health/issue-019-refactor-profiler-daemon-to-use-coroutines-structured-concur.md`
* **Status:** **STILL RELEVANT**
* **Verification & Evidence:**
  * In `ProfilerDaemonEngine.kt`, client connection handling is managed via raw blocking threads:
    `Thread { handleConnection(clientFd) }.apply { name = "conn-handler-${clientFd.value}"; start() }`
  * Network I/O and reactor loops use blocking calls (such as `poll()` with timeouts) rather than non-blocking equivalents.
  * Transitioning to a coroutine-based architecture using `supervisorScope` and structured concurrency will significantly improve daemon scalability, thread safety, and cancellation cleanups during shutdown.

### 2. `IterativeProfiler` Context Loss via thread creation (`issue-059`)
* **Category:** Performance / Diagnostics
* **File:** `docs/internals/backlog/performance/issue-059-iterativeprofiler-context-loss-via-thread-creation.md`
* **Status:** **STILL RELEVANT**
* **Verification & Evidence:**
  * In `IterativeProfiler.kt` (lines 68-80), the task execution runs inside a raw spawned `Thread`:
    `val thread = Thread { ... task.run() }`
  * Standard `Thread` creation completely strips MDC logging contexts and standard non-inheritable `ThreadLocal` variables initialized in the caller thread. Consequently, profiled workloads relying on thread-local contexts (such as security headers, transactions, or MDC trace IDs) will crash or experience context loss.
  * **Solution Recommendation:** Propagate thread context explicitly or utilize a configurable context-preserving task runner.

### 3. `IterativeProfiler` Logic Errors (Confirmed) (`issue-112`)
* **Category:** Performance / Diagnostics
* **File:** `docs/internals/backlog/performance/issue-112-iterativeprofiler-logic-errors-confirmed.md`
* **Status:** **NOT RELEVANT / DUPLICATE**
* **Reasoning & Evidence:**
  * This issue is a parent checklist that is fully redundant because its sub-components are individually addressed and resolved by other dedicated files:
    1. *Relative Paths* is resolved under `issue-055` (resolved).
    2. *Path Truncation* is resolved under `issue-060` (resolved).
    3. *Infinite Loop* is resolved by implementing component-based `Path.startsWith` rather than naive string comparison (fully validated in `IterativeProfilerTest`).
    4. *Context Loss* is duplicate of `issue-059` (open).
  * Consequently, `issue-112` should be marked as **resolved** or **duplicate** to avoid confusion.

### 4. Reactor Loop Iteration Arena Scoping (`issue-195`)
* **Category:** Performance / Memory Management
* **File:** `docs/internals/backlog/performance/issue-195-reactor-loop-iteration-arena-scoping.md`
* **Status:** **ALREADY RESOLVED (NOT RELEVANT)**
* **Verification & Evidence:**
  * The current reactor loops in both `SupervisorDaemonEngine.kt` (lines 352-363) and `ProfilerDaemonEngine.kt` (lines 284-295) have already been successfully refactored to wrap iteration processing in a short-lived `NativeArena.ofConfined().use { iterationArena -> ... }` block inside their `while (!isGlobalShutdown())` loop.
  * This segregates session-level allocations (like `pollFds` or pre-allocated pools) from transient iteration-level allocations (like reading paths from tracee memory), eliminating linear memory leaks cleanly.

### 5. `IterativeProfiler` infinite retry loop and failure on disjoint prefix file paths (`issue-056`)
* **Category:** Testing / Diagnostics
* **File:** `docs/internals/backlog/testing/issue-056-iterativeprofiler-infinite-retry-loop-and-failure-on-disjoin.md`
* **Status:** **ALREADY RESOLVED (NOT RELEVANT)**
* **Verification & Evidence:**
  * In `IterativeProfiler.kt` (lines 89-92), `isCurrentlyReadAllowed` checks:
    `p.startsWith(allowedPath)` where `p` and `allowedPath` are both strongly typed `java.nio.file.Path` instances.
  * The `java.nio.file.Path.startsWith` function compares paths **component-by-component** rather than checking raw string prefixes. Therefore, a path like `/tmp/prefix-other` does *not* match allowed path `/tmp/prefix`, preventing false positive matches.
  * This correct behavior is programmatically validated by the test `test iterative profiling path matching avoids naive prefix collision` in `IterativeProfilerTest.kt`.

### 6. Profiler Trace Listener Unbounded Channel DoS / OOM (`issue-20260726-224607`)
* **Category:** Security / Reliability
* **File:** `docs/internals/backlog/security/issue-20260726-224607-profiler-trace-listener-unbounded-channel-dos-oom.md`
* **Status:** **STILL RELEVANT**
* **Verification & Evidence:**
  * In `ProfilerTraceListener.kt` (line 44):
    `val eventChannel = Channel<TraceEvent>(Channel.UNLIMITED)`
  * Under heavy profiling of highly concurrent, high-throughput applications, trace events can be queued faster than the single collector thread can drain and append them. An unbounded channel allows heap allocations to grow linearly, resulting in severe JVM OutOfMemoryError (OOM) crashes and bypassing backpressure.

### 7. TraceEvent receives FFM values risking Use-After-Free due to Arena closure (`issue-20260726_011928_10`)
* **Category:** Security / FFM Safety
* **File:** `docs/internals/backlog/security/issue-20260726_011928_10_profiler-trace-listener-memory-segment-escape.md`
* **Status:** **ALREADY RESOLVED (NOT RELEVANT)**
* **Verification & Evidence:**
  * `TraceEvent` contains only primitive JVM types (`tidValue`: Int, `syscallName`: String, `args`: LongArray, `paths`: List<String>). There are no references to `MemorySegment` or foreign memory addresses.
  * String path extraction via `SupervisorProcessMemoryReader.readString` copies raw bytes from the native segment into a JVM heap-allocated `String` before returning.
  * This safety boundary is programmatically verified by an ArchUnit rule `ffmApiMustBeIsolatedToFfiPackage` in `ArchitectureTest.kt` ensuring zero dependencies on `java.lang.foreign.*` outside the FFI boundaries.

### 8. `IterativeProfiler` can exhaust thread pools on recursive containerization limits (`issue-20260726_011928_11`)
* **Category:** Security / Resource Containment
* **File:** `docs/internals/backlog/security/issue-20260726_011928_11_thread-pool-exhaustion-iterative-profiler.md`
* **Status:** **NOT RELEVANT / INVALID**
* **Verification & Evidence:**
  * `IterativeProfiler.profile` runs sequentially. Its `executeTask` helper instantiates and joins a single raw thread:
    `thread.start(); thread.join()`
  * There is a hard retry ceiling of `maxRetries = 20`. Tasks are executed strictly one after another; there is no concurrency or recursion in the profiler's loop. Therefore, the profiler cannot multiply threads exponentially or cause thread pool exhaustion.

### 9. TraceEvent path truncation on excessively long extracted string paths (`issue-20260726_011928_13`)
* **Category:** Security / Sandbox Bypass
* **File:** `docs/internals/backlog/security/issue-20260726_011928_13_profiler-trace-event-buffer-truncation.md`
* **Status:** **STILL RELEVANT**
* **Verification & Evidence:**
  * In `SupervisorProcessMemoryReader.kt` (lines 20-27), `readString` scans the retrieved buffer up to `maxLen` for a null terminator. If no null terminator is reached (e.g. on paths exceeding `maxLen`), it silently truncates and returns the partial string:
    `return String(bytes, 0, len, StandardCharsets.UTF_8)`
  * In a security supervisor context, this creates a major vulnerability: the JVM validates a truncated prefix, but the kernel processes the full untruncated path, leading to a TOCTOU-like sandbox bypass.

### 10. Refactor Profiler core classes for testability and test them (`issue-20260726-0135`)
* **Category:** Testing / Maintainability
* **File:** `docs/internals/backlog/testing/issue-20260726-0135-refactor-profiler-classes-for-testability.md`
* **Status:** **STILL RELEVANT**
* **Verification & Evidence:**
  * Classes like `Profiler`, `ProfilerDaemon`, and `ProfilerSessionHandler` tightly couple core JVM tracing logic with environment-dependent Unix domain sockets, process spawns, and signal intercepts, making testing difficult without extensive mocking.
  * Structural separation and dependency injection are still highly recommended to reach a clean >80% test coverage target without flaky mock tests.

---

## 3. Logical & Structural Audit of the Profiler Module

Our audit across the codebase has highlighted the following architectural strengths and areas of notice:

1. **Vulnerability Chaining & Concurrency:**
   * **Strength:** The USER_NOTIF handshake protocol (`HandshakeSession.kt`) enforces synchronous lockstep between the tracee thread suspension and JVM trace recording. This guarantees that stack traces captured via JVM thread registry maps are perfectly aligned with the blocked syscall frame, preventing "drifted" stacks.
   * **Vulnerability:** Unbounded memory streams on trace queues (`ProfilerTraceListener`) present a Denial-of-Service vector if event volumes are high. This is confirmed under `issue-20260726-224607`.

2. **FFM ABI & Memory Safety:**
   * **Strength:** Alignment with the project's zero-leak FFI standard is excellent. The use of custom thread-safe lock-free `SegmentPool` pre-allocations (`SECCOMP_NOTIF_POOL`, `SECCOMP_NOTIF_RESP_POOL`) prevents layout alignment/drift errors and eliminates allocation and garbage collection overhead.
   * **Strength:** No raw `MemorySegment` objects or native addresses escape the FFI boundary, satisfying ArchUnit rules.

3. **Graceful Degradation:**
   * **Strength:** Safe fallback mechanisms (such as falling back to `/tmp` for socket directories when paths exceed limits) prevent runtime startup failures on nested environments.

4. **Test Verification Strength:**
   * **Strength:** The unit and integration tests are robust, run without mocking where possible, and explicitly verify kernel behaviors.
   * **Strength:** Specific regression tests (such as path prefix-collision tests) are present and active.

---

## 4. Next Steps & Summary

| Issue ID | Title | Status | Recommended Action |
|---|---|---|---|
| **`issue-019`** | Refactor Profiler Daemon to use Coroutines | **Open** | Keep active for future implementation. |
| **`issue-059`** | `IterativeProfiler` Context Loss via thread creation | **Open** | Keep active for future implementation. |
| **`issue-112`** | `IterativeProfiler` Logic Errors (Confirmed) | **Duplicate** | Mark file status as `resolved`/`duplicate`. |
| **`issue-195`** | Reactor Loop Iteration Arena Scoping | **Resolved** | Mark file status as `resolved`. |
| **`issue-056`** | `IterativeProfiler` Infinite Retry Loop on Disjoint Prefix | **Resolved** | Mark file status as `resolved`. |
| **`issue-20260726-224607`** | Profiler Trace Listener Unbounded Channel DoS / OOM | **Open** | Keep active for future implementation. |
| **`issue-20260726_011928_10`** | TraceEvent receives FFM values risking Use-After-Free | **Resolved** | Mark file status as `resolved`. |
| **`issue-20260726_011928_11`** | `IterativeProfiler` can exhaust thread pools | **Invalid** | Mark file status as `resolved` or `deferred`. |
| **`issue-20260726_011928_13`** | TraceEvent path truncation on excessively long paths | **Open** | Keep active for future implementation. |
| **`issue-20260726-0135`** | Refactor Profiler core classes for testability | **Open** | Keep active for future implementation. |

No new critical/structural security bypass vulnerabilities were discovered during this review beyond those already accurately cataloged in the backlog. The profiler module is exceptionally well-engineered, following proper encapsulation, FFM safety, and deterministic resource lifecycles.
