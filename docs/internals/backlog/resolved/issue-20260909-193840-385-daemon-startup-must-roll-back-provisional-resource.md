---
title: "Daemon startup must roll back provisional resources and retire dead owners"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: ["platform", "enforcer", "profiler"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/ChildStartupTransaction.kt"
target_symbols:
  - "ChildStartupTransaction"
open_questions: false
---

# 🟡 [Severity: HIGH]: Daemon startup must roll back provisional resources and retire dead owners

**Context:**
Supervisor and profiler startup failures leak endpoints children pumps or hooks; profiler replacement loses dead daemon ownership.

**Needed:**
1. Commit a shared resource owner only after readiness and fail closed on ptracer authorization.

**Resolution (2026-09-09):** Both managers acquire through ChildStartupTransaction and retain
ChildProcessResources only after live readiness. Launch/ptracer/hook/readiness failure rolls
back all acquired resources. ResourceRetirement attempts independent releases and propagates
fatal errors first. Failed retirement remains owned/retryable; no replacement skips a dead
profiler's pending cleanup. PrivateUnixEndpoint uses its creating launcher and retires invalid
fallback candidates. The supervisor output owner observes process exit even when EOF comes first.

**Evidence:** RED→GREEN launch/fallback/interrupt/retirement/fatal-precedence/EOF regressions;
both-manager failure matrix and failed dead-profiler replacement retry. Final :platform:test:
319 tests, no failures/skips; :enforcer:test: 794 tests, no failures, 2 existing skips;
:profiler:test: 526 tests, no failures/skips. Fresh-JVM PermanentSupervisorRegistration and
SupervisorProxy: 3 tests, no failures/skips; ProfilerIntegrationTest: 12 tests, no failures/skips.
All three detektMain tasks passed; enforcer/profiler diagrams and startup migration docs updated.

---
<!-- id: issue-20260909-193840-385-daemon-startup-must-roll-back-provisional-resource  file: issue-20260909-193840-385-daemon-startup-must-roll-back-provisional-resource.md -->
