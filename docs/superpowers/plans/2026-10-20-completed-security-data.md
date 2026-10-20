---
document_type: execution_plan
base_revision: 4032164666b55175eb07506ba9dc70d7cf282eba
---

# Completed Security Data Implementation Plan

> **For agentic workers:** Use subagent-driven-development or executing-plans task-by-task. Operator delegated routine decisions; no new worktree or unchecked merge. Keep this repair independent of held build-logic optimization.

**Goal:** Persist a completed host NVD update before real analysis and isolate cold security work from successful native validation.

**Architecture:** A supported restore/update/ownership/save/analyze lifecycle in an independent required security job. Existing core/mutation/optional/native and publication contracts remain fail-closed. Use existing policy fixtures, no framework or dependencies.

**Tech Stack:** Existing GitHub Actions, JDK25/Gradle9.6.1/Dependency-Check10.0.4, Python standard library and Kotlin source-bound policy tests.

**Spec:** [Completed security-data snapshots](../specs/2026-10-20-completed-security-data-design.md).

## Global Constraints

- Base40321646; keep this repair independent of the unshipped build-logic gate.
- Existing Ubuntu26.04/JDK25/Gradle9.6.1/Dependency-Check10.0.4; no dependencies, new services, heap/worker increases, native/runtime or root scanner configuration changes.
- Preserve CVSS7, suppressions, production configurations, demo exclusions, OSSIndex choice, normal auto-update/default freshness validity, failure behavior and existing missing-key eligibility.
- Never modify, filter, or handle GITHUB_TOKEN; no credential, package permission or default-branch-push changes.
- Only completed successful updates with caller-owned paths may be saved; no partial/cancelled snapshot, stale fallback, daily immutable key or cache-hit scan bypass.
- Host data has one cache owner; no overlap with setup-gradle or container mounts, cross-version/OS/arch fallback, cache purging or foreign-permission repair.
- Stable required names, optional/native/mutation checks, PR cancellation, core cache forwarding and publication guards stay intact; every needed result must explicitly succeed.
- Upload failure does not prevent analysis after successful update/ownership unless cancelled; upload failure still makes the job and required join fail.

## Review Focus

1. Completed-data identity cannot be overwritten or restored across scanner/OS/arch incompatibility; actual shell/conditions must reject wrong prefix and matched-key save.
2. Snapshot upload failure must not hide or prevent analysis, while failed update/ownership/cancellation must prevent unsafe use/save; test actual conditions against outcomes.
3. Eligibility and docs-only skip semantics must remain exactly old behavior, with no phantom executed-scan claim; pin Unicode blank keys and actual filter equality.
4. Setup-gradle and explicit cache must not double-own the DB; final/default publication must not bypass security through cache hit or missing join result.
5. Cold bootstrap remains externally variable; verify actual completed update/save/analysis and warm/due updates on trusted default ref separately from PR skip and local wiring proof.

## File map and interfaces

One implementation unit owns `.github/workflows/ci.yml`, existing
`buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt` and
`buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt`.
Controller owns testing-strategy and scoped issue/index/spec/plan updates.
No changes to root scanner configuration, catalog/locks, native runner or resources.

New job `security-check`, display `Security Validation`; job180min, update150min.
Compatibility prefix `dc-data-v1-${runner.os}-${runner.arch}-dc${version}-`, version
parsed from catalog, primary suffix run_id/run_attempt. Actual data under required
GRADLE_USER_HOME; restore/save only that path. Stable existing join needs exactly
`[core-quality, mutation-tests, security-check]` and all three literal successes.
Publisher optional/join dependency and container hash forwarding unchanged.

### Task 1: Required completed-data security lifecycle

- [ ] Read investigation report/provenance and existing workflow/policy/test extraction helpers. Trace every old security/filter/cache/join caller; write tests before production changes, keeping every old fixture/assertion.
- [ ] Add failing valid-lifecycle fixture and exact negative mutations for absent/serialized/skipped/allowed-failure security, overlapping data cache, unsafe prefix/version/key, cache-hit bypass, no updater/analysis/ownership or unsafe-save/implicit-success conditions. Actual extracted metadata/eligibility shell tests cover absent/empty/ASCII+Unicode blank and normal keys, version bump/unsafe version, unique same-run attempts, no emitted key values. Actual join shell tests125 tuples, only all-success passes. Condition/control tests cover update failure, foreign ownership, cancellation, upload failure with both analysis outcomes, missing key and docs-only behavior without a network scanner.
- [ ] RED: `./gradlew -p buildSrc ciCachePolicyTest --console=plain --max-workers=1` must fail for missing production lifecycle/policy, not dependency/toolchain failure. Capture exact source/command/exit.
- [ ] Implement only the three files: independent lane/filters/explicit cache ownership, updater/save/analysis sequencing and failure semantics, exact literal-success join. Use supported cache actions/public setup-gradle inputs, current bindings, one cache owner and no new helper framework. Keep core's demo filter, native/ownership/container outputs, all other job maps and publisher behavior unchanged except removing core security/data include.
- [ ] GREEN: focused command plus root `checkCiCachePolicy` and installed duplicate-rejecting actual YAML/shell checks. Compare all baseline job maps and root scanner config; exercise actual conditions/shell, not only mutation strings. No real NVD call, huge suite, dependency update or source outside scope.
- [ ] Self-review and commit only three files: `git commit -m "Persist completed security data in an independent required lane"`; report exact RED/GREEN/argv/YAML/failurecontrols/limits and no real scan claim.

## Completion and hosted qualification

- [ ] Controller validates evidence/diff, updates testing-strategy/issue and exact link/backlog/whitespace checks. Run focused host checks and complete `./gradlew build --console=plain --max-workers=2` once after focused green; record any real failure, no ignored native/assertion outcome. Real NVD network execution remains hosted.
- [ ] One whole-branch read-only fresh review of lifecycle/save/failure/eligibility/cache isolation/join/publisher, with source-bound evidence. Grade Important/Critical by actual effect and fix once with covering RED/GREEN tests; minors ledgered, no broad cleanup.
- [ ] Open scoped PR through normal review path. Actual hosted required/optional/native/mutation checks must pass; disclose whether NVD was skipped under existing key policy. Local/PR green does not qualify an executed scan.
- [ ] After qualified authorized merge, trusted default cold security job must show successful update, ownership, explicit completed save and real CVSS analysis; publication remains blocked unless all real outcomes pass. Do not relabel cache/DB save as scan pass or retry full native pipelines just to bootstrap.
- [ ] Compatible same-head warm run must restore saved version/ref data and execute analysis again. Later normal update-due observation must advance a unique snapshot; measure duration/runnerminutes/sizes/external API limits, no guaranteed speed or eviction immunity.
- [ ] Reconcile held build-logic branch after this repair: retain both extra join requirements and all original gates; do not silently ship its older join over security.

## Self-review

- [x] Scope is one lifecycle repair; data/eligibility/update/save/failure/join interfaces agree.
- [x] All constraints pinned to policy, actual shell/YAML or real hosted proof.
- [x] Builder-check upload-failure question resolved: analyze after successful update/ownership unless cancelled; failed upload remains red.
- [x] First/warm/due actual-security acceptance distinguished from skipped PR/local checks; no unknown eviction claim or unsupported partial resume.
