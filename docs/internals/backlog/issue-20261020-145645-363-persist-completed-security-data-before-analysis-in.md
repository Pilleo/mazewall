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

Whole-branch review found one Important version-prefix collision: a stable version
could restore a prerelease snapshot. Commit `a709eca6` terminates the version with
`_`, excluded by the accepted version grammar. Actual metadata-shell RED reproduced
the collision; GREEN proves mutual stable/prerelease nonmatch and OS/architecture
isolation, retaining unsafe-version/attempt/matched-key-save controls. Scoped
re-review marks the finding addressed with no new Critical/Important breakage.

The corrected complete local build passed in16m36s,245 tasks (180 executed,
7 cached,58 up-to-date). Hosted required checks and actual trusted-default
cold/warm/update-due persistence/analysis/publication remain outstanding. Neither
local fake-task controls nor exact lookup membership alone close this issue.

## Additional pre-merge review findings

Operator-requested ponytail review found two build-verification defects beyond the
earlier prefix fix. The foreign-ownership fixture assumes `/etc/hostname` is owned
by another UID, which is false for root builds (including the development image's
default user). Also, the new executable policy checks read the actual workflow,
but that file is absent from the marker-producing verifier's declared inputs;
workflow-only edits can therefore skip those checks as UP-TO-DATE.

The operator approved fixes: select and verify a truly foreign-owned test fixture
without changing production ownership rules, and declare the actual workflow as
a relative-path-sensitive verifier input with an applied-wiring invalidation test.
Commit `0905b992` addresses both findings. Ownership controls reject caller-owned
candidates, verify the foreign UID, and preserve actual positive/negative shell
checks. Root-only fixture ownership assignment is limited to an exclusive temporary
file; UID-0 selection was modeled on a UID-1000 host, not executed as root.

The input regression applies the actual production JavaExec registration: prime
SUCCESS, unchanged UP-TO-DATE, workflow-only edit SUCCESS, and invalid workflow
FAILED with the old marker removed. Both regressions observed intended RED before
GREEN. Root policy passed with the mandatory aggregate bootstrap; the corrected
full local build passed in4m24s (245 tasks,31 executed,214 up-to-date). Scoped
independent review marks both addressed with no new Critical/Important findings.

Renewed hosted qualification is pending. Existing PR cold/warm evidence belongs
to the old head and must not be relabelled current-head proof. No claim is made
that all preexisting tests or the entire repository now support root execution.

---
<!-- id: issue-20261020-145645-363-persist-completed-security-data-before-analysis-in  file: issue-20261020-145645-363-persist-completed-security-data-before-analysis-in.md -->
