---
title: "Validate complete Portal DTO shapes before source emission"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261103-095305-625-make-accepted-portal-byte-array-and-list-contracts"
component: "portal-codegen"
target_modules:
  - ":portals:codegen"
target_files:
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/BoundaryTypes.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalServiceSchema.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/ResolvedPortalContract.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/ResolvedPortalContractTest.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalStubGeneratorTest.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalCodegenFunctionalTest.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/testapi/SampleServices.kt"
  - "docs/internals/designs/portal/local-service-isolation-design.md"
  - "docs/guides/portal-service-isolation.md"
target_symbols:
  - "BoundaryTypes"
  - "PortalServiceSchema"
  - "ResolvedPortalContract"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Validate complete Portal DTO shapes before source emission

**Context:**
CG02: current resolution accepts abstract DTOs and nullable Kotlin DTO fields that emit uncompilable constructors/encode calls; nested DTO generic components are inspected through erased Class types. Represent supported value, capability and result positions explicitly and reject unsupported shapes with a field path.

**Needed:**
1. Reproduce abstract DTO and nullable Kotlin field acceptance with compiled fixtures. Add negative controls for inaccessible enclosing classes/constructors/accessors and unsupported recursive or unresolved generic shapes.
2. Resolve complete member generic types and nullability, including record components, constructor parameters and readable accessors. Validate that reconstruction and reading are both legal before emission.
3. Extend the existing sealed model with position-safe alternatives for copied values, inbound capabilities/snapshots and results. Keep Unit result-only and make returned/nested capabilities unrepresentable in a validated copied-value shape. Own immutable collections and restrict model construction.
4. Drive source declarations and codecs from that model. Reject unsupported nullable/nested shapes with contract/method/parameter-or-result/field paths; retain enough metadata for CG03 fingerprints and P02 diagnostics.

**Scope and dependencies:**
- Follows [CG01](resolved/issue-20261103-095305-625-make-accepted-portal-byte-array-and-list-contracts.md)'s compiler-backed type agreement.
- [Review CG02](../reviews/2026-11-03-portal-codegen-hardening.md#cg02--resolve-a-complete-position-aware-dto-model) distinguishes reproduced acceptance defects from the erased nested-generic rejection.
- Reuse the current resolver and public entrypoints. This issue tightens validation; new nullable wire representations or arbitrary polymorphic serialization are not required.
- Runtime DTO constructors/getters remain trusted application code. Shape validation must not execute them or claim to prove them pure. P02 owns the user-facing diagnostic command.

**Acceptance:**
- Unsupported abstract, inaccessible, nullable or unresolved member shapes fail during resolution with a precise field path, before publishing any output.
- Supported nested generic metadata survives resolution. A shape is either compiled and round-tripped or explicitly rejected; it never reaches emission as an erased guess.
- Invalid value/capability/result combinations cannot be constructed through the validated model. Existing supported nonnullable DTOs and capability placement remain compatible.
- Failed resolution preserves the previous complete generated output and does not initialize guest implementations.

**Verification:**
- [Junior implementation walkthrough: CG02](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#cg02--complete-validated-dto-shapes) gives the accepted/rejected fixture sequence, model obligations and commands.
- Run `ResolvedPortalContractTest`, `PortalStubGeneratorTest` and compiler-backed positive/negative `PortalCodegenFunctionalTest` cases, then `./gradlew :portals:codegen:test`.
- Run `./gradlew build`, backlog and whitespace checks at implementation completion; document the accepted/rejected shape matrix.

---
## Repository-review revalidation — 2026-11-04

[CG02/PCG-02](../reviews/2026-11-04-repository-review-findings.md#cg02) freshly confirms
accepted abstract/nullable DTO compilation failures and erased generic-member rejection.
Complete the existing position-aware validated model, including access/constructor/capability
placement; accepted fixtures must compile and round-trip, rejected ones fail before publication.
Do not infer trusted getter/constructor purity. CG03 consumes this same model.

---
<!-- id: issue-20261103-095306-294-validate-complete-portal-dto-shapes-before-source-  file: issue-20261103-095306-294-validate-complete-portal-dto-shapes-before-source-.md -->
