---
title: "Make vulnerable web demo lifecycle and evidence authoritative"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "demos"
target_modules: ["demos:vulnerable-web-app"]
target_files:
  - "demos/vulnerable-web-app/src/main/kotlin/demo/vulnapp/VulnAppApplication.kt"
  - "demos/vulnerable-web-app/src/main/kotlin/demo/vulnapp/config/MazewallProtectionDecision.kt"
  - "demos/vulnerable-web-app/src/main/kotlin/demo/vulnapp/config/ServiceExecutorOwner.kt"
  - "demos/vulnerable-web-app/src/main/kotlin/demo/vulnapp/config/MazewallProfileManager.kt"
  - "demos/vulnerable-web-app/compose.yml"
target_symbols: ["MazewallProtectionBootstrap", "ServiceExecutorOwner", "MazewallProfileManager"]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make vulnerable web demo lifecycle and evidence authoritative

**Context:**
The packaged application read its protection switch directly before Spring resolved configuration,
while bean selection read a different property path. Profiling configuration existed only under
test sources, service executors duplicated lifecycle code, shutdown compiled evidence before
stopping work, and file publication was non-atomic. Compose also exposed deliberately vulnerable
ports on every host interface.

**Resolution (2026-09-10):**
Spring's prepared environment now resolves protection once before application workers exist; the
same published property selects protected or unprotected beans. Profiling configuration ships in
the application and uses a shared executor owner that stops admission and drains all accepted work
against one deadline. Profiler sessions close before an immutable snapshot is atomically published,
with a coherent immutable generation containing SBoB, stack dictionary, and digest-backed
integrity state. One atomic pointer publishes only complete generations, while combined failure
outcomes retain timeout/interruption and cleanup failures together. Compose separates protected,
unprotected, and profiling modes; both the packaged application default and compose bind to
loopback. Fresh-JVM tests start the real Spring server and exercise bootstrap, conditional beans,
an existing benign HTTP route, protected/unprotected service wiring, shutdown publication, owned
fixtures, and non-empty evidence. They also prove no command-execution HTTP route is exposed.

Evidence: focused and full `:demos:vulnerable-web-app:test` verification, including three
isolated-JVM Spring application scenarios, is recorded in the Task 12 implementation report; the
canonical lifecycle PlantUML/SVG was regenerated from authoritative code order.

---
<!-- id: issue-20260910-020139-754-make-vulnerable-web-demo-lifecycle-and-evidence-au  file: issue-20260910-020139-754-make-vulnerable-web-demo-lifecycle-and-evidence-au.md -->
