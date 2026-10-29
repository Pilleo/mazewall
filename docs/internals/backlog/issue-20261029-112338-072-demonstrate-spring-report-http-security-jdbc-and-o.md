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

- [x] Task3 standalone HTTP/Security/JDBC application and explicit pre-traffic binding.
- [x] Task4 readiness, retained cancellation and actual owned shutdown.
- [x] Task5 request-specific safe outcomes and serial-worker transaction receipts.
- [ ] Task7 copied-artifact acceptance and final whole-change review/gates.

Pre-flight: request receipt capture occurs on the dedicated serial worker before
another invocation can overwrite lastCompletion; HTTP threads never use that global
latest report as their own receipt. External review stays separate from fixture demo
approval. Default Boot infrastructure remains active for unrelated application beans.
Approved starters are application-only; existing library/runtime versions stay fixed.

Task3 business tests observed RED for the absent method then GREEN with real H2.
Finished-JAR HTTP test observed RED at absent deployment measurement/serve, then
GREEN:401 before protected work,200/one committed row, actual denied sibling read
with rollback, reader403 after body with rollback, subsequent approver200, clean
request/security state and actual owned-worker retirement. Unrelated normal beans
still receive actual method-security/transaction advice. Exact test totals and
source-bound acceptance/final review remain completion gates.

Ruling: register preinitialized infrastructure singletons with bean definitions
because Boot's SQL dependency configurer dereferences JdbcTemplate definitions;
manual singleton registration alone failed before traffic. Keep the protected
already-frozen proxy explicitly registered/bound before refresh. This preserves
normal Boot processing rather than globally disabling it. Cost if wrong: startup
failure (caught by finished-JAR tests), never raw-target fallback.

Task4 readiness/drain regression observed RED before the operator drain command,
then GREEN: readiness503 plus refused protected admission before stop; ordinary
successful requests retain exact request-specific commit/rollback receipts.
An inactive-drain response has UNKNOWN/no body, not invented rollback evidence.
Synchronous HTTP disconnect is explicitly NOT a worker-cancellation signal.
The separate test-only finished-JAR held-report fixture observed RED at missing
behavior, then GREEN: real report read/SQL begins under the same request/Security
bridge; virtual caller interruption returns while actual cleanup remains held;
bounded stop retains Incomplete and genuine resources; eventual release yields
actual SQL rollback, correlated completion and canonical physical retirement. The
old Incomplete snapshot stays incomplete. No held-cleanup hook enters normal
ReportService/application.jar.5 host and3 native cases execute with zero skips.
A further readiness-state regression observed RED then GREEN: readiness/admission
sample actual owned diagnostics, not just SmartLifecycle's READY flag, so a failed
or retired underlying generation cannot remain ready. Preparation/close budgets
remain ten seconds; synchronous HTTP has no automatic execution deadline and
client timeout/disconnect never proves rollback or physical termination.

Task5 Spring public failures use bounded categories/server request IDs without
paths/payloads, retaining original local causes. Single-worker bridge captures
new transaction completion before release and checks both metadata and Security
restoration; no HTTP-thread global lastCompletion correlation is used.

---
<!-- id: issue-20261029-112338-072-demonstrate-spring-report-http-security-jdbc-and-o  file: issue-20261029-112338-072-demonstrate-spring-report-http-security-jdbc-and-o.md -->
