---
title: "`SeccompAction` Violates Open/Closed Principle (OCP)"
severity: "ENHANCEMENT"
status: "open"
---

# 🔵 [Severity: ENHANCEMENT]: `SeccompAction` Violates Open/Closed Principle (OCP)

**Target:** `io.mazewall.core.SeccompAction`
**Context:** Currently an `enum`, `SeccompAction` cannot support dynamic parameters (e.g., custom errno values for `ACT_ERRNO` or trace IDs for `ACT_TRACE`) without breaking changes or global modifications.
**Needed:** Refactor `SeccompAction` to a `sealed interface`. Use `data object`s for static actions and `data class`es for parameterized actions, enabling extensibility while maintaining exhaustive compiler checks.
