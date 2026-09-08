---
title: "Interface decoupling for ProcessBuilder execution in RealGitHubClient"
severity: "HIGH"
status: "open"
priority: medium
dependencies: []
component: "orchestrator"
target_modules: [":tools:orchestrator"]
target_files: ["tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt"]
paperclip_issue_id: "b7c66a41-b95f-4cd9-afad-d79df610a67e"
paperclip_identifier: "MAZ-1076"
---

# 🔴 [Severity: HIGH]: Interface decoupling for ProcessBuilder execution in RealGitHubClient

**Context:**
The current `RealGitHubClient` executes git and gh CLI commands via direct Java `ProcessBuilder` execution. This makes it un-testable inside standard environments where the GitHub CLI might not be installed or authenticated.

**Needed:**
Introduce a process execution interface or execution abstraction that can be mocked/faked in unit tests, allowing us to thoroughly test the edge cases in `RealGitHubClient`.
