---
title: "Publish Portal bootstrap worker in the codegen Maven closure"
severity: "MEDIUM"
status: "resolved"
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
**Resolution — 2026-10-12:** Needed step1 fulfilled: ordinary worker MavenJava convention, actual published-only plugin marker/loading/generation and strict locks accepted. E=`/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-11-integration-contract-completion/`: `task-6d1/task-6d1-review.md` c049 missing-worker RED/WIP GREEN; final `task-6c-final/task-6c-final-review.md` binds P617 worker JAR/POM/module/generated classes. c049 failure not relabeled. [Acceptance](../../reviews/2026-10-12-integration-contract-completion.md).

Task6c2 actual codegen POM requires io.mazewall:worker but the worker declares no Maven publication; published-only plugin consumers fail before plugin loading. Task6d1 fixes normal build wiring without guest-host fallback.

**Needed:**
1. Add the runtime module MavenJava convention and prove real published-only marker resolution, generation and strict locks.

---
<!-- id: issue-20261012-143218-552-publish-portal-bootstrap-worker-in-the-codegen-mav  file: issue-20261012-143218-552-publish-portal-bootstrap-worker-in-the-codegen-mav.md -->
