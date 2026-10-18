---
title: "Keep CI container cache restore and save keys stable across generated build files"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "ci"
target_modules:
  - ":tools:orchestrator"
target_files:
  - ".github/workflows/ci.yml"
  - "scripts/run_containerized_tests.sh"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Keep CI container cache restore and save keys stable across generated build files

**Context:**
Operator-requested read-only review of CI run `39122718255` found that the optional container Gradle cache restore/save keys differ within one job for the same PR merge SHA. At 11:22:32 UTC restore requested hash `0a671d6f...`; at 11:32:26 save used hash `5b930fad...`, both ending in `631fe9e1d99bf9fc69cc9ac0fdd3f77259501043`.

`.github/workflows/ci.yml:52,87` reevaluates broad `**/*.gradle*`/properties globs at each step. The glob admits generated `buildSrc/build/kotlin-dsl/.../*.gradle.kts` and `.gradle-instrumented-classpath.marker` files under build/TestKit caches, so intended source inputs are not isolated from generated state. Exact hit behavior becomes unreliable and large archives churn despite fallback restoration. No CI workflow or cache behavior is changed in the thread feature; this issue records the confirmed key mismatch for separate remediation. The legacy module label identifies the recognized control-plane owner, not permission for unrelated orchestrator edits.

**Needed:**
1. Compute a cache key once from the intended stable tracked build/toolchain inputs and reuse that exact value for restore/save; do not let generated/ignored files or marker counts affect it. Account explicitly for lockfiles, wrapper and image/toolchain identity as appropriate.
2. Add a regression: generated Gradle scripts/markers leave the source key unchanged; a real relevant build/lock/toolchain input change alters it; restore and save consume the same computed key.
3. Measure cache hits, transfer time, contents and storage churn before splitting/reducing caches. Consider separating stable dependency/toolchain data from smaller per-commit build outputs; do not remove SHA versioning blindly or delete caches without operator approval.
4. Preserve caller ownership, dedicated host/container cache isolation, required all-job merge/publication gating and uncached genuine native execution. Do not speed CI by caching protection success, skipping tests or changing credential bindings.

## Investigation
- Completed run job `117661407309` logs contain both keys and the actual fallback restore/save. GitHub job timing: restore 20 seconds, save 13 seconds; optional job 841 seconds and serialized main job 847 seconds. Cache transfer was not the dominant critical path or a demonstrated hang.
- The cache API reported three optional container entries totaling 4,498,356,255 bytes, about 1.5 GB each, and repository-wide 52 entries / 10,713,801,086 bytes at inspection. Configured quota/eviction and cold-build savings were not independently profiled.
- Local uncompressed dedicated home was about 2.5 GB: Gradle caches about 1.8 GB (628 MB dependency modules and about 1.2 GB version-specific data), JDKs 538 MB and wrapper 152 MB. This is a local content estimate, not a byte-for-byte remote archive audit.
- Related workflow: https://github.com/Pilleo/mazewall/actions/runs/39122718255 . Review was read-only and made no cache deletion, cancellation or workflow change.

## Side effects
- CI-key correctness follow-up only; preserve required native freshness, ownership checks, protected merge gating and cache isolation. No workflow remediation is part of the current thread feature.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20261018-132635  file: issue-20261018-132635-keep-ci-container-cache-restore-and-save-keys-stable-across.md -->
