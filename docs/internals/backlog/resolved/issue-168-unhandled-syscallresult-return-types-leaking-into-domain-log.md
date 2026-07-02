---
title: "Unhandled `SyscallResult` return types leaking into domain logic"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Unhandled `SyscallResult` return types leaking into domain logic

*   **Status:** RESOLVED (June 2026)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/` and `io/mazewall/landlock/`
*   **Context & Proof:** `domainLogicMustHandleSyscallResults` only checked public methods, allowing internal/private domain logic to leak raw FFM `SyscallResult` objects.
*   **Fix:** Refactored internal methods in `Landlock.kt` to encapsulate all `SyscallResult` usages inside clean domain structures (`AddRuleResult`, `OpenResult`) or exceptions. Expanded the check in `ArchitectureTest.kt` to audit all methods in these packages.
