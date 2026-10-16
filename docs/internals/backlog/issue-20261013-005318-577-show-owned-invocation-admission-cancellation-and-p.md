---
title: "Show owned invocation admission cancellation and physical drain"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "demos"
target_modules: []
target_files:
  - "demos/cli-demo"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Show owned invocation admission cancellation and physical drain

**Context:**
ManagedRuntime exposes explicit context, finite admission, invocation handles, executionEnd and bounded drain. They are important project features; existing tests already cover held cleanup and worker reuse, but the behavior is not presented as a concise independently runnable feature test.

**Needed:**
1. Curate a presentation-tagged JUnit lifecycle test using existing owned-runtime fixtures: virtual caller/platform worker, declared context capture/restoration, maxAdmissions rejection and held cleanup. Assert and visibly distinguish caller cancellation from live execution, then release, await physical end and Complete drain. No additional CLI mode or production demonstration abstraction.

**Acceptance:** A held first body exhausts the one-admission domain and a second submit rejects with zero body entry. The caller is actually virtual and the acquired protected worker actually platform. Cancelled delivery while cleanup is held yields Incomplete drain; explicit release yields executionEnd, complete drain and observed worker termination. A reused healthy worker restores declared context. No timing-only sleeps or carrier-thread filter installation establish the result.

---
<!-- id: issue-20261013-005318-577-show-owned-invocation-admission-cancellation-and-p  file: issue-20261013-005318-577-show-owned-invocation-admission-cancellation-and-p.md -->
