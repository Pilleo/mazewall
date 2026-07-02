---
title: "Landlock Excessive Capability Leak on `ENOENT`"
severity: "MEDIUM"
status: "open"
---

# 🟡 [Severity: LOW]: Landlock Excessive Capability Leak on `ENOENT`

*   **Dimension:** OS Invariants & Native Safety
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt`
*   **Observation:** When a path does not exist, `addRule` falls back to the parent directory. This grants access to the *entire* directory when the user only intended to allow a specific (future) file.
*   **Needed:** Implement `O_CREAT` awareness or document this broad fallback as a known limitation.
