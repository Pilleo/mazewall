---
title: "Keep host verification aggregates from realizing optional native tasks"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "build.gradle.kts"
  - "buildSrc/src/main/kotlin/mazewall.quality-conventions.gradle.kts"
  - "buildSrc/src/test/java/io/mazewall/build/AggregateVerificationFunctionalTest.java"
target_symbols:
  - "unitCheck"
open_questions: false
---

# 🟡 [Severity: HIGH]: Keep host verification aggregates from realizing optional native tasks

**Context:**
PR531 CI run38721461180 fails while determining unitCheck dependencies: TaskCollection.matching realizes profiler:tierENativeBuild, whose configuration resolves a Docker image probe. The clean host lacks that optional image. Host task selection must filter registered names without realizing unrelated native tasks; required host gates must remain selected and failing checks must still fail.

**Needed:**
1. Add a real aggregate TestKit regression with an unselected task whose configuration fails, then replace name-only eager filtering with lazy name filtering.

## Resolution

The real aggregate fixture registers an optional native task whose configuration
throws. The original gate fails before executing required host tasks
(`run-0dc3ff55893c`). Passing a name-filtered task collection directly to
`dependsOn` still realizes that task (`run-37347a7726f9`), so the fix resolves
registered names into individual task providers inside a deferred dependency
provider. The same selection applies to grouping/kernel aggregations and the
quality convention; required gates and explicit native failures are preserved.

The mandatory aggregate tests pass with configuration-cache reuse and explicitly
selected native failures (`run-173b2f4fd234`). The real checkout's host graph also
passes with `-PtierEDockerCli=/unavailable/ci-no-docker` (`run-4c3c0bc51c93`). No
Docker failure is ignored, downgraded or bypassed when native acquisition is selected.

The full included-module build passes with this correction (`run-bd93ef1797a4`,
832,953 ms). CI must confirm the clean-runner gate at the published follow-up SHA.

---
<!-- id: issue-20261015-104807-764-keep-host-verification-aggregates-from-realizing-o  file: issue-20261015-104807-764-keep-host-verification-aggregates-from-realizing-o.md -->
