---
title: "Orchestrator Resiliency and Configuration Improvements"
severity: "HIGH"
status: "resolved"
priority: 10
dependencies: []
component: "orchestrator"
effort: "medium"
github_issue: 63
---

# 🔴 [Severity: HIGH]: Orchestrator Resiliency and Configuration Improvements

**Context:**
The orchestrator has several hardcoded variables and architectural limitations that prevent it from being fully resilient under network partition, service restarts, or API rate limiting.

**Needed:**
Implement the following improvements:
1. **Configurable Polling & Timeouts:** Move polling sleep intervals (currently hardcoded 15s/30s) and maximum task timeout thresholds to an external configuration file or environment variables (`.env`).
2. **GitHub CLI Request Caching:** Implement short-TTL in-memory caching for `gh` checks on PR states and build statuses to mitigate the risk of GitHub API rate limits.
3. **Persistent State Tracking:** Save the active task context (e.g. current issue, PR number, session ID) to a local JSON file or SQLite database. This allows the Orchestrator to resume monitoring from the exact previous state after a restart.
4. **Command Execution Retries:** Add an exponential backoff retry mechanism for external CLI executions (`executeCmd`/`executeWithPipe`) to survive transient network or auth errors.
