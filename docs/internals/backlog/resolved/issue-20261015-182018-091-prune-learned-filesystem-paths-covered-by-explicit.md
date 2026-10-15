---
title: "Prune learned filesystem paths covered by explicit base grants"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/SbobParser.kt"
  - "enforcer/src/test/kotlin/io/mazewall/SbobParserTest.kt"
target_symbols: ["SbobParser"]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Prune learned filesystem paths covered by explicit base grants

**Context:**
The real complete-profile handoff exposes redundant learned file grants beneath
an already-approved existing base directory. The parser pruned learned paths
without considering those base roots, so strict prepared-resource capture tried
to pin a stale/missing learned child despite the same read/write authority
already covering it through its parent. Do not create that child or weaken
PreparedPath validation. Normalize/prune each permission set with its respective
base grants, preserving symlink handling and separation of reads from writes.

**Needed:**
1. Reproduce actual prepared-resource capture failure for an absent learned child covered by an explicit base root; reuse existing physical normalization/pruning for respective read/write unions and verify no grant expansion or cross-permission pruning.

## Evidence so far

Actual `PreparedResourceSet.capture` RED `run-81d5b0bd835b`; only the two prune
inputs now include their corresponding base paths. The complete parser test
class passes in `run-e893c76ed610`. Public parser learning semantics remain
unchanged: observations can unblock a starting policy, and a consumer that needs
an approval ceiling must explicitly restrict the learned result further.
Direct callers inspected (`run-e3b87c4921b0`); symbol evidence is PARTIAL, not a
complete graph (`run-502b45bfc579`). Further boundary checks and full quality gate
remain required before closure.

## Resolution

The full parser suite additionally verifies no read-to-write authority promotion,
preservation of learned symlink targets, restrictive exclusion outside the base
ceiling, and continued failure for an invalid explicitly requested base resource.
Actual prepared-resource capture succeeds without creating the redundant learned
child. Full build passes (`run-bbf486925536`); the unchanged enforcer mutation
gate passes with matching current inputs (`run-c0f65b467853`). Connected review
confirms only redundant learned grants are pruned through the existing physical
normalizer; strict resource pinning, permission separation and public learning
semantics remain intact.

---
<!-- id: issue-20261015-182018-091-prune-learned-filesystem-paths-covered-by-explicit  file: issue-20261015-182018-091-prune-learned-filesystem-paths-covered-by-explicit.md -->
