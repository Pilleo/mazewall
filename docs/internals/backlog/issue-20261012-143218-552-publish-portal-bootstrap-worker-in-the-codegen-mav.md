---
title: "Publish Portal bootstrap worker in the codegen Maven closure"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "portals/worker/build.gradle.kts"
target_symbols:
  - "mavenJava"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Publish Portal bootstrap worker in the codegen Maven closure

**Context:**
Task6c2 actual codegen POM requires io.mazewall:worker but the worker declares no Maven publication; published-only plugin consumers fail before plugin loading. Task6d1 fixes normal build wiring without guest-host fallback.

**Needed:**
1. Add the runtime module MavenJava convention and prove real published-only marker resolution, generation and strict locks.

---
<!-- id: issue-20261012-143218-552-publish-portal-bootstrap-worker-in-the-codegen-mav  file: issue-20261012-143218-552-publish-portal-bootstrap-worker-in-the-codegen-mav.md -->
