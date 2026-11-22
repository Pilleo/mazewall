---
title: "Generate typed Portal operations with bounded submit-time snapshots"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261103-090025-581-simplify-portal-registration-and-single-project-wo"
  - "issue-20261103-095307-699-reject-noncanonical-and-incompletely-consumed-gene"
  - "issue-20261103-095308-453-keep-portal-worker-checkout-owned-through-generate"
  - "issue-20261103-095309-136-bound-generated-portal-encoding-before-allocation-"
component: "portal-runtime"
target_modules:
  - ":portals:runtime"
  - ":portals:codegen"
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntime.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntimeConfig.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalServiceManifest.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalGeneratedNames.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalGeneratedNamesTest.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalOperation.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalSubmissionLimits.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRequestWriter.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalCallAdmission.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalOperationOwner.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/OwnedPortalInvocation.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalInvocationLifecycle.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalInvocationRetirement.kt"
  - "portals/runtime/src/main/java/io/mazewall/portal/PreparedPortalOperation.java"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalOperationGenerator.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalServiceSchema.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalCodegenPlugin.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalSubmissionSnapshotTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalSubmissionBudgetTest.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalOperationGeneratorTest.kt"
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/PreparedPortalOperationIntegrationTest.kt"
  - "tests/consumers/portal-plugin"
  - "docs/guides/portal-service-isolation.md"
target_symbols:
  - "PortalRuntime"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Generate typed Portal operations with bounded submit-time snapshots

**Context:**
Portal developer-experience plan P03: make a generated operation bind directly to the existing broker-owned PreparedPortalOperation and InvocationHandle, with copy-at-submit value semantics, finite retained-byte accounting and no handwritten host mapping.

**Needed:**
1. Generate typed operation descriptors and zero/multiple-argument values from the resolved schema; bind them directly to the exact runtime-owned broker without host guest construction or another executor.
2. Extend existing admission with a finite retained-request byte budget and snapshot-in-progress ownership. Encode copied values before submit returns, outside lifecycle locks, with one deadline and bounded allocation.
3. Preserve existing stubs, IDs, prepare semantics and default bridges. Add duration-bounded submission, Java-boxed selectors and diagnostics for contracts whose new async binding is not yet supported.

**Plan and dependencies:**
- [Implementation plan P03](../../superpowers/plans/2026-11-03-portal-developer-experience.md) and [snapshot/admission contract](../designs/portal/portal-developer-experience.md#4-submit-time-copying-deadlines-and-admission).
- Follows P02's packaged consumers. Direct inbound FDs are qualified by P08; existing annotated stream adapters retain their synchronous path.
- Reuse CG04/CG05/CG06 from the [codegen hardening review](../reviews/2026-11-03-portal-codegen-hardening.md): strict decoding, validation-owned checkout and the shared bounded writer. P03 owns submit-time admission/ownership, not a competing encoder.

**Acceptance:**
- Caller mutation after successful submit cannot change queued bytes, including nested supported values. Concurrent mutation during submit remains unsupported and documented.
- Reserve count plus worst-case live encoded/scratch/copy capacity before encoding; shrink to retained capacity without under-accounting. Full budgets reject NOT_STARTED before getter/guest entry.
- An admitted blocking getter remains owned across cancellation/close. Finite close reports incomplete cleanup; releasing the getter permits cleanup but never late guest dispatch. No early execution-end or byte/count release.
- Encoding failure, queued/active cancellation, close and transport failure release owned reservations exactly once after their actual users/retirement end.
- Validate descriptor identity, declared exceptions, overloads, generics, nullable values, nested/colliding generated names and malformed lengths. Golden wire round trips match existing IDs/bytes; Java and Kotlin consumers work without reflection into guest classes.
- New artifacts have explicit host/worker/metadata roles; publication does not infer their role from a class-name suffix.
- Guidance and fixtures distinguish bounded library request buffers from caller inputs, host DTO code and retained decoded results. Consume results through a finite window; completion may leave a large result reachable from the caller's future after admission is released.

**Verification:**
- [Junior implementation walkthrough: P03](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#p03--generated-submissions-and-byte-ownership) gives deterministic mutation/blocked-encoder tests, reservation ownership paths and focused commands.
- Run focused `PortalSubmission*Test`, `PortalOperationGeneratorTest`, existing prepared-operation lifecycle/visibility tests and a generated packaged-consumer native round trip.
- Run `./gradlew build`, backlog validation and whitespace checks. Require real worker identity, mutation isolation and physical-retirement evidence before closure.

## Repository review acceptance links — 2026-11-04 (P03)

P03 must consume the shared helper corrections owned by
[PRT-05](resolved/issue-20261103-235254-202-dispatch-legal-object-name-portal-service-overload.md)
and [PCG-10](resolved/issue-20261103-235254-619-preserve-fatal-failures-during-portal-declared-exc.md):
legal Object-name overloads dispatch to the guest, while declared-exception constructor
Errors retain exact fatal identity through generated/stateful paths. Require real
generated Java/Kotlin call and dispatch/non-reuse controls alongside existing CG04–06
acceptance. Preserve original Object-method behavior and default bridges; coordinate
with [ARCH-02](issue-20261103-235258-789-guard-supported-portal-binary-compatibility-with-t.md)
for unchanged compiled consumers. These are acceptance prerequisites, not duplicate
helper implementations or permission to widen P03.

---
<!-- id: issue-20261103-090025-757-generate-typed-portal-operations-with-bounded-subm  file: issue-20261103-090025-757-generate-typed-portal-operations-with-bounded-subm.md -->
