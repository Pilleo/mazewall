---
title: "Gradle Configuration Avoidance Breakage via JitPack Shim"
severity: "RESOLVED"
status: "resolved"
priority: medium
paperclip_issue_id: 6b075d9a-b0bd-4e45-9347-f1385043ac89
paperclip_identifier: MAZ-331
---

# ✅ [RESOLVED]: Gradle Configuration Avoidance Breakage via JitPack Shim

*   **Status:** RESOLVED (June 2026)
*   **Target Area:** `build.gradle.kts`
*   **Context & Proof:** The use of `tasks.whenTaskAdded` disabled Gradle's Task Configuration Avoidance, forcing eager configuration of all tasks during the configuration phase, severely degrading IDE sync and build start times.
*   **Fix:** Replaced `tasks.whenTaskAdded` with the lazy API equivalent: `tasks.matching { it.name == "listDeps" }.configureEach { ... }`. This preserves Task Configuration Avoidance while still injecting the configurations property for JitPack's `listDeps` task.
