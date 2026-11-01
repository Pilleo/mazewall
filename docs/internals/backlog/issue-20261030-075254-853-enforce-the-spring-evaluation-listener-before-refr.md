---
title: "Enforce the Spring evaluation listener before refresh"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
  - "demos/spring-report-api/src/main/java/demo/report/ReportApplication.java"
target_symbols:
  - "requireEvaluationListener"
open_questions: false
---

# 🟡 [Severity: HIGH]: Enforce the Spring evaluation listener before refresh

**Context:**
Approved inline follow-up Task1. Spring defaults can be overridden by system/environment configuration, violating exact127.0.0.1/CLI-port evaluation binding. Reject primary/management listener drift before registration/refresh without disabling normal Boot processing. No dependencies, policy widening, worktrees or plan files.

**Needed:**
1. Write safe finished-JAR override regressions RED, host effective-environment matrix, minimal pre-refresh guard, exact copied-report counts and docs; verify10host/8native (including management-zero review regression) then final source-bound/full gates.

## Outcome

Effective configuration is validated before registration/refresh. The address must
be127.0.0.1 and the main port must match the CLI. Management port0 creates a second
Boot listener and is rejected; only absence or a matching nonzero port is allowed.
Upstream rejection of redundant shared-port management addresses remains intact.

Host and actual finished-JAR override/management-zero regressions passed. Native
tests use safe loopback overrides, never public binds; ordinary Boot processing
and existing budgets remain unchanged. See [current qualification](issue-20261029-112338-058-qualify-copied-pitch-consumers-measurements-and-ho.md).
Open pending managed landing; no listener widening or fallback was introduced.

---
<!-- id: issue-20261030-075254-853-enforce-the-spring-evaluation-listener-before-refr  file: issue-20261030-075254-853-enforce-the-spring-evaluation-listener-before-refr.md -->
