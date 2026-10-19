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
  - "scripts/ci_cache_key.sh"
  - "buildSrc/src/test/java/io/mazewall/build/CiCacheKeyFunctionalTest.java"
  - "buildSrc/src/test/java/io/mazewall/build/VerificationActionsTest.java"
  - "buildSrc/build.gradle.kts"
  - "buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt"
  - "buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt"
  - "docs/internals/designs/core/testing-strategy.md"
needs_kernel: false
core_lock: true
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

## Assigned isolated cache-key fix

The operator accepted this recommendation after green PR 536 merged as `7d84bb1c852c47e010fc57b5d0aa1ddaabf8b753`. Work is assigned to this session on `fix/ci-cache-key-stability`, separately from the completed thread feature. The local execution plan is `docs/superpowers/plans/2026-10-18-ci-cache-key-stability.md` (Git-ignored).

Implement one shared tracked-input hash helper, mandatory real-Git regressions and narrow workflow-policy checks; compute one job output before Gradle and bind restore/save to it. Keep archive shape, SHA suffixes, prefixes, fallback hierarchy, job serialization and all security/native gates. `scripts/run_containerized_tests.sh` remains a read-only cache-layout dependency; no change there is planned. Existing module metadata is legacy control-plane routing, not permission for unrelated orchestrator edits.

Initial exact call-site inspection found only the cache task and its policy test. ADK blast-radius/full-backlog validation is unavailable and Codanna had no symbol, so impact evidence is recorded as degraded. The current root policy task passes the old workflow and does not yet catch the within-job hash drift; new regression evidence is required before implementation.

## Isolated fix and local acceptance

Implementation `24754b34064f4f42e66497a2077cf00b855f0258` adds the executable tracked-input helper, real-Git/error regressions, mandatory/focused verification, narrow workflow-policy mutations and one shared output per current job. The complete local branch was independently reviewed with no Critical/Important code finding. Later Markdown bookkeeping does not rename that verified executable input.

- RED: the missing helper and unchanged policy's acceptance of the mutable-key mutation both failed for the expected feature reasons.
- Focused `./gradlew -p buildSrc ciCachePolicyTest`, mandatory `verificationActionsTest` and root `checkCiCachePolicy` all passed. Real fixtures cover tracked input invalidation, generated/untracked exclusions, filenames/aliases/Git context and failed/malformed hash operations with no partial usable digest. Existing five policy rules and native-freshness functional fixtures remain.
- `LC_ALL=C ./gradlew build --console=plain --max-workers=1` exited 0 in 21m20s; 242 tasks: 179 executed, 7 from cache, 56 up-to-date. Nested expected-negative harness failures are not failed outer verification.
- Actual helper output before and after that real full build was identical: `2f1f0f73ac7713ba5d956a5e663b0b4e8a1208d59d5aaad8708edf1f315574b6`. Generated files no longer perturb this local identity. Source/selector data must remain stable during hashing; this is not race-proof filesystem containment or native attestation.
- Cache layout, SHA suffixes, fallback hierarchy, image keys, permissions, ownership, job serialization, publisher bindings and uncached native outcomes remain unchanged. No cache deletion, size/performance claim or dependency/tool upgrade.

Evidence is retained under `.superpowers/sdd/2026-10-18-ci-cache-key-stability/`, including exact per-task argv/log/exits, `full-build.log`/`.exit`, `local-acceptance-receipt.json` and `task-1-review.md`. Existing toolchain warnings remain explicit maintenance. Current full ADK validation is unavailable; current index validation is narrower.

**Pending:** publish the approved separate PR, require remote CI, and compare actual logged restore/save primary keys for the same SHA in exercised save contexts. A skipped main-branch-only save is not observed equality. Local proof is not yet a remote cache-hit/transfer/size qualification. Keep this issue open for operator/control-plane acceptance; do not automatically merge or archive it.

## Subsequent remote qualification

PR [537](https://github.com/Pilleo/mazewall/pull/537) passed both required PR jobs and merged with operator approval as `6a966c7fd0f23b4d140adff986f76fe7424bb5b2`. The earlier pending-publication record above is historical.

Default-branch run [39147119265](https://github.com/Pilleo/mazewall/actions/runs/39147119265) exercised **both** cache save contexts. Each actual requested restore primary exactly matched its actual saved primary, including digest `2f1f0f73ac7713ba5d956a5e663b0b4e8a1208d59d5aaad8708edf1f315574b6` and merge SHA `6a966c7fd0f23b4d140adff986f76fe7424bb5b2`. `master-cache-receipt.json` and the full job logs under the existing evidence workspace retain those comparisons. Both jobs initially restored older fallback archives; an exact primary hit or performance improvement is not established.

The optional-integration and host-unit gates passed, but the **overall default-branch workflow failed** in `:enforcer:integrationTest`. Its fatal report identifies cold mmap method-handle linkage and executable CodeHeap growth denied with EPERM in an existing strict protection fixture. Publication was skipped. This independently tracked native-gate blocker is [issue-20261018-173748-642](../issue-20261018-173748-642-prevent-cold-mmap-linkage-from-aborting-strict-pro.md); no passing native result is inferred from a successful cache save. Cache-key binding is remotely verified, while full workflow acceptance remains red. No policy relaxation, cache deletion, automatic rerun or automatic backlog archival was performed.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20261018-132635  file: issue-20261018-132635-keep-ci-container-cache-restore-and-save-keys-stable-across.md -->
