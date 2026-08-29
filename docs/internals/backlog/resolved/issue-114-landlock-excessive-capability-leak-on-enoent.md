---
title: "Landlock Excessive Capability Leak on `ENOENT`"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "enforcer"
effort: "medium"
github_issue: 255
paperclip_issue_id: 7ef08183-95d4-4916-8369-d20b4cb352f7
paperclip_identifier: MAZ-291
---

# 🟡 [Severity: LOW]: Landlock Excessive Capability Leak on `ENOENT`

*   **Dimension:** OS Invariants & Native Safety
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt`
*   **Observation:** When a path does not exist, `addRule` falls back to the parent directory. This grants access to the *entire* directory when the user only intended to allow a specific (future) file.
*   **Needed:** Implement `O_CREAT` awareness or document this broad fallback as a known limitation.
