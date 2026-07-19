# Guidelines for AI Coding Agents in mazewall

Welcome, AI Agent. This repository contains **mazewall**, a kernel-enforced, thread-scoped and process-wide sandboxing library for JVM applications using Linux **Seccomp-BPF** and **Landlock LSM** via the JDK **Foreign Function & Memory (FFM) API**.

As an AI agent pair-programming on this project, you are assisting in transitioning this project from a Proof of Concept (PoC) to a production-grade library. The minimum supported JDK is **22** (FFM API finalization); the codebase targets **Java 25 idioms** where applicable. Because this is a security-critical project that directly interfaces with the Linux kernel and manipulates JVM threads, you must adhere strictly to the following rules, constraints, and engineering philosophies.

---

## 🚧 Hard Boundaries

**⚠️ Ask First:**
*   Adding new dependencies.
*   Making breaking API changes.
*   Modifying the core BPF linear scan architecture.

**🚫 Never Do:**
*   Never catch `EPERM` or `EACCES` exceptions without rethrowing or crashing (No silent bypasses).
*   Never block JVM coordination syscalls (refer to the detailed list in [enforcer/AGENTS.md](enforcer/AGENTS.md#1-never-block-jvm-coordination-system-calls)).
*   Never combine `SECCOMP_FILTER_FLAG_TSYNC` and `SECCOMP_FILTER_FLAG_NEW_LISTENER`.
*   Never use `JAVA_LONG` for 32-bit `sock_filter` fields.
*   **Never modify, filter, or handle the `GITHUB_TOKEN` environment variable in the codebase.** Managing or modifying GitHub CLI credentials or environment variables is strictly the operator's responsibility.
*   **Never call `view_file` on a `.kt` or `.java` file without first running `codanna retrieve describe <ClassName>` or `kotlin scripts/file_structure.main.kts <path_to_file>` to inspect its outline.** The only exception is if you have already outlined this specific file in the CURRENT turn.

---

## 📓 Code Issues & Discoveries Journal

Whenever you discover a bug, architectural gap, kernel-level nuance, or security vulnerability, you MUST log it by creating a new markdown file in the [backlog directory](docs/internals/backlog/) (e.g. `issue-182-some-bug.md`) and registering it in [docs/internals/backlog/README.md](docs/internals/backlog/README.md). Do not leave critical insights buried in chat history.

**Format for new issue files (include YAML frontmatter):**
```markdown
---
title: "Title of Issue"
severity: "HIGH/MEDIUM/LOW/CRITICAL/ENHANCEMENT"
status: "open"
---

# 🔴 [Severity: HIGH]: Title of Issue
**Context:** [What you found and why it exists]
**Needed:** [How to fix or prevent it]
```

---

## 📝 Presentation & Output Format

When presenting a fix or creating a PR, use the following format:
*   **🚨 Severity:** [CRITICAL / HIGH / MEDIUM / LOW / ENHANCEMENT]
*   **💡 Issue:** [Description of the vulnerability or bug]
*   **🎯 Impact:** [What happens if triggered, e.g., JVM Deadlock, logic error]
*   **🔧 Fix:** [How it is resolved]
*   **✅ Verification:** [How the fix was tested]

---

## 🔄 Iterative Development & Testing

*   **Step-by-Step Execution:** Do not attempt massive refactors in a single pass. Make changes iteratively and surgically.
*   **Source File Inspection (API Outline):** Before reading the full contents of any source file, you MUST inspect its API surface or symbols first to save context tokens. You may use:
    - **Codanna** (`codanna retrieve describe <SymbolName>` or MCP tools) for semantic JVM code symbols, class declarations, and cross-references.
    - The native Kotlin script (`kotlin scripts/file_structure.main.kts <path_to_file>`) for outlining a specific file structure on disk (such as Markdown documents, YAML configs, or files containing multiple classes).
*   **Constant Verification:** Test after **each** logical step using the Testcontainers suite (`./gradlew test`). The codebase must remain buildable and tests must pass at every intermediate stage.

---

## 1. Core Engineering Philosophy & Tone

### Zero Hype, Absolute Certainty
*   **No Marketing or Speculative Language:** Avoid promotional, flashy, or hand-wavy descriptions. This library operates at the kernel-user space boundary where errors lead to fatal JVM deadlocks or JVM bypasses.
*   **Rigorous Decision Making:** Every choice must be double and triple checked. A single missed detail can result in catastrophic failure (JVM deadlocks, kernel instability, silent security bypasses).
*   **Honest Limitations:** Every security boundary must be documented with its exact threat model, caveats, and failure modes. If you are not 100% sure about a kernel behavior, JVM internal mechanism, or system call side-effect:
    1.  **Do not guess or assume.**
    2.  Search the codebase, `designs/core/security-considerations.md`, `designs/profiler/profiler-design.md`, `designs/enforcer/containment-design.md`, and Linux manual pages — in that order. These files contain hard-won, project-specific kernel behavior discoveries that man pages do not cover.
    3.  Flag the uncertainty explicitly in comments and discuss it with the developer.
*   **Documentation Split:**
    *   **`/presentation`:** Addressed to general Backend/Software Engineers. Conceptually accessible; no BPF jump tables.
    *   **Core code, KDocs, & design docs:** Highly rigorous. Exact syscall numbers, FFM memory layouts, kernel invariants.
*   **Mandatory Documentation of Findings:** Any new architectural finding, kernel behavior discovery, or security nuance *must* be documented immediately in the appropriate Markdown file. Do not leave critical insights in conversation histories.

### Code Maintainability & Craftsmanship Invariants
All code must adhere strictly to the centralized [mazewall Code Quality & Craftsmanship Standards](.agents/CODE_QUALITY.md). Read and follow it for rules regarding SOLID design, type verification, immutability/FP, AOT friendliness, logical modularity, and debuggability.

---

## 2. Strict Protection Against Unsafe Fallback / Bypass Scenarios

> [!WARNING]
> **CRITICAL SECURITY INSTRUCTION:** AI agents historically tend to implement "fail-safe" or "silent bypass" fallback behavior to make code "just work." **This is strictly unacceptable in a security library.**

*   **Never Implement Silent Bypasses:** Do not catch exceptions silently or downgrade a failed seccomp/Landlock installation to a warning-and-bypass unless that fallback is explicitly configured by the operator.
*   **Fail Closed by Default:** The **default `FallbackBehavior` is `FAIL`** (see `Platform.configuredFallback()` — it returns `FallbackBehavior.FAIL` unless the operator explicitly overrides via `-Dio.mazewall.fallback=WARN_AND_BYPASS` or `IO_MAZEWALL_FALLBACK=WARN_AND_BYPASS`). This is intentional and must not be changed.
*   **No Unconsulted Fallbacks:** Do not write automatic recovery loops or mock environments (like simulating a syscall return value via register manipulation) without explicit operator consent, even if the immediate effect appears safe.

---

## 3. Directory Structure & Technical Delegations

`mazewall` is split into two specialized subprojects. Detailed engineering safety rules, FFM design conventions, and architectural bounds are documented in the respective **child `AGENTS.md` files**:

### A. The `:enforcer` Module (Core Sandbox Engine)
Responsible for production-grade sandboxing using Linux Seccomp-BPF and Landlock LSM through the JDK Foreign Function & Memory (FFM) API.
*   **Key Source Files:** `Policy.kt`, `BpfFilter.kt`, `PureJavaBpfEngine.kt`, `Landlock.kt`, `ContainedExecutors.kt`, `LinuxNative.kt`, `Platform.kt`.
*   **Engineering Rules:**
    > [IMPORTANT]
    > Before making any changes inside `/enforcer`, you **must** read and adhere to the strict guidelines in **[enforcer/AGENTS.md](enforcer/AGENTS.md)**.
    >
    > It covers preventing Loom Virtual Thread carrier poisoning, native FFM layout alignments, and raw syscall constraint designs.

### B. The `:profiler` Module (Developer Diagnostic Suite)
Responsible for unprivileged system call profiling and Landlock path discovery using BPF `USER_NOTIF` sockets, progressive testing, and descendant `strace` parsing.
*   **Key Source Files:** `Profiler.kt`, `ProfilerDaemon.kt`, `IterativeProfiler.kt`, `StraceProfiler.kt`, `BobCompiler.kt`, `BillOfBehavior.kt`.
*   **Engineering Rules:**
    > [IMPORTANT]
    > Before making any changes inside `/profiler`, you **must** read and adhere to the strict guidelines in **[profiler/AGENTS.md](profiler/AGENTS.md)**.
    >
    > It covers the critical out-of-process `USER_NOTIF` ACK loop deadlock prevention, Yama `ptrace_scope` configurations, and `strace` log parsing.

---

## 4. Shared-Memory ACE Escape Caveat (The Core Threat Model)

Thread-scoped seccomp is **not** an absolute security boundary against an attacker with Arbitrary Code Execution (ACE) on the sandboxed thread. Because all JVM threads share the same address space and heap, a native memory corruption exploit (e.g., via buffer overflow or FFM `Unsafe` pointer manipulation) on a contained thread can corrupt memory on unrestricted sibling or helper threads to achieve escape.

*   **Mandatory Baseline:** Tier 1 (process-wide `NO_EXEC` baseline, via `ContainedExecutors.installOnProcess`) is an absolute architectural backstop, not an optional recommendation. Stacking thread-scoped Tier 2 containment on top mitigates the blast radius of data-oriented attacks (SSRF, XXE, SQLi), but must never be presented alone as a complete security boundary. Refer to [designs/core/security-considerations.md](docs/internals/designs/core/security-considerations.md) for the complete threat matrix.
*   **Namespaces & cgroups Roadmap (Tier 1 Expansion):** Process-wide Mount/Network/PID namespaces and cgroups v2 limits are planned on the roadmap to reinforce the Tier 1 baseline at process initialization, ensuring escapes from memory corruption remain contained inside the process boundaries. Thread-local namespaces are explicitly rejected due to JVM coordination conflicts.

---

## 5. Testing and Verification Guidelines

*   **Prioritize TDD (Test-Driven Development):** Whenever possible, follow a TDD workflow.
    *   **For Bug Fixes:** You MUST empirically reproduce the reported issue by writing a failing test case before applying any code changes.
    *   **For New Features:** Define the expected behavior with tests before implementing the logic.
*   **Testing is Mandatory:** Any bugfix, behavioral change, or new parameter **must** be accompanied by an automated test.
*   **Running Tests:** Always run using the nested-seccomp OCI profile via the provided Podman orchestration scripts. This ensures the correct kernel capabilities and seccomp filters are applied:
    - `./gradlew test` — Runs host-side unit tests only (fast, no kernel interaction).
    - `./scripts/run_tests.sh` — Runs the full integration test suite inside a container.
    - `./scripts/run_vulnerable_app_demo.sh` — Executes the end-to-end CVE exploitation demo.
    - `./scripts/check_coverage.sh` — Verifies Jacoco thresholds.
    - `./scripts/lint.sh` — Runs static analysis (Detekt, SpotBugs, ktlint).
    ```bash
    ./scripts/run_tests.sh
    ```
*   **Module Check Tasks:** Verify your changes specifically pass module checks:
    *   `:enforcer:check` (Landlock >= 65%, LinuxNative >= 78%, core classes >= 80% Jacoco instruction coverage)
    *   `:profiler:check` (Profiler >= 60% Jacoco instruction coverage)

---

## 6. Native Engine Traits & Fault Injection

To maintain high testability, `mazewall` avoids direct static calls to native JNI/FFM methods. Instead, core components interact with the `NativeEngine` trait interfaces.
For detailed implementation examples and test usage of the `MockNativeEngine` pattern, refer to the documentation in [enforcer/AGENTS.md](enforcer/AGENTS.md#7-native-engine-decoupling-for-testability).

---

## 7. Key Design Documents

Before modifying components, read the relevant design document:

| Document | Covers |
|---|---|
| [designs/enforcer/containment-design.md](docs/internals/designs/enforcer/containment-design.md) | BPF scan loops, argument inspections, Landlock ordering, FFM layouts. |
| [designs/profiler/profiler-design.md](docs/internals/designs/profiler/profiler-design.md) | USER_NOTIF architecture, socket SCM_RIGHTS, ACK loop protocol. |
| [designs/core/security-considerations.md](docs/internals/designs/core/security-considerations.md) | Full threat model, ACE escape caveats, K8s custom profiles, Yama scopes. |

## 8. Cross-Module Change Protocol
If a change touches both `:enforcer` and `:profiler`:
1. Complete and verify `:enforcer` changes first.
2. Run `:enforcer:check` before starting `:profiler` work.
3. Update `Syscall.kt` and `Arch.kt` in `:enforcer` before referencing the new enum in profiler.

## 9. Task verification protocol
After any code changes, run `./gradlew build` to verify the final changes.
You may run more granular checks in the process, but build must be always green before you submit the results.

## 10. Codebase Intelligence & Search Tools

To optimize context token consumption and perform precise codebase navigation:

*   **Codanna (Symbol Lookup & Call Graphs):** Use the helper wrapper `./scripts/code_atlas.sh`, or raw `codanna retrieve` / `codanna mcp` directly. It is completely CLI-only/one-shot; no background daemon server needs to be running. Refer to [.agents/skills/file_structure/SKILL.md](file:///.agents/skills/file_structure/SKILL.md) for detail usage.
*   **Searching for Symbols:** Use `codanna mcp find_symbol <Name>` instead of `grep_search` for class/function definitions. Use `codanna mcp find_callers <Name>` instead of `grep_search` for call-site discovery. Do not use `grep_search` as your primary navigation tool for symbols.
*   **ast-grep (Structural Code Search):** Use the repository wrapper `./scripts/sg.sh` for syntax-aware pattern searches and refactoring. Refer to [.agents/skills/ast_grep/SKILL.md](file:///.agents/skills/ast_grep/SKILL.md) for detail usage.

---

## 11. Available Agent Skills

The `.agents/skills/` directory contains reusable, step-by-step workflows for common tasks. Use these skills proactively when they match the task at hand — they encode hard-won lessons specific to this codebase.

| Skill directory | Use when... |
|---|---|
| `add_syscall` | Adding a new syscall constant to `Policy`, `BpfFilter`, or the profiler |
| `ast_grep` | Performing structural, syntax-aware search and replace on source code |
| `ffm_safety` | Making any FFM/off-heap memory changes (layouts, arenas, downcalls) |
| `fix_backlog_item` | Fixing bugs/backlog items cleanly without warmups, swallows, or hacks |
| `loop_driven_development` | Iterative red-green-refactor TDD cycle for new features |
| `report_security_issue` | Discovering a new vulnerability, bypass risk, or kernel behavior gap |
| `review` | Code review of a patch, PR, or proposed design |
| `spec_driven_development` | Building a feature from a written spec document |
| `update_docs` | Keeping design docs in sync after code changes |
| `file_structure` | Inspecting any file's outline/structure before reading its full content |

