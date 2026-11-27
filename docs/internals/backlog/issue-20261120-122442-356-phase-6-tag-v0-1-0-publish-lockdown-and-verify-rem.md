---
title: "Phase 6: Tag v0.1.0, publish :lockdown, and verify remote artifact download"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261120-122441-870-phase-4-verify-kernel-matrix-packaged-consumers-an"
  - "issue-20261120-122442-047-phase-5-write-0-1-0-release-documentation-and-clea"
component: "core"
target_modules: [":lockdown"]
target_files:
  - ".github/workflows/ci.yml"
target_symbols:
  - "publish"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Phase 6: Tag v0.1.0, publish :lockdown, and verify remote artifact download

**Context:**
Execute the operator-controlled release of `io.mazewall:mazewall-lockdown:0.1.0` to GitHub Packages only, from a clean verified commit. Maven Central is not in scope.

**Needed:**
1. Establish an operator-approved path to publish the non-snapshot `0.1.0` artifact from `v0.1.0`, with a version/tag check and the Phase 4 gates. Keep default-branch snapshot publishing separate.
2. Before announcing, use the published quickstart in a throwaway project and empty dependency cache with a consumer account lacking maintainer access. Verify the package visibility, exact coordinate, artifact metadata and checksums against the staged candidate, then run the lockdown smoke check.

## Progress Evidence
- Scoped `.github/workflows/ci.yml`'s `publish` job strictly to `:lockdown:verifyLockdownPublication :lockdown:publishMavenJavaPublicationToGitHubPackagesRepository` after `[core-quality, mutation-tests, security-check, build-logic-validation]`, enforced by `CheckCiCachePolicy` (`PUBLISH_GRAPH`) and `CiCachePolicyTest`.
- This job publishes branch snapshots; it does not publish the `v0.1.0` tag.

---
<!-- id: issue-20261120-122442-356-phase-6-tag-v0-1-0-publish-lockdown-and-verify-rem  file: issue-20261120-122442-356-phase-6-tag-v0-1-0-publish-lockdown-and-verify-rem.md -->
