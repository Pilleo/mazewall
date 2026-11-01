---
title: "Remove serialized CI validation delay while preserving complete gates"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "ci"
target_modules: []
target_files:
   - ".github/workflows/ci.yml"
   - "buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt"
   - "buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt"
   - "docs/internals/designs/core/testing-strategy.md"
target_symbols: []
open_questions: false
needs_kernel: false
core_lock: true
effort: "small"
autonomy: "supervised"
has_side_effects: true
verify_cheap:
   - "./gradlew -p buildSrc ciCachePolicyTest --console=plain --max-workers=1"
   - "./gradlew checkCiCachePolicy --console=plain --max-workers=1"
---

# 🟡 [Severity: MEDIUM]: Remove serialized CI validation delay while preserving complete gates

**Context:**
The operator requests materially faster CI after two serial validation jobs took
about 52 minutes. Cache restores work: the cleanup run reused the prior 1.7 GB
container cache and adapter build time fell from 6m38s to 3m23s. The avoidable
critical-path delay is the main job waiting for optional acceptance before
starting. Run independent validation in parallel; publish only after both succeed.
Cancel superseded PR runs, never separate default-branch release runs. Preserve
all tests, required check names, native freshness, credentials and cache isolation.

**Needed:**
1. Add policy regressions for parallel validation, both-gate publication and PR-only cancellation before changing the workflow.
2. Run both existing validation jobs independently, move existing publication into a default-branch-only job depending on both, and retain cache identities and ownership checks.
3. Verify all policy mutations and full local build, then compare actual hosted job overlap and timings; make no unmeasured performance claim.
4. Reuse the existing optional PR container archive as a main-container restore fallback; keep primary identities and cache layout, and add no extra per-PR archive. Pin fallback ordering with a policy regression.

## Local verification and hosted acceptance

The cleanup baseline [run39298751190](https://github.com/Pilleo/mazewall/actions/runs/39298751190)
spent 13m40s in optional acceptance and another 16m02s in the serial main job.
The new graph permits overlap; this is not yet a measured hosted speedup.

Graph and cache-reuse assertions failed before their respective policy changes,
then passed with the complete existing cache/Git-helper mutation suite. The
actual workflow policy and full local optional build pass. YAML comparison retains
every existing gate command; the publication command/bindings are unchanged.
Independent review found no blocking defect. Keep open until hosted overlap,
cache selection and actual durations are checked; default-branch publication is
not claimed exercised by a PR run.

---
<!-- id: issue-20261019-182206-556-remove-serialized-ci-validation-delay-while-preser  file: issue-20261019-182206-556-remove-serialized-ci-validation-delay-while-preser.md -->
