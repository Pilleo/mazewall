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

## Local implementation evidence — hosted qualification pending

Commit `00095123` implements only workflow and existing policy/test files. Focused
RED/GREEN controls include actual metadata/Unicode eligibility/ownership/task shell,
all125 join triples and exact-hit assertion true/false/unset/nonexact cases.
Actual YAML duplicate rejection,32 shell syntax checks and baseline job-map checks
passed. No real cache operation or NVD scan was executed by these controls.

The first root policy invocation timed out at120 seconds inside the existing
implicit build-tool verifier; it was not counted as a pass. The controller then
ran the complete local `./gradlew build --console=plain --max-workers=2` with enough
harness time: it passed in20m52s,245 tasks (183 executed,7 cached,55 up-to-date),
including root policy and existing mandatory bootstrap. No project heap, timeout
or verifier-success marker was enlarged or bypassed.

Task review passed spec and quality with no findings. Whole-branch review and real
trusted cold/warm/update-due persistence and analysis remain pending. Exact cache
membership is not archive integrity/retention, nor a security/publication pass.

---
<!-- id: issue-20261020-145645-363-persist-completed-security-data-before-analysis-in  file: issue-20261020-145645-363-persist-completed-security-data-before-analysis-in.md -->
