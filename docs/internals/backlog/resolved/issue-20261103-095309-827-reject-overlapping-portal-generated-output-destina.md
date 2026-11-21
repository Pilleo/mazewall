---
title: "Reject overlapping Portal generated-output destinations before publication"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "portal-codegen"
target_modules:
  - ":portals:codegen"
target_files:
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalCodegenPlugin.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalCodegenFunctionalTest.kt"
  - "docs/guides/portal-service-isolation.md"
target_symbols:
  - "GeneratedOutputPublisher"
  - "GeneratePortalStubsTask"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject overlapping Portal generated-output destinations before publication

**Context:**
CG07: a direct publisher probe reports success for child-then-parent destinations while silently deleting the just-published child output. Only exact destination equality is rejected; staging cleanup can also mask an earlier generation failure.

**Needed:**
1. Turn the child-then-parent publication probe into a regression. Cover both orders, equal paths, normalized `..` aliases and staged/destination overlap, with sentinels in the previous output generation.
2. Validate a disjoint publication plan before any destination creation/move/deletion. Require pairwise-disjoint owned output trees and staging trees; resolve or reject filesystem aliases consistently, including symlinked existing ancestors.
3. Keep staging, backup and published ownership explicit through the existing transaction. Reject configurations that allow an output operation to delete or replace another output/staging tree.
4. Preserve the original/fatal failure across rollback and staging cleanup, reusing the repository's existing error-preservation pattern. Inject failures at backup, publication, rollback and cleanup boundaries.

**Scope and dependencies:**
- Independent corrective task. [Review CG07](../../reviews/2026-11-03-portal-codegen-hardening.md#cg07--validate-publication-paths-before-moving-anything) reproduces output loss; cleanup-error masking is source-confirmed and still needs fault injection.
- Default sibling output paths and the public Gradle configuration surface remain valid. Keep the existing publisher; a new filesystem transaction framework is unnecessary.
- Preserve existing user edits in the functional fixtures. P02 build conventions and P03's additional artifacts reuse the corrected publisher.

**Acceptance:**
- Invalid ownership/overlap fails before destination mutation and reports the conflicting paths. All previous sentinels remain intact.
- Valid sibling outputs publish completely, remove stale owned files, and remain deterministic/cache-compatible.
- A failed move restores the previous generation where rollback succeeds. Rollback/cleanup failures retain primary/fatal error identity and useful suppressed causes rather than reporting success or masking the original failure.
- No output/staging cleanup traverses a path owned by another publication entry. Tests cover both overlap orderings and filesystem aliases.

**Verification:**
- [Junior implementation walkthrough: CG07](../../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#cg07--disjoint-output-publication) details sentinel fixtures, path-alias cases and failure-injection observations.
- Run the publisher/failure-injection and rerun/relocation cases in `PortalCodegenFunctionalTest`, then `./gradlew :portals:codegen:test`.
- Run `./gradlew build`, backlog and whitespace checks at implementation completion. Record preservation of old/new output sentinels at each failure boundary.

---
## Repository-review revalidation — 2026-11-04

[CG07/PCG-07](../../reviews/2026-11-04-repository-review-findings.md#cg07) freshly reproduces
ancestor/descendant publication deleting successful child output. Cleanup can additionally
mask primary failure (source-confirmed). Validate disjoint staging/destinations and aliases
before mutation; test both orders, symlinks and failure suppression with prior-output sentinels.
Ordinary default siblings remain unaffected; bundle cache equivalence is separate PCG-08 work.

## Resolution Evidence (2026-11-21)
- Updated `GeneratedOutputPublisher` (`PortalCodegenPlugin.kt`) to canonicalize existing ancestors/symlinks and reject pairwise equal/overlapping destinations, overlapping staging directories, and staging/destination/backup overlaps prior to creating parent directories, backing up, moving, or deleting output trees.
- Preserved primary and fatal (`Error`) precedence across backup cleanup in `GeneratedOutputPublisher.publish` and staging cleanup in `GeneratePortalStubsTask.generate`.
- Added unit tests for both overlap orderings, `..` relative aliases, symlinked ancestors, staging overlap, and backup/publish/rollback/cleanup fault injection in `PortalStubGeneratorTest.kt`, plus TestKit overlap regression in `PortalCodegenFunctionalTest.kt`; verified with `./gradlew :portals:codegen:ktlintCheck :portals:codegen:detektMain :portals:codegen:spotbugsMain :detektWarningRatchet :portals:codegen:test --no-daemon --console=plain` (`54` tests passed, `0` failures, `0` skips).

---
<!-- id: issue-20261103-095309-827-reject-overlapping-portal-generated-output-destina  file: issue-20261103-095309-827-reject-overlapping-portal-generated-output-destina.md -->
