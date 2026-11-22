---
title: "Fingerprint the complete versioned Portal wire schema"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261103-095306-294-validate-complete-portal-dto-shapes-before-source-"
component: "portal-codegen"
target_modules:
  - ":portals:codegen"
  - ":portals:runtime"
  - ":portals:worker"
target_files:
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalManifestGenerator.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/ResolvedPortalContract.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalServiceSchema.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalCodegenPlugin.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalManifestGeneratorTest.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalCodegenFunctionalTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalServiceManifestTest.kt"
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/RequiredWorkerGenerationTest.kt"
  - "portals/worker/src/test/kotlin/io/mazewall/portal/worker/PortalGeneratedDescriptorTest.kt"
  - "docs/internals/designs/portal/local-service-isolation-design.md"
target_symbols:
  - "PortalManifestGenerator"
  - "ResolvedPortalContract"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Fingerprint the complete versioned Portal wire schema

**Context:**
CG03: a reproduced same-binary-name DTO int-to-long field change changes generated wire encoding but leaves schemaId identical. The current fingerprint covers only service/method IDs and top-level type names.

**Needed:**
1. Add separate-classloader fixtures with identical service/DTO binary names and different DTO member widths. Replace the current different-service-name generic test with a control that isolates generic shape.
2. Define a deterministic canonical schema from CG02's validated model: ordered methods/fields, scalar encodings, nested generic members, nullability, capability positions, snapshot limits and explicit schema/codec versions. Document which enum and declared-exception changes affect compatibility identity.
3. Resolve once per contract in the generation task and derive source and metadata from the same model; preserve existing public generator entrypoints through narrow adapters.
4. Document fingerprint migration and require regenerated host/worker artifacts to agree. Verify that existing generation-bound metadata/readiness rejects mismatches, without weakening artifact-digest or launch-policy checks.

**Scope and dependencies:**
- Follows [CG02](resolved/issue-20261103-095306-294-validate-complete-portal-dto-shapes-before-source-.md). Method IDs and valid wire bytes remain stable; correcting identity intentionally changes affected schema IDs.
- [Review CG03](../reviews/2026-11-03-portal-codegen-hardening.md#cg03--hash-the-actual-schema-with-a-defined-encoding-version) contains the reproduced identical hash. It does not claim that artifact measurement is bypassed.
- Reuse current manifest and readiness mechanisms. Any public manifest-format change beyond the existing schema-ID field needs a separately reviewed compatibility decision.

**Acceptance:**
- Same-named incompatible field widths, order, nested types and supported nullability changes produce different identities. Equivalent contracts loaded independently produce identical identities.
- Canonical output is stable across classloader/reflection iteration order and repeated builds; codec/schema version changes affect identity explicitly.
- Mixed regenerated host/worker metadata is rejected before guest dispatch. Matching artifacts retain successful startup and round trips.
- P02 diagnostics can report the same canonical schema/version used for emitted sources and metadata, without reinterpreting declarations.

**Verification:**
- [Junior implementation walkthrough: CG03](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#cg03--full-schema-identity) explains same-name classloader controls, canonicalization steps and native mismatch proof.
- Run `PortalManifestGeneratorTest`, `ResolvedPortalContractTest`, the metadata/compiled-source functional cases and relevant manifest/descriptor tests.
- Qualify mismatch rejection with the existing generated-worker/`RequiredWorkerGenerationTest` fixtures. Run `./gradlew build`, backlog and whitespace checks at implementation completion.

---
## Repository-review revalidation — 2026-11-04

[CG03/PCG-03](../reviews/2026-11-04-repository-review-findings.md#cg03) freshly reproduces
same-name int/long DTO codecs with equal schema IDs. Hash the versioned complete CG02 model;
retain equal-schema controls and actual mixed host/worker refusal. Separate artifact hashes
and new Portal JVM ABI tracking do not replace wire identity; preserve migration approval.

---
<!-- id: issue-20261103-095306-992-fingerprint-the-complete-versioned-portal-wire-sch  file: issue-20261103-095306-992-fingerprint-the-complete-versioned-portal-wire-sch.md -->
