---
title: "Mazewall 0.1.0 process-wide lockdown release"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261120-122430-554-phase-0-1-finish-maven-central-namespace-verificat"
  - "issue-20261120-122431-839-phase-2-extract-lockdown-module-and-cut-four-coupl"
  - "issue-20261120-122433-169-phase-3-implement-stable-io-mazewall-lockdown-api-"
  - "issue-20261120-122441-870-phase-4-verify-kernel-matrix-packaged-consumers-an"
  - "issue-20261120-122442-047-phase-5-write-0-1-0-release-documentation-and-clea"
  - "issue-20261120-122442-356-phase-6-tag-v0-1-0-publish-lockdown-and-verify-rem"
component: "core"
target_modules: [":lockdown", ":platform", ":enforcer"]
target_files:
  - "docs/designs/first-process-lockdown-release.md"
  - "settings.gradle.kts"
  - "build.gradle.kts"
target_symbols:
  - "Mazewall"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Mazewall 0.1.0 process-wide lockdown release

**Context:**
Track the 0.1.0 release of one supported process-wide external-program lockdown capability via the extracted `:lockdown` module (`io.mazewall:mazewall-lockdown:0.1.0`, Option 2 — GO after Phase 1 measurement at commit `ccbe82740325480b2e99d7bd9e92df7dc7680872`).

**Related existing owners:**
- [issue-20261028-230731-560](issue-20261028-230731-560-decouple-core-release-qualification-from-optional-.md) (decouple core release qualification from optional integrations)
- [issue-20261028-232811-964](resolved/issue-20261028-232811-964-reject-installation-receipts-containing-both-succe.md) (reject contradictory installation receipts)
- [issue-20261028-230730-359](issue-20261028-230730-359-publish-qualified-platform-envelopes-for-each-cont.md) (qualified platform envelopes)

**Needed:**
1. Complete Phase 0-1 open namespace verification, Phase 2 (`:lockdown` extraction), Phase 3 (stable `io.mazewall.lockdown` API), Phase 4 (verification, packaging, independent security review), Phase 5 (docs and hygiene), and Phase 6 (release).

## Progress Evidence
- Completed Phases 1–3 (`:lockdown` module extraction, 4 seam cuts, `io.mazewall.lockdown` stable API, `private` constructors + `@JvmSynthetic` internal accessors, ABI dump, Java/Kotlin tests).
- Relocated `:lockdown`'s internalized core sources to `io.mazewall.lockdown.internal.*` (eliminating Java keyword package `platform.native` and split-package risks), added JPMS `module-info.java` + `-sources.jar` + `-javadoc.jar`, and extended `VerifyLockdownPublication` with `ModuleFinder` package/export checks.
- Completed Phase 4 in-repo verification (`MazewallLockdownIntegrationTest` 5-thread-model + 5-syscall + `clone3`/`prctl` + JPMS/`macOS`/`--illegal-native-access=deny`/`selfVerify=true` subprocess suite, `MazewallLockdownNativeImageIntegrationTest` `-Xint` `PROT_EXEC` denial + `HOTSPOT_JIT` relaxation rejection, and `VerifyLockdownPublication`).
- Completed Phase 5 release documentation (`LICENSE`, `STABILITY.md`, `CHANGELOG.md`, `docs/public-api.md`, `README.md`, `GETTING_STARTED.md`, POM metadata).
- Deferred: Maven Central namespace registration (`issue-20261120-122430-554`) and GitHub Packages / Maven Central tag & publish (`issue-20261120-122442-356`) await explicit operator execution.

---
<!-- id: issue-20261120-122411-300-mazewall-0-1-0-process-wide-lockdown-release  file: issue-20261120-122411-300-mazewall-0-1-0-process-wide-lockdown-release.md -->
