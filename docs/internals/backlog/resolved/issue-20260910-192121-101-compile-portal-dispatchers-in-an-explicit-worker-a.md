---
title: "Compile portal dispatchers in an explicit worker artifact and validate boundary metadata"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "portal-codegen"
target_modules: ["portal-codegen", "portal-worker"]
target_files:
  - "portal-codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalCodegenPlugin.kt"
target_symbols:
  - "PortalCodegenPlugin"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Compile portal dispatchers in an explicit worker artifact and validate boundary metadata

**Context:**
Review hardening Task 7: generated dispatchers were emitted without a compiled worker artifact; Kotlin records and capability placement lacked executable compiler coverage.

**Needed:**
1. Add compiler and worker descriptor regressions, validate schema, publish worker-only artifact, run module checks.

**Resolution:** Dedicated `workerSourceSet` and `portalWorkerJar` keep generated dispatchers and service descriptors out of the host artifact. Production workers require bounded validated descriptors through their defining loader. Kotlin records use property readers; computed/delegated constructor readers and non-inbound or nested ReadFd are rejected. All generated output trees participate in atomic publication and rollback.

**Evidence:** `PortalCodegenFunctionalTest` compiles and round-trips Java records, POJOs, Kotlin data classes and `@JvmRecord`, including inherited overrides and case-distinct names; checks host/worker jars, host-only generation, invalid main selection, stale outputs and rollback. `PortalStubGeneratorTest` verifies canonical wire IDs, capability diagnostics and invalid components. `PortalGeneratedDescriptorTest` verifies missing, valid and malformed metadata. `:portal-codegen:test :portal-worker:test :portal-codegen:detektMain :portal-worker:detektMain` passed; see the canonical process-portal design and `docs/diagrams/portal_codegen.puml`/`.svg`.

---
<!-- id: issue-20260910-192121-101-compile-portal-dispatchers-in-an-explicit-worker-a  file: issue-20260910-192121-101-compile-portal-dispatchers-in-an-explicit-worker-a.md -->
