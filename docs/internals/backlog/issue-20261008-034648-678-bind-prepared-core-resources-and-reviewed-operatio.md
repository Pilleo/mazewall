---
title: "Bind prepared core resources and reviewed operation to actual filesystem objects"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [enforcer]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
target_symbols:
  - "ManagedRuntime"
open_questions: true
---

# 🟡 [Severity: HIGH]: Bind prepared core resources and reviewed operation to actual filesystem objects

**Context:**
C4/C6 preparation currently validates configured hashes without retaining actual resource and implementation object identity through worker installation and retirement

**Needed:**
1. Pin genuine descriptors before worker setup, reject stale resources and explicit recipe contradictions, verify actual selected implementation bytes

## Implemented task-5 slice

ManagedRuntime filesystem preparation resolves all explicit/runtime objects before
worker setup, holds genuine captured-engine descriptors and one shared ruleset, rejects
missing leaves without parent widening, revalidates on the manager and retires only
after physical workers end. Additive PreparedOperationResources resolves an actual
ordinary finished JAR and selected Class/classloader; exact external review, canonical
policy, resource generation, JVM member declaring artifact and required ABI are checked
before admission. Reviewed adapter entry points remain intact. Explicit strict read-only
recipes reject writable overlaps/hardlink aliases; future output uses existing directories.
Existing ResourceRetirement/CleanupFailures preserve fatal-first rollback/cleanup.

Focused host/ABI/architecture/Detekt and disposable native fixtures are recorded in
the fleet task-5 report. See designs/enforcer/prepared-resources-design.md for exact
rights and ownership. This issue stays open for coordinator review and remaining gates.

## Unclosed acceptance

- Nested/exploded/versioned implementation packaging and custom loader attestation.
- Actual already-defined bytecode attestation under transformation/redefinition and
  physical content immutability against concurrent sibling writes; O_PATH does not
  provide this. Trusted immutable deployment remains necessary.
- Strict read-only directory plus writable grants rejects pending alias-exclusion support;
  directory identity is not immutable descendant content. Preexisting mount aliases and
  topology remain deployment trust assumptions.
- The unchanged configured-review overload cannot be relabeled actual implementation
  proof. Framework adapters must adopt the additive owner under coordinator contract.
- Combined packaged/native/full-build/container gates remain coordinator-owned.

Coordinator-owned backlog registration: add one open HIGH enforcer issue row for
issue-20261008-034648-678-bind-prepared-core-resources-and-reviewed-operatio; open count +1.

---
<!-- id: issue-20261008-034648-678-bind-prepared-core-resources-and-reviewed-operatio  file: issue-20261008-034648-678-bind-prepared-core-resources-and-reviewed-operatio.md -->
