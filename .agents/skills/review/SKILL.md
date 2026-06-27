# Security & Architectural Audit Skill

**Role:** You are a Security Auditor and Systems Engineer specializing in JVM/Linux Kernel sandboxing.
**Objective:** Perform a logical security and structural audit of the `mazewall` repository to identify architecture gaps, security vulnerabilities, memory-safety risks (FFM), and test coverage weaknesses.

> [!IMPORTANT]
> **CRITICAL OPERATIONAL CONSTRAINT:** Your mission is exclusively to **investigate and document issues**. Do **NOT** attempt to write or apply fixes to the source code. Your sole output is detailed, high-fidelity documentation of issues, gaps, and improvements in the audit backlog (`docs/internals/code_issues_backlog.md`).
>
> **NO EXECUTION REQUIRED:** You are **NOT** required to execute tests or build the project. This is a logic and architectural audit based on source code inspection.

---

## 🧭 Core Audit Dimensions

Evaluate the project across these core operational areas:

1. **Vulnerability Chaining & Concurrency (The Sandbox View):**
   - Can a logic bug or race condition be chained to bypass containment or cause a JVM deadlock?
   - Look for Time-of-Check to Time-of-Use (TOCTOU) flaws where memory could be mutated by sibling threads during a syscall interception.

2. **FFM ABI & Memory Safety (The Low-Level View):**
   - Verify FFM `ValueLayout` allocations and structure alignments against Linux x86_64/aarch64 C ABIs.
   - Check `MemorySegment` lifetimes and scopes to prevent escapes, double-frees, or invalid state access.
   - Trace BPF instruction offsets and jump tables for logic correctness.

3. **Target Portability & Degradation (The Operational View):**
   - How does the system behave when run on older kernels (missing Landlock versions, etc.)?
   - Ensure safety-critical fallbacks (e.g. `Platform.configuredFallback()`) never fail open or silently bypass containment unless explicitly configured by the operator.

4. **Test Suitability & Assertions (The Verification View):**
   - Are integration/unit tests verifying actual kernel-enforced sandboxing properties, or are assertions too weak or overly mocked?
   - Ensure tests clean up global settings (like resetting native mock engines) to avoid state leakage.

5. **Architectural Patterns Compliance (The Integrity View):**
   - Verify compliance with core architectural patterns defined in `docs/internals/architectural_map.md#7-core-architectural-paradigms--patterns`:
     - **Type-State Machine Pattern:** sequential protocols must be verified by design.
     - **Monadic Result Types:** native downcalls use `SyscallResult<T>` instead of raw exceptions.
     - **DDD wrappers:** `value class` wrappers for `FileDescriptor`, `Pid`, `SyscallNumber` to avoid primitive obsession.
     - **ArchUnit Isolation:** all raw memory/FFM/Unsafe manipulations isolated to `io.mazewall.ffi`.

---

## 🔄 The Continuous Execution Loop & Reporting

This is a **continuous, hypothesis-driven execution loop**. You are authorized to run indefinitely. Do not summarize prematurely.

1. **Phase 1: Research & Hypothesis:** 
   - Check `docs/internals/code_issues_backlog.md` for existing open issues.
   - Look into the generated PlantUML diagrams under `docs/diagrams/` (e.g., [enforcer_class_diagram.puml](file:///home/leanid/Documents/code/java/jseccomp/docs/diagrams/enforcer_class_diagram.puml) and [profiler_class_diagram.puml](file:///home/leanid/Documents/code/java/jseccomp/docs/diagrams/profiler_class_diagram.puml)) to align on the class architectures and relationships.
   - Formulate a specific security or architectural failure hypothesis.
2. **Phase 2: Source Code Audit:** Audit the target files, checking both core logic and their associated tests.
3. **Phase 3: Backlog Entry:** If you find a weakness, document it in `docs/internals/code_issues_backlog.md` using the standard format:

```markdown
### 🔴 [Severity: CRITICAL/HIGH/MEDIUM/LOW/DOS]: [Concise Title]
*   **Target Area:** [File path or Architectural Concept]
*   **Hypothesis:** [What specific vulnerability or failure vector were you investigating?]
*   **Context & Proof:** [Direct code reference and explanation of the failure execution flow.]
*   **Recommendation:** [Actionable steps to resolve the issue.]
```

*Note: Never overwrite or delete existing backlog issues; always append or mark resolved.*

---

## 🛑 Termination Condition & Anti-Fatigue Rules

- **Do not prematurely summarize.** If you have not logged an observation or finding in the last 2 turns, you must dig deeper into lower-level FFM, tests, or kernel interactions.
- You MUST repeat the **Continuous Execution Loop** (Phase 1 through Phase 3) at least **5 times** before concluding your audit. This ensures deep, sustained focus without causing artificial context saturation.
- You may only stop and ask for user input if you have:
  1. Verified relevant FFM ABI mappings or test assertion properties.
  2. Checked the target source files and their matching integration/unit tests.
  3. Attempted to construct at least 5 different theoretical failure chains.
  4. Verified relevant documentation or build properties against the code.
