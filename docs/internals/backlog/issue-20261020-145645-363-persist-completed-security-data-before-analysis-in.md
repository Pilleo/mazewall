---
title: "Persist completed security data before analysis in an independent required lane"
severity: "HIGH"
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
effort: "medium"
autonomy: "supervised"
has_side_effects: true
---

# 🟡 [Severity: HIGH]: Persist completed security data before analysis in an independent required lane

**Context:**
Postmerge548 run39424518821 restored empty host Gradle data and exhausted its90-minute core budget during cold NVD update, after native/unit/demo succeeded. setup-gradle post-save skipped after cancellation, container cache is separate, publication correctly blocked. Cache eviction plausible not proven. Isolate host security, restore a version/OS/arch-compatible data-only snapshot, update successfully and validate ownership, explicitly persist completed data before real analysis. Preserve CVSS7/freshness/error/missing-key/filter/native/publication policy. If snapshot upload fails analysis still runs after successful update/ownership unless cancelled; job remains failed. No dependency/heap/scanner-root-config/credential change, no stale-data fallback or partialDB save. Keep separate from unshipped explicit build-logic gate.

**Needed:**
1. Add source-bound policy and shell negative controls for compatible immutable snapshots, update/ownership/save/analysis order and failure paths; observe RED.
2. Move security to an independent required host lane with completed-data cache ownership and bounded cold bootstrap, preserve real analysis and fail-closed stable join.
3. Run focused policy/YAML/lifecycle tests, full local build and whole-change review; preserve previous gates and cache boundaries.
4. Qualify actual trusted default cold update/save/analysis and compatible warm/due-refresh behavior; no publication or speed claims from skipped credential-ineligible scans.

## Scope and evidence

[Design](../../superpowers/specs/2026-10-20-completed-security-data-design.md)
and [plan](../../superpowers/plans/2026-10-20-completed-security-data.md) preserve
normal freshness and real analysis. Logs prove empty host restore, full NVD update
and skipped host post-save after timeout; near10GiB cache pressure does not prove
historical eviction. No scan/publication pass is inferred from a saved database.

Engineering ruling after builder check: snapshot-upload failure must leave the
job red but must not suppress analysis after successful update and ownership,
unless cancelled. Failed update/ownership still blocks use/save; no partial-data
resume or stale fallback. Keep separate from the unshipped build-logic optimization
and reconcile both join prerequisites explicitly if that branch later integrates.

---
<!-- id: issue-20261020-145645-363-persist-completed-security-data-before-analysis-in  file: issue-20261020-145645-363-persist-completed-security-data-before-analysis-in.md -->
