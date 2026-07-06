---
title: "Detect and Handle CLI Authentication Failures with Actionable Login Alerts"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
effort: "small"
---

# 🔴 [Severity: HIGH]: Detect and Handle CLI Authentication Failures with Actionable Login Alerts

**Context:**
The Autonomous Backlog Orchestrator invokes external command-line tools (`gh`, `jules`, and `agy` / Antigravity) to manage GitHub issues, trigger agent sessions, and execute reviews. If any of these tools fails due to expired or missing credentials (authentication errors), the daemon currently crashes or logs generic command execution exceptions. This makes troubleshooting difficult for the operator, who must manually dig into log traces to figure out which tool failed to authenticate.

**Needed:**
We need to capture command execution failures, specifically parsing standard error outputs or exit codes for authentication-related errors, and notify the operator with explicit instructions on how to log in.

Specifically:
1. **Detect Auth Failures:**
   In command execution wrappers (like `execute` / `executeCmd` / `executeWithPipe`), scan the `stderr` string or check exit codes for signature authentication errors. Typical patterns include:
   * **GitHub CLI (`gh`):** Errors containing "not authenticated", "GH_TOKEN", or "sign in".
   * **Jules CLI (`jules`):** Errors containing "not logged in", "authentication required", or "unauthorized".
   * **Antigravity CLI (`agy`):** Token validation errors.
2. **Raise Structured Exceptions:**
   Map these errors to a specific `CliAuthenticationException` carrying details about the failing tool.
3. **Notify Operator:**
   Catch `CliAuthenticationException` in the daemon loops, print a prominent warning to the terminal, and send a clear Telegram message detailing how the operator can resolve the issue (e.g., *"⚠️ GitHub CLI is not authenticated. Please run `gh auth login` on the host to continue."*).
