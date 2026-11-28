---
title: "Enable Gradle build and configuration caching in the pre-commit hook"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "scripts/hooks/pre-commit"
target_symbols:
  - "BUILD_TASKS"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Enable Gradle build and configuration caching in the pre-commit hook

**Context:**
Operator requires the full commit hook to use caching. The existing hook invokes the complete Gradle build plus included portal suites but overrides the repository configuration-cache setting with --no-configuration-cache. Keep full verification, fail-closed exit status, Git-local environment isolation, locking and serial execution. Explicitly enable both Gradle caches and verify an unchanged invocation reuses the configuration and eligible task outputs. No cached-success marker, skipped gate, dependency upgrade or changed credentials.

**Needed:**
1. Enable --build-cache and --configuration-cache, update the existing hook argument regression and guidance, verify fail-closed hook behavior, then demonstrate real Gradle cache reuse.

**Resolution**

Verified the existing full pre-commit command enables both caches and serial execution. The Git hook regression checked the exact arguments, clean environment, and rejection of a failed build. Retained real Gradle output from the prior full commit gate confirms task output cache hits and configuration-cache reuse.

---
<!-- id: issue-20261101-211554-659-enable-gradle-build-and-configuration-caching-in-t  file: issue-20261101-211554-659-enable-gradle-build-and-configuration-caching-in-t.md -->
