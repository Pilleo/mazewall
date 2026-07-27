---
title: "Mockable HTTP transport abstraction for RealJulesClient and TelegramBot"
severity: "HIGH"
status: "resolved"
priority: 5
dependencies: []
component: "orchestrator"
target_modules: [":tools:orchestrator"]
target_files: ["tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/JulesCli.kt", "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/TelegramBot.kt"]
github_issue: 364
---

# 🔴 [Severity: HIGH]: Mockable HTTP transport abstraction for RealJulesClient and TelegramBot

**Context:**
Both `RealJulesClient` and `TelegramBot` are coupled directly to the java standard `HttpClient`, making HTTP requests over the network. This makes them un-testable in unit tests.

**Needed:**
Introduce a mockable HTTP transport abstraction or dependency-inject the `HttpClient` / an HTTP client interface to allow offline test execution and simulate mock API responses.
