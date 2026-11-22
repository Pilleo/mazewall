---
title: "Phase 6: Tag v0.1.0, publish :lockdown, and verify remote artifact download"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261120-122430-554-phase-0-1-finish-maven-central-namespace-verificat"
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
Execute the operator-controlled release of io.mazewall:mazewall-lockdown:0.1.0 from a clean verified commit and confirm throwaway remote resolution before announcement.

**Needed:**
1. Build clean checkout candidate, run full check/native/consumer/allowlist gates, tag v0.1.0 for operator-controlled publication, and verify throwaway remote download and smoke execution.

## Progress Evidence
- Scoped `.github/workflows/ci.yml`'s `publish` job strictly to `:lockdown:verifyLockdownPublication :lockdown:publishMavenJavaPublicationToGitHubPackagesRepository` after `[core-quality, mutation-tests, security-check, build-logic-validation]`, enforced by `CheckCiCachePolicy` (`PUBLISH_GRAPH`) and `CiCachePolicyTest`.
- Remaining before archival: operator tagging (`v0.1.0`), remote publication to Maven Central / GitHub Packages, and throwaway remote download verification.

---
<!-- id: issue-20261120-122442-356-phase-6-tag-v0-1-0-publish-lockdown-and-verify-rem  file: issue-20261120-122442-356-phase-6-tag-v0-1-0-publish-lockdown-and-verify-rem.md -->
