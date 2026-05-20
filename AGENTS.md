# Guidelines for AI Coding Agents in jseccomp

Welcome, AI Agent. This repository contains **jseccomp**, a kernel-enforced, thread-scoped and process-wide sandboxing library for JVM applications using Linux **Seccomp-BPF** and **Landlock LSM** via the JDK **Foreign Function & Memory (FFM) API**.

As an AI agent pair-programming on this project, you are assisting in transitioning this project from a Proof of Concept (PoC) to a production-grade library targeting **Java 25**. Because this is a security-critical project that directly interfaces with the Linux kernel and manipulates JVM threads, you must adhere strictly to the following rules, constraints, and engineering philosophies.

---

## 1. Core Engineering Philosophy & Tone

### Zero Hype, Absolute Certainty
*   **No Marketing or Speculative Language:** Avoid promotional, flashy, or hand-wavy descriptions. There is no room for overpromising, untold risks, or ambiguity. This library operates at the kernel-user space boundary where errors lead to fatal JVM deadlocks or JVM bypasses.
*   **Rigorous Decision Making:** Every choice in the code must be double and triple checked. A single missed detail or incorrect assumption can result in catastrophic failure (such as JVM deadlocks, kernel instability, or silent security bypasses). All pros and cons of any proposed architectural or implementation approach must be exhaustively weighed.
*   **Honest Limitations:** Every security boundary must be documented with its exact threat model, caveats, and failure modes. If you are not 100% sure about a kernel behavior, JVM internal mechanism, or system call side-effect:
    1.  **Do not guess or assume.**
    2.  Search the codebase or Linux manual pages.
    3.  Flag the uncertainty explicitly in comments and discuss it with the developer.
*   **Documentation Split:**
    *   **Presentation Folder (`/presentation`):** Articles here are addressed to general Backend/Software Engineers. They must be conceptually accessible, focus on architectural diagrams, threat models, and real-world exploit showcases (e.g. neutralizing Log4Shell, fileless malware). Avoid extremely dense kernel-level assembly or BPF jump tables here.
    *   **Core Code, Javadocs, & Design Docs:** Must be highly rigorous and detail-oriented. Explain the exact system call numbers, FFM memory layouts, signal contexts, register transitions, and kernel invariants. Javadocs and KDocs are highly encouraged on all classes and methods.
*   **Mandatory Documentation of Findings:** Any new important architectural finding, kernel behavior discovery, or security nuance uncovered during development or discussion *must* be immediately documented in the appropriate Markdown file (e.g., `SECURITY_CONSIDERATIONS.md`, `README.md`, or inside the `/presentation` folder). Do not leave critical insights isolated in conversation histories.

---

## 2. Strict Protection Against Unsafe Fallback / Bypass Scenarios

> [!WARNING]
> **CRITICAL SECURITY INSTRUCTION:** AI agents historically tend to implement "fail-safe" or "silent bypass" fallback behavior in order to avoid compiler/runtime errors and make code demonstrations or tests "just work." **This is strictly unacceptable in a security library.**

*   **Never Implement Silent Bypasses:** Do not catch exceptions silently or downgrade a failed seccomp/Landlock installation to a "warning and bypass" unless that fallback is explicitly defined in the policy's fallback configuration.
*   **Fail Closed by Default:** If a security mechanism cannot be initialized, a system call cannot be resolved, or an environment constraint is not met, the library must fail-closed (e.g., throwing a terminal `UnsupportedOperationException` or `IllegalStateException`) rather than silently leaving the application uncontained.
*   **No Unconsulted Fallbacks:** Do not design or write automatic recovery loops or mock environments (like simulating a system call return value via register manipulation) without explicitly alignment and review from the developer.

---

## 3. Directory Structure & Architecture

The repository is organized as a multi-module Gradle project:
*   **`/utils` (Core Library):** The main implementation module containing the native bridges, policy models, and thread wrapping.
    *   `Platform.kt`: Determines OS support (Linux x86_64 or aarch64) and handles fallback configuration.
    *   `LinuxNative.kt`: High-performance native bindings using the JDK FFM API to invoke system calls (`prctl`, `syscall`, `sigaction`).
    *   `Arch.kt`: Architecture-specific maps containing CPU AUDIT identifiers and system call tables (x86_64 and aarch64).
    *   `BpfFilter.kt`: Pure Java/Kotlin compilation of BPF linear instruction streams for Seccomp argument inspection.
    *   `Policy.kt`: Composable security policies (e.g. `PURE_COMPUTE`, `NO_NETWORK`, `NO_EXEC`).
    *   `Landlock.kt`: Path-aware sandboxing configuration using Linux Landlock LSM, handling JVM classpath automatic whitelisting to prevent classloading failures.
    *   `ContainedExecutors.kt`: The public API wrapping `ExecutorService` to load seccomp filters when worker threads initialize.
*   **`/demo` (Log4Shell Showcase):**
    *   Demonstrates a simulated JNDI-like remote code execution vulnerability blocked by wrapping the executing thread pool in a contained executor running under `Policy.NO_EXEC`.
*   **`/presentation`:** Markdown files detailing developer articles on deep-dive topics, design trade-offs, and issues backlog.

---

## 4. Critical JVM & Linux Kernel Safety Rules (The Hard Limits)

Any modification you make to the BPF builders, custom policies, or native integration must comply with these hard architectural constraints:

### Rule A: Never Block JVM Coordination System Calls
Application threads running within the HotSpot JVM periodically enter **Safepoints** for Garbage Collection, Thread Dumps, or Deoptimization. If your policy blocks system calls required for thread scheduling or memory management, the next safepoint will permanently freeze the entire JVM.
*   **Prohibited from Blocking:**
    *   `futex` (used for thread synchronization and JVM parking).
    *   `sched_yield` (invoked during spinlock contention).
    *   `rt_sigreturn` (needed to return from JVM internal signal handlers).
    *   `madvise` / `mprotect` (used by GC threads for page allocation).
    *   `gettid` (required for thread identification).
*   **Enforce Guards:** Ensure `Policy.Builder` asserts that these system calls cannot be added to a blocked category.

### Rule B: Prevent Loom Virtual Thread Carrier Poisoning
Seccomp filters apply strictly to the underlying Linux OS thread (LWP). 
*   **The Hazard:** Loom Virtual Threads share carrier OS threads in a dynamic pool (`ForkJoinPool`). If you load a seccomp filter from within a Virtual Thread, that filter remains bound to the underlying carrier thread forever. Subsequent Virtual Threads mapped to that carrier will inherit the restricted security context, causing unexpected application crashes.
*   **The Protection:** Always maintain runtime guardrails that detect Virtual Threads (`Thread.currentThread().isVirtual`) and throw `IllegalStateException` to prevent seccomp stacking on carrier threads. Virtual thread execution must always proactively configure restricted carrier pools as outlined in `plan.md`.

### Rule C: Shared-Memory ACE Escape Caveat
*   **The Threat Model:** Thread-scoped seccomp is not an absolute security boundary against an attacker who achieves **Arbitrary Code Execution (ACE)** inside the sandboxed thread.
*   **The Mechanism:** Because all JVM threads share the same physical address space (heap, class metadata, stacks), an attacker with native execution capabilities can bypass seccomp by altering JVM memory tables or writing tasks directly into unrestricted thread queues.
*   **The Action:** Frame thread-scoped seccomp as a highly effective, low-overhead shield against standard application data attacks (SSRF, Path Traversal, XXE, SQL Injection), but mandate process-wide `NO_EXEC` (Tier 1) at JVM startup for absolute protection against process execution escalation.

---

## 5. Development & Coding Conventions

### A. FFM API Patterns
We do not use JNI or native C libraries. All kernel communication must use pure Kotlin/Java FFM API (`java.lang.foreign`):
*   Target strictly **Java 25**. Make sure FFM features utilized match the latest specifications in JDK 25.
*   Use `Arena.ofConfined()` or structured `Arena` scopes for safe off-heap allocations (`MemorySegment`).
*   Always use `Linker.Option.captureCallState("errno")` when linking system calls. The standard JVM does not maintain `errno` states, so you must capture and translate it immediately via `Native.getLastError()` or the captured state segment to avoid race conditions with other JVM threads.
*   Ensure proper struct alignment and offsets when packing BPF arrays (e.g. `sock_fprog` and `sock_filter`).

### B. Containment Exception Translation
Because the Java Standard Library does not expose raw OS `errno` integers directly, violation detection must rely on a reliable translation strategy:
1.  **Priority 1:** Match exact JVM-encoded error numbers (e.g. `"error=1"` for `EPERM`, `"error=13"` for `EACCES`). This is completely locale-insensitive.
2.  **Priority 2:** Match OS error message patterns (e.g., `Permission denied`, `Operation not permitted`), but **only** for `IOException` or `SocketException` subclasses.
3.  **Prohibited:** Do not use broad fragments like `"denied"` without class restrictions, as this will trigger false positives on non-system-call exceptions (e.g. "Access denied by application rule").

---

## 6. Testing and Verification Guidelines

To verify changes securely:
*   **Testing is Mandatory:** This is a security library; untested code is a vulnerability. Any bugfix, behavioral change, or new important architectural detail **must** be accompanied by an automated test.
*   **OCI Sandbox Stacking:** The Linux kernel requires `PR_SET_NO_NEW_PRIVS` to stack seccomp filters. Standard OCI and Docker environments block unprivileged seccomp installation unless configured explicitly.
*   **Running Tests:** Always run tests using the custom OCI profile:
    ```bash
    docker compose up -d
    docker compose exec jseccomp ./gradlew test
    ```
    This profile (`docker-seccomp.json`) whitelists `seccomp(2)` to allow filter stacking safely without requiring root or broad container privileges.
*   **Platform Guards:** Always guard Linux-only system call integration tests using JUnit's `@EnabledOnOs(OS.LINUX)` or platform-compatibility checks to avoid breaking macOS/Windows developer environments.
*   **GraalVM Support Roadmap:** Keep in mind that native AOT presets are not actively implemented, but are planned for future integration. Write code with clean separation to facilitate this future shift.
