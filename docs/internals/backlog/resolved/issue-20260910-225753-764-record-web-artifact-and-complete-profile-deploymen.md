---
title: "Record web artifact and complete profile deployment evidence"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: ["demos:vulnerable-web-app"]
target_files:
  - "demos/vulnerable-web-app/compose.yml"
target_symbols:
  - "MazewallProfileManager"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Record web artifact and complete profile deployment evidence

**Context:**
RH-28 and RH-29: default gadget isolation, private shared profile volume and collector-complete enforcement require recorded regression evidence.

**Needed:**
1. Record implementing commits and executable regression evidence in the canonical hardening manifest.

**Resolution:** RH-28–RH-29 are implemented by commits 62a3f7a5, 077e1f81. Regression owners: DeploymentContractTest, EnforcementEvidenceTest, MazewallProfileManagerTest and ProfileArtifactPublisherTest.

**Verification:** Full profiler and web suites passed in the web package (570 profiler and 32 web tests), including real fresh-JVM Spring scenarios. Compose config validation passed; Docker containers were not started. Missing/lossy collector metadata remains fail-closed.

The [canonical finding manifest](../../designs/core/post-review-hardening-evidence.md#liveness-and-boundary-review-rh) maps individual methods and gates. [Final integration evidence](../../designs/core/review-hardening-verification.md) distinguishes fresh execution from cached outputs and does not imply aggregate acceptance from this scoped resolution.

---
<!-- id: issue-20260910-225753-764-record-web-artifact-and-complete-profile-deploymen  file: issue-20260910-225753-764-record-web-artifact-and-complete-profile-deploymen.md -->
