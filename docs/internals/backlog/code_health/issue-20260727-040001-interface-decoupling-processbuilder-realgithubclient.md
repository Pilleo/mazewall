---
title: "Interface decoupling for ProcessBuilder execution in RealGitHubClient"
severity: "HIGH"
status: "open"
priority: medium
dependencies: []
component: "orchestrator"
target_modules: [":tools:orchestrator"]
target_files: ["tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt"]
---

# 🔴 [Severity: HIGH]: Interface decoupling for ProcessBuilder execution in RealGitHubClient

**Context:**
The current `RealGitHubClient` executes git and gh CLI commands via direct Java `ProcessBuilder` execution. This makes it un-testable inside standard environments where the GitHub CLI might not be installed or authenticated.

**Needed:**
Introduce a process execution interface or execution abstraction that can be mocked/faked in unit tests, allowing us to thoroughly test the edge cases in `RealGitHubClient`.
