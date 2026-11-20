---
title: "Decouple core release qualification from optional integration releases"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "ci"
target_modules: []
target_files:
  - ".github/workflows/ci.yml"
target_symbols: []
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Decouple core release qualification from optional integration releases

**Context:**
Architecture assessment finding 5b: ci.yml:97-110 makes optional-integrations a prerequisite of core build/publication; integrations/koog/README.md:70-74 confirms this delivery coupling. Acceptance: an approved artifact-to-gate matrix retains every core native, static-analysis and packaged-consumer requirement, requires integration acceptance for integration publication, and demonstrates that an unrelated optional-adapter failure cannot publish that adapter or unnecessarily block an independently qualified core artifact. Shared contract changes must still select affected integration checks. This is a release-graph design task, not permission to waive current gates or publish.

**Needed:**
1. Map published artifacts to required qualification gates and propose an independently versioned release graph with negative controls for shared-contract changes and failed adapter publication.

## Repository review addendum — 2026-11-04 (ARCH-04 / BUILD-PROVENANCE)

[Finding and acceptance](../reviews/2026-11-04-repository-review-findings.md#arch-04).
The earlier context is partially superseded: core-quality now validates independently
(`.github/workflows/ci.yml:123–145`). The required aggregate and broad publication
still join optional integrations and Go acceptance (`:886–940`). This is release-graph
debt, not evidence the current fail-closed aggregate is unsafe.

Approve an exact artifact-to-gate matrix before selecting closure-specific publication
tasks. A common version with independent promotion status is sufficient initially;
independent version schemes are not a prerequisite. Preserve present all-artifact
requirements until the decision. Negative controls must show optional-only failure
blocks its artifact, shared-contract changes select affected consumers, and failed or
skipped required gates publish nothing in that closure. Bind receipts to actual
source/artifact identities. Record/pin approved Actions, JDK image and toolchain inputs
before reproducible-release claims; mutable tags are not compromise evidence.

The [current full build failed](../reviews/2026-11-04-repository-review-evidence.md#the-one-full-build-checkpoint).
This review neither qualifies a core promotion nor authorizes publication or relaxed gates.

---
<!-- id: issue-20261028-230731-560-decouple-core-release-qualification-from-optional-  file: issue-20261028-230731-560-decouple-core-release-qualification-from-optional-.md -->
