---
title: "Preserve active filesystem intent and immutable policy snapshots"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/PolicyBuilder.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve active filesystem intent and immutable policy snapshots

**Context:**
An active empty Landlock intersection becomes unrestricted when reconstructed through PolicyBuilder.base because build derives enforcement only from nonempty path sets. Built and composed definitions also expose mutable maps and sets, allowing callers to change effective permissions after snapshot creation.

**Needed:**
1. Preserve active filesystem intent through template and scope-copy construction; freeze permission collections; verify reconstruction, mutation rejection, and native deny-all behaviour.

---
<!-- id: issue-20261006-021323-433-preserve-active-filesystem-intent-and-immutable-po  file: issue-20261006-021323-433-preserve-active-filesystem-intent-and-immutable-po.md -->
**Execution evidence:**

- `PolicyIntentContractTest` first ran with five tests and four failures: template reconstruction lost active Landlock state, and built/composed permission collections could be mutated.
- Builder state now preserves active restriction through `base()` and scope snapshots. Builder and intersection outputs freeze permission and diagnostic collections.
- `./gradlew :enforcer:test --tests '*PolicyIntentContractTest*'` passed after the changes.
- A fresh full `./gradlew :enforcer:test` initially exposed the independent synchronization gate prerequisite; after its separately scoped correction, the full enforcer suite passed.
- `./gradlew :enforcer:integrationTest --tests '*PolicyIntentNativeTest*'` passed with one test, zero skipped, zero failures. The isolated child proves caller access succeeds, the reconstructed deny-all worker's fresh open is denied, and caller access still succeeds afterward.
- Native layouts, compiler scan design, and JVM coordination exceptions were unchanged.
- The issue remains open pending repository review/resolution.
