---
title: "Demonstrate Spring report HTTP security JDBC and owned shutdown together"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
  - "demos/spring-report-api"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Demonstrate Spring report HTTP security JDBC and owned shutdown together

**Context:**
Approved pitch plan Tasks3-5: independent Boot4.1.1 Java25 web/JDBC/Security/Actuator consumer using actual retained singleton and qualified JDBC guard, explicit runtime/resource/review bootstrap and pre-traffic binding, real HTTP commit/kernel refusal/postauth rollback/context reuse, honest cancellation/shutdown, safe request-specific serial-worker receipts. Demo-only starter additions approved. Normal serve never approves itself.

**Needed:**
1. Build host and finished-JAR HTTP tests first, implement minimal report app and explicit bootstrap/advisor ordering, preserve normal Boot infrastructure, add lifecycle/readiness and safe per-request outcomes, copied-published acceptance and scoped final review. No separate journals/worktrees or remote publication.

## Inline progress

- [ ] Task3 standalone HTTP/Security/JDBC application and explicit pre-traffic binding.
- [ ] Task4 readiness, retained cancellation and actual owned shutdown.
- [ ] Task5 request-specific safe outcomes and serial-worker transaction receipts.
- [ ] Task7 copied-artifact acceptance and final whole-change review/gates.

Pre-flight: request receipt capture occurs on the dedicated serial worker before
another invocation can overwrite lastCompletion; HTTP threads never use that global
latest report as their own receipt. External review stays separate from fixture demo
approval. Default Boot infrastructure remains active for unrelated application beans.
Approved starters are application-only; existing library/runtime versions stay fixed.

---
<!-- id: issue-20261029-112338-072-demonstrate-spring-report-http-security-jdbc-and-o  file: issue-20261029-112338-072-demonstrate-spring-report-http-security-jdbc-and-o.md -->
