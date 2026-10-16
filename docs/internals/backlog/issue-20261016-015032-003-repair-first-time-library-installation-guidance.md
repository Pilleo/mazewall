---
title: "Repair first-time library installation guidance"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "GETTING_STARTED.md"
  - "enforcer/README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Repair first-time library installation guidance

**Context:**
The recommended dependency uses the renamed jseccomp repository and nonexistent
main branch; enforcer repeats it, JitPack currently lists no builds for mazewall,
and the source-build JDK requirement is unstated. The guide also incorrectly calls
kernel 6.2 a full-feature guarantee and tells users to run the removed
`scripts/run_tests.sh` launcher.

**Needed:**
1. Document and verify a source-to-Maven-local installation with an independent consumer; correct optional JitPack coordinates and link module guidance to the canonical instructions.
2. Distinguish build/runtime/compiler requirements, remove the blanket kernel
   feature claim, and point verification commands at maintained Gradle gates.

## Verification evidence

- Baseline: merged master `6f6f71a8fa7900020ccdda1ae85b057954110553` in the
  isolated `fix/getting-started-installation` worktree.
- The old documented Maven metadata URL returned **HTTP 404** (`run-ea2631ebc1ba`).
  Git refs contain `master`, not `main` (`run-ce50f2cd8f0d`), and the legacy GitHub
  name redirects to `Pilleo/mazewall` (`run-d656bc36f210`).
- The documented `:platform:publishToMavenLocal :enforcer:publishToMavenLocal`
  tasks passed on OpenJDK 25 / Gradle 9.6.1 in 4m14s (`run-89023792dc96`).
  A private `-Dmaven.repo.local` directory preserved the operator's normal Maven
  repository. BuildSrc's mandatory verification fixtures also ran; their deliberate
  negative-control build failures are not publication failures.
- A separate Gradle project used the guide's `mavenLocal` repository block and
  exact `io.mazewall:enforcer:0.1.0-SNAPSHOT` dependency. Stable Kotlin **2.4.20**
  compiled and ran the existing independent `KotlinApiConsumer` in 19s
  (`run-f1ad29ca6b22`). Its process classpath names both newly published local
  enforcer/platform JARs; no producer-project/source substitution was used.
- Reference documentation: JitPack's current unversioned multi-module/snapshot
  guidance and Gradle **9.6.1** Maven-local publishing/repository documentation.
- Local reference checks passed: all ten relative guide links, both new module
  anchors, referenced launchers, Bash syntax, and equality between the documented
  repository/dependency snippets and the actual independent consumer
  (`run-79591683811e`). The check caught the same retired launcher in the module's
  testing section; that entry now uses the maintained container runner too.
- The documented HTTPS clone URL resolves the expected `master` commit
  (`run-3da94b2ec216`). Backlog membership/totals passed (`run-daa8f7859233`),
  as did `git diff --check` (`run-4c7bce63cbf8`).
- This qualifies the local artifact installation and policy-construction path,
  not a clean-cache network bootstrap, remote JitPack/GitHub Packages delivery,
  JDK 22 native execution, or new kernel containment behavior. Credentials and
  publication configuration are unchanged.

Implementation and local qualification are complete. This issue remains open for
repository review; no merge or remote package-delivery acceptance is claimed.

---
<!-- id: issue-20261016-015032-003-repair-first-time-library-installation-guidance  file: issue-20261016-015032-003-repair-first-time-library-installation-guidance.md -->
