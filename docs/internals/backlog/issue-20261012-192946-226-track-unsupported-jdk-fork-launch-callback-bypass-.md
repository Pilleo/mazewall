---
title: "Track unsupported JDK FORK launch callback bypass in supervised agent demo"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "demos/agent-sandbox-demo"
target_symbols:
  - "StacktraceScopingPolicy"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Track unsupported JDK FORK launch callback bypass in supervised agent demo

**Context:**
Task4a actual JDK25 FORK-mode probe allowed the malicious web-stack shell without a custom spawn verdict. Possible CLONE provenance is untraced; only early VFORK bootstrap is supported.

**Needed:**
1. Reproduce the exact FORK callback gap with actual syscall provenance and supported launch controls; determine a separately reviewed fail-closed supported scope without broad CLONE supervision, permission unions or JVM coordination regressions.

---
<!-- id: issue-20261012-192946-226-track-unsupported-jdk-fork-launch-callback-bypass-  file: issue-20261012-192946-226-track-unsupported-jdk-fork-launch-callback-bypass-.md -->
