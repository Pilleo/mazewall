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

## Round 1/5 review corrections

S1 excludes custom system loaders by requiring the exact bootstrap-defined ordinary
OpenJDK AppClassLoader category/system identity or exact ordinary URLClassLoader.
S2 freezes strict paths once and derives both captured recipes and ABI guarding from
that snapshot. Q1 retains one manager-started physical cleanup owner per filesystem
generation; caller drain only joins within its deadline, never waits synchronously on
validation monitors or native release. Prepared cleanup retains the full hierarchy
through validation and actual worker end, and reports exact fatal/error outcomes.
Named real-loader/real-descriptor host and disposable native deadline/fault regressions
are recorded in the appended task report. Additive Spring seam signatures remain stable.

Round2/5 R1 corrects asynchronous failure/retirement observation ordering. An immutable
atomic terminal release outcome retains exact failure; manager and worker/cleanup
physical completion precede failure snapshots. Pure drain lifecycle never combines an
earlier null with later completion to publish CLOSED. Actual-owner ordinary/fatal late
completion barriers reproduce the original false first/later Complete and successful
close, then verify failure persistence after correction. No additional packaging scope.

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
