---
title: "Standard Java Concurrency (`Virtual Threads`, `CompletableFuture`) trivially bypasses Thread-Scoped (Tier 2) containment without ACE"
severity: "CRITICAL"
status: "open"
priority: 5
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: false
---


# 🔴 [Severity: CRITICAL]: Standard Java Concurrency (`Virtual Threads`, `CompletableFuture`) trivially bypasses Thread-Scoped (Tier 2) containment without ACE

**Target:** `io.mazewall.enforcer.ContainedExecutors` and `docs/internals/designs/core/security-considerations.md`
**Failure Hypothesis:** A developer wraps an `ExecutorService` using `ContainedExecutors.wrap(delegate, Policy.NO_NETWORK)` to safely process an untrusted document. The untrusted parsing logic calls standard Java APIs like `CompletableFuture.runAsync { ... }` or `Thread.startVirtualThread { ... }`. Because these APIs delegate execution to the JVM's pre-existing `ForkJoinPool.commonPool()` (whose OS carrier threads were spawned at JVM startup and lack the seccomp filter), the delegated task executes entirely unconstrained.
**Context & Proof:** Seccomp and Landlock filters are strictly inherited via the Linux `clone` syscall. While `mazewall` correctly notes that Arbitrary Code Execution (ACE) can poison sibling threads, it fails to account for the fact that standard, safe Java APIs bypass thread-scoped containment by design. An attacker does not need memory corruption (ACE) or native access; they only need to submit a closure to a standard thread pool. Any network request or file access within that closure will succeed, instantly neutralizing the Tier 2 containment.
**Vulnerability Chain Potential:** Critical. Completely invalidates the security boundary of Tier 2 `wrap()` for any workload that isn't strictly synchronous and single-threaded. Malicious libraries can easily initiate SSRF or read files by simply hopping threads.
**Needed:**
1. Document this fundamental architectural bypass clearly in `designs/core/security-considerations.md` alongside the ACE pivot. Emphasize that Tier 2 containment only restricts synchronous execution on the current thread.

## Solution Options

### Option A
(To be filled)

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ]

**Implementation Hints:**
-
