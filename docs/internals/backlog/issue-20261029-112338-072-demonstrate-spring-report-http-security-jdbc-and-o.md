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

## Outcome

- The reviewed retained proxy is bound before refresh; infrastructure singleton
  definitions preserve normal Boot SQL, transaction and Security processing.
- Actual HTTP tests cover pre-body401, commit, kernel denial/rollback, postauth403,
  recovery, request-specific worker receipts and clean reused contexts.
- Readiness follows owned diagnostics. Drain refuses new work; held-cleanup tests
  distinguish caller interruption, incomplete drain, later rollback and retirement.
- Typed InactiveDomainException maps only unentered work to503; generic/entered
  failures are not relabelled BUSY. Producer `597f483649f52970597047ac51056a349a19adbb`
  first qualified that additive API, explaining its retained historical lock.

Finished-JAR/native/full checks passed; [current qualification](issue-20261029-112338-058-qualify-copied-pitch-consumers-measurements-and-ho.md).
HTTP disconnect is not worker cancellation or rollback evidence; no automatic HTTP
execution deadline is claimed. Normal serve never approves itself. Open pending
managed landing; remote-release and human-rehearsal gates remain separate.

---
<!-- id: issue-20261029-112338-072-demonstrate-spring-report-http-security-jdbc-and-o  file: issue-20261029-112338-072-demonstrate-spring-report-http-security-jdbc-and-o.md -->
