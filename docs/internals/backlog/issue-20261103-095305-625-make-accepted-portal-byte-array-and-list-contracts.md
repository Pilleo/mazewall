---
title: "Make accepted Portal byte-array and list contracts generate compilable Kotlin"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "portal-codegen"
target_modules:
  - ":portals:codegen"
target_files:
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalStubGeneratorTest.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalCodegenFunctionalTest.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/testapi/SampleServices.kt"
  - "docs/guides/portal-service-isolation.md"
target_symbols:
  - "PortalStubGenerator"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make accepted Portal byte-array and list contracts generate compilable Kotlin

**Context:**
CG01: compiled review probes show byte[] signatures become Array<Byte> although the codec uses ByteArray, and List results are declared MutableList while their decoder returns List. Existing string-based tests pass despite invalid generated Kotlin.

**Needed:**
1. Add failing generated-source compilation fixtures for Java `byte[]` parameters/results and `List<Integer>` results. Include Kotlin `ByteArray`, read-only/mutable list signatures, inherited generic contracts and nested supported collection values.
2. Align emitted declaration types with the accepted contract and decoder expressions. Map primitive byte arrays explicitly; choose collection types that both override the contract and match actual decoder results.
3. Compile and execute both generated host and dispatcher artifacts through the existing functional fixture. Keep source-text assertions only for details that cannot be observed through compilation/round trips.

**Scope and dependencies:**
- Independent prerequisite for the model/codec tasks; no wire-format, method-ID or public generator signature change.
- [Review CG01](../reviews/2026-11-03-portal-codegen-hardening.md#cg01--make-accepted-types-actually-compile) records actual compiler failures, not an inferred KotlinPoet behavior.
- Preserve the existing user-owned edits in `PortalCodegenFunctionalTest.kt`; extend its fixture rather than replacing it.

**Acceptance:**
- Every positive fixture passes contract resolution, compiles unmodified generated sources and round-trips representative empty/nonempty values.
- Primitive byte-array signatures use `ByteArray`, and generated list results satisfy the exact Java/Kotlin override contract.
- Existing scalar/DTO/map/overload fixtures remain green; valid encoded bytes and stable method IDs do not change.

**Verification:**
- [Junior implementation walkthrough: CG01](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#cg01--compilable-boundary-types) gives the concrete compiler fixture, RED/GREEN sequence and proof limits.
- Run `PortalStubGeneratorTest` and the affected `PortalCodegenFunctionalTest` compile/round-trip cases, then `./gradlew :portals:codegen:test`.
- At implementation completion run `./gradlew build`, `./scripts/adkw check-backlog` and `git diff --check`. Record compiled-source evidence before closing this issue.

---
## Repository-review revalidation — 2026-11-04

[CG01/PCG-01](../reviews/2026-11-04-repository-review-findings.md#cg01) freshly reproduced
unmodified emitted byte-array/list compilation failures. Fix declarations and codecs once;
P01 byte-array benchmarks and downstream CG/P tasks must consume real generated output rather
than manual repairs. Compile/round-trip both Java and Kotlin contract variants before closure.

---
<!-- id: issue-20261103-095305-625-make-accepted-portal-byte-array-and-list-contracts  file: issue-20261103-095305-625-make-accepted-portal-byte-array-and-list-contracts.md -->
