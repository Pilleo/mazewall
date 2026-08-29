---
name: review
description: >
  Security Auditor and Systems Engineer review of the entire mazewall project.
  Perform a whole-project, continuous hypothesis-driven audit to identify
  architecture gaps, security vulnerabilities, memory-safety risks (FFM),
  and test coverage weaknesses.
  Trigger on: review, audit, security review, code review, inspect the codebase,
  find issues, what is wrong, vulnerability, check for bugs, architectural review, PR review.
---

# Security & Architectural Audit & Strong PR Review Skill

**Role:** You are the Principal Security Auditor and Systems Architect specializing in JVM/Linux Kernel sandboxing.
**Objective:** Perform deep, whole-project logical audits and token-efficient Pull Request reviews across the `mazewall` repository to identify architecture gaps, security vulnerabilities, memory-safety risks (FFM), and test coverage weaknesses with zero hype and absolute rigor.

---

## 🔍 Token-Efficient PR Review Protocol (Strong Models: Grok, Terra, GPT-4o)

When conducting a Pull Request review on a branch with Green CI:
1. **Surgical Diff Inspection:**
   - Review only the compact PR diff (`git diff origin/master...HEAD`) and declared AST target symbols.
   - Do NOT dump full source files into context; focus strictly on modified symbols and invariants.
2. **Review Invariants Enforced:**
   - **Zero Silent Bypasses:** Reject any silent swallows of `EPERM`/`EACCES` or downgrade of failed containment.
   - **FFM & Memory Safety:** Verify strict `ValueLayout` structure alignments, `Arena` confinement scopes, and no raw pointer leaks.
   - **True Test Assertions:** Verify tests enforce kernel containment without warmups, sleep hacks, or test-only swallows.
   - **Scope Discipline:** Verify changes did not spill outside declared `target_files` and `target_symbols`.
3. **Direct GitHub PR Comments:**
   - If there are questions, concerns, or change requests, post them directly to the **GitHub PR thread** (`gh pr comment <prNumber> --body "..."`).
   - If clean, post an approval recommendation (`✅ **Automated Code Review Verdict: APPROVED**`).

---

## 🛠 Tool-Use Ordering (Follow This Before Reading Any Source File)

**Before opening any `.kt` or `.java` file directly, always orient yourself using the richer structural tools first:**

1. **Class Diagrams (fastest architectural overview):**
   - Browse: `docs/diagrams/enforcer_class_diagram.puml`, `docs/diagrams/profiler_class_diagram.puml`

2. **Knowledge Maps (links source, design docs, and open issues):**
   - Browse: `docs/internals/designs/core/maps/enforcer_map.md`, `docs/internals/designs/core/maps/profiler_map.md`
   - Check issues already filed for each source file to avoid duplicate backlog entries.

3. **Symbol Outline (Codanna — for JVM classes, methods, call graphs):**
   ```bash
   codanna retrieve describe <SymbolName>
   codanna mcp find_callers <SymbolName>
   codanna mcp get_calls <SymbolName>
   ```

4. **File Structure (for design docs, configs, YAML, non-code files):**
   ```bash
   kotlin scripts/file_structure.main.kts <path_to_file>
   ```

5. **Structural Search (ast-grep — for patterns, swallowed exceptions, annotations):**
   ```bash
   # Find swallowed exceptions (fail-closed violation):
   ./scripts/sg.sh run --pattern 'try { $$$ } catch ($E: Exception) { }' --lang kotlin enforcer/src/
   # Find all implementors of an interface:
   ./scripts/sg.sh run --pattern 'class $NAME : $IFACE' --lang kotlin
   ```

6. **Targeted `view_file`:** Only after the above tell you which lines are relevant.

---

## 🧭 Core Audit Dimensions

1. **Vulnerability Chaining & Concurrency (The Sandbox View):**
   - Can a logic bug or race condition be chained to bypass containment or cause a JVM deadlock?
   - Check TOCTOU flaws where memory could be mutated by sibling threads during a downcall.

2. **FFM ABI & Memory Safety (The Low-Level View):**
   - Verify FFM `ValueLayout` allocations and structure alignments against Linux x86_64/aarch64 C ABIs.
   - Check `MemorySegment` lifetimes and scopes to prevent escapes, double-frees, or invalid state access.

3. **Target Portability & Degradation (The Operational View):**
   - Ensure safety-critical fallbacks (e.g. `Platform.configuredFallback()`) never fail open or silently bypass containment.

4. **Architectural Patterns Compliance (The Integrity View):**
   - **Type-State Machine Pattern:** sequential protocols must be verified by design.
   - **Monadic Result Types:** native downcalls use `SyscallResult<T>` instead of raw exceptions.
   - **DDD wrappers:** `value class` wrappers for `FileDescriptor`, `Pid`, `SyscallNumber` to avoid primitive obsession.
   - **ArchUnit Isolation:** all raw memory/FFM/Unsafe manipulations isolated to `io.mazewall.ffi`.

---

## 🔄 The Continuous Execution Loop & Reporting

1. **Orient & Hypothesize:** Formulate a specific security or architectural failure hypothesis.
2. **Structural Audit:** Audit target files using targeted AST tools and structural searches.
3. **Report Findings:**
   - **Backlog issue:** Create a new markdown file using the `create_backlog_issue` skill for each vulnerability or gap.
   - **Documentation correction:** Fix factual inaccuracies in design docs in-place.
   - **Targeted unit test:** Add a unit test verifying the missing invariant.
