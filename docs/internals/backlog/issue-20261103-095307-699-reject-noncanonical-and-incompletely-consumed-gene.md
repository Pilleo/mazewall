---
title: "Reject noncanonical and incompletely consumed generated Portal payloads"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261103-095305-625-make-accepted-portal-byte-array-and-list-contracts"
component: "portal-codegen"
target_modules:
  - ":portals:codegen"
  - ":portals:runtime"
  - ":portals:worker"
target_files:
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalCodec.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalCallException.kt"
  - "portals/worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerDriver.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalStubGeneratorTest.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalCodegenFunctionalTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalCodecTest.kt"
  - "portals/worker/src/test/kotlin/io/mazewall/portal/worker/PortalWorkerDriverTest.kt"
  - "docs/internals/designs/portal/local-service-isolation-design.md"
target_symbols:
  - "PortalStubGenerator"
  - "PortalCodec"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject noncanonical and incompletely consumed generated Portal payloads

**Context:**
CG04: compiled generated dispatchers invoke service code for trailing bytes, nonempty zero-argument payloads, Boolean byte 2, malformed UTF-8 and duplicate map keys. Host result decoding also omits complete-consumption checks.

**Needed:**
1. Turn the review's compiled-dispatcher probes into regression cases with a service-entry counter. Exercise trailing/truncated payloads, empty shapes, invalid Boolean bytes, malformed UTF-8 and duplicate map keys in requests and responses.
2. Require complete payload consumption before worker method entry and before host result delivery, including zero-argument calls and Unit replies. Require exact capability counts/placement and retain ownership of rejected descriptors through cleanup.
3. Reject noncanonical Boolean and UTF-8 encodings and duplicate decoded map keys. Keep existing length/count checks and apply validation recursively to supported nested values.
4. Give malformed-wire failures a distinguishable classification and field/method context without payload logging. Do not misclassify a completed service exception or trusted DTO-constructor exception as wire corruption.

**Scope and dependencies:**
- Follows [CG01](issue-20261103-095305-625-make-accepted-portal-byte-array-and-list-contracts.md); [review CG04](../reviews/2026-11-03-portal-codegen-hardening.md#cg04--reject-malformed-data-before-accepting-a-generated-call) records executed malformed-input probes.
- This issue owns parsing and error classification. [CG05](issue-20261103-095308-453-keep-portal-worker-checkout-owned-through-generate.md) owns retaining/retiring the broker checkout around response validation.
- Valid wire bytes and completed business-exception behavior stay compatible; previously accepted malformed encodings become explicit failures.

**Acceptance:**
- Each malformed request is rejected before service entry, with a zero entry count; each malformed reply is rejected before returning a value.
- Empty request/result shapes consume exactly zero bytes. Missing/extra capability cases close only owned resources exactly once.
- Boolean bytes other than 0/1, invalid UTF-8 and duplicate decoded keys fail deterministically. Valid boundary values and declared service errors still round-trip.
- Tests execute unmodified compiled generated sources; substring assertions alone cannot satisfy the issue.

**Verification:**
- [Junior implementation walkthrough: CG04](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#cg04--strict-decoding) supplies the malformed-payload matrix, entry-count expectations and error-classification controls.
- Run `PortalCodecTest`, generated compile/dispatch functional cases and `PortalWorkerDriverTest`, then the three affected module test suites.
- Run `./gradlew build`, backlog and whitespace checks at implementation completion. Checkout-reuse/state-loss evidence belongs to CG05.

---
## Repository-review revalidation — 2026-11-04

[CG04/PCG-04](../reviews/2026-11-04-repository-review-findings.md#cg04) freshly reaches
service entry with malformed Boolean/UTF-8, duplicate map keys, trailing bytes and nonempty
zero-argument bodies. Cover full body/capability consumption on request and result, including
Unit and extra direct capabilities. Use distinct malformed-wire classification; trusted host
constructor failures are not automatically worker corruption. CG05 retains the exact checkout.

---
<!-- id: issue-20261103-095307-699-reject-noncanonical-and-incompletely-consumed-gene  file: issue-20261103-095307-699-reject-noncanonical-and-incompletely-consumed-gene.md -->
