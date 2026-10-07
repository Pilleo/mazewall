---
title: "Own prepared Portal invocation cancellation through physical retirement"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["portals:runtime"]
target_files: 
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntime.kt"
target_symbols: 
  - "PortalRuntime"
open_questions: false
---

# 🟡 [Severity: HIGH]: Own prepared Portal invocation cancellation through physical retirement

**Context:**
Approved P2 requires public PreparedPortalOperation.submit and minimal InvocationHandle stages. Cancellation must retain finite admission and physical execution ownership through exact failed-worker reaping and cleanup retry.

**Needed:**
1. Add bounded broker-owned host dispatch, public typed operation preparation, and exact worker retirement tracking; verify queued and in-flight cancellation.

**Implementation evidence (pending repository review):**
- Public `PortalRuntime.prepare` selects a service/broker and returns a minimal shared `InvocationHandle` from `PreparedPortalOperation.submit`. Host mapping is explicitly outside child protection, rejects broker substitution/local fallback, and has a finite JDK host dispatch owner.
- Synchronous and prepared operations share admission. The submit deadline survives host queueing and broker checkout/transport. Queued cancellation never enters mapping; running cancellation retains the exact worker retirement future and physical lease until reaping and cleanup retry complete.
- Pure invocation transitions never let cancellation erase running/retiring state. Runtime close stops dispatch, retires process owners and joins dispatch; self-close rejects. Converted futures cannot forge physical end.
- Missing API baseline recorded (`run-afcbe969c562`). Focused full runtime host/quality and real public configuration/queued cancellation/request-progress cancellation/failed socket cleanup retention passed (`run-cb0dfbf279cb`). Public fixture uses typed test stubs for real builtins; generated consumer/required readiness remains P3–P5 proof.
- `./gradlew build` passed in 1m36s (`run-747f93371827`); backlog validation passed (`run-b670848185df`). No warning inventory expansion. Enforcer dependency is API because its invocation handle types are now publicly exposed.

---
<!-- id: issue-20261007-211004-888-own-prepared-portal-invocation-cancellation-throug  file: issue-20261007-211004-888-own-prepared-portal-invocation-cancellation-throug.md -->
