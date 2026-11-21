---
title: "Phase 5: Write 0.1.0 release documentation and clean repository hygiene"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies:
  - "issue-20261120-122433-169-phase-3-implement-stable-io-mazewall-lockdown-api-"
  - "issue-20261016-015032-003-repair-first-time-library-installation-guidance"
component: "core"
target_modules: [":lockdown"]
target_files:
  - "README.md"
  - "GETTING_STARTED.md"
  - "docs/public-api.md"
target_symbols:
  - "GETTING_STARTED"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Phase 5: Write 0.1.0 release documentation and clean repository hygiene

**Context:**
Align README, GETTING_STARTED, docs/public-api.md, STABILITY.md, CHANGELOG.md, LICENSE, and POM metadata with the supported io.mazewall.lockdown promise and exact limits, and clean repo-root scratch files.

**Needed:**
1. Add Apache-2.0 LICENSE and POM metadata, update README/GETTING_STARTED/public-api around Stable vs Experimental vs Internal tiers with exact threat-model limits, add STABILITY.md and CHANGELOG.md, and clean repo-root scratch files.

## Resolution Evidence (2026-11-21)
- Added repository-root `LICENSE` (Apache-2.0), `STABILITY.md` (3-tier Stable/Experimental/Internal contract with JPMS module `io.mazewall.lockdown`, relocated `io.mazewall.lockdown.internal.*`, `-sources.jar`/`-javadoc.jar`, exact filter/verification contract), and `CHANGELOG.md` (`0.1.0`).
- Added complete Maven POM metadata (Apache-2.0 license, URL, developer, SCM) to `lockdown/build.gradle.kts`.
- Updated `docs/public-api.md`, `README.md`, and `GETTING_STARTED.md` to lead with `io.mazewall:mazewall-lockdown:0.1.0` (`Mazewall.installOnProcess`, JPMS `--enable-native-access=io.mazewall.lockdown`), exact `prctl`/`clone3`/`fork` semantics, and opt-in `-Dio.mazewall.selfVerify=true`, while keeping existing `<!-- consumer: ... -->` snippets intact for `tests/consumers/doc-snippets`.
- Preserved pre-existing user-owned untracked files in repository root per repo policy.

- Full completion gate verified in commits `52e81d2e1` and `7e62bacf7` (`./gradlew build -PmazewallKernelLane=BASELINE` and rootful Docker `CONFINED` kernel lane passed).
---
<!-- id: issue-20261120-122442-047-phase-5-write-0-1-0-release-documentation-and-clea  file: issue-20261120-122442-047-phase-5-write-0-1-0-release-documentation-and-clea.md -->
