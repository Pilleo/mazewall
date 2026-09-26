---
title: "Remove wildcard imports blocking rootful Tier E gate in a clean checkout"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "platform/src/integrationTest/kotlin/io/mazewall/core/GrantedFdScmRightsTest.kt"
target_symbols:
  - "GrantedFdScmRightsTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Remove wildcard imports blocking rootful Tier E gate in a clean checkout

**Context:**
A clean rootful Docker gate with --rerun-tasks fails ktlintIntegrationTestSourceSetFormat on three wildcard imports before Tier E tests start.

**Needed:**
1. Replace only the three wildcard imports with the exact referenced platform symbols and rerun focused platform and rootful checks.

**Resolution:** Replaced the three wildcard imports with exact file-descriptor,
process-launcher, and socket imports. `./gradlew :platform:integrationTest --tests
io.mazewall.platform.lifecycle.GrantedFdScmRightsTest --rerun-tasks` passed, and
the next clean rootful gate advanced past platform ktlint to the separate
enforcer integration-test ktlint failure (issue-20260926-113027-386).

---
<!-- id: issue-20260926-112452-863-remove-wildcard-imports-blocking-rootful-tier-e-ga  file: issue-20260926-112452-863-remove-wildcard-imports-blocking-rootful-tier-e-ga.md -->
