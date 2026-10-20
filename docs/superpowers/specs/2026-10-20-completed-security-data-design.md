# Completed security-data snapshots and independent analysis

## Intent and evidence

Repair post-merge security without replaying successful native/unit pipelines just
to populate NVD data. Run39424518821 restored an empty host Gradle home and exhausted
its90-minute core budget during a full410k-record update. Cancellation skipped the
host cache post-action; no completed scan or publication occurred. Container caches
cannot persist this host database. Eviction is plausible, not proven.

Operator delegated routine CI decisions without permission loops. Engineering
choices: independent host security job, completed-data snapshots,180-minute job
and150-minute update-step budget for the observed slow cold bootstrap. This is
not an extension of the entire core pipeline or a substitute for analysis.

## Binding constraints

- Base40321646; keep this repair independent of the unshipped build-logic gate.
- Existing Ubuntu26.04/JDK25/Gradle9.6.1/Dependency-Check10.0.4; no dependencies, new services, heap/worker increases, native/runtime or root scanner configuration changes.
- Preserve CVSS7, suppressions, production configurations, demo exclusions, OSSIndex choice, normal auto-update/default freshness validity, failure behavior and existing missing-key eligibility.
- Never modify, filter, or handle GITHUB_TOKEN; no credential, package permission or default-branch-push changes.
- Only completed successful updates with caller-owned paths may be saved; no partial/cancelled snapshot, stale fallback, daily immutable key or cache-hit scan bypass.
- Host data has one cache owner; no overlap with setup-gradle or container mounts, cross-version/OS/arch fallback, cache purging or foreign-permission repair.
- Stable required names, optional/native/mutation checks, PR cancellation, core cache forwarding and publication guards stay intact; every needed result must explicitly succeed.
- Upload failure does not prevent analysis after successful update/ownership unless cancelled; upload failure still makes the job and required join fail.

## Workflow and data lifecycle

Add independent `security-check` / `Security Validation`, contents:read and
pull-requests:read, no job-level condition/needs/continue-on-error. Copy the exact
existing code paths-filter for security; retain core's filter for exploitation.
Remove only core's security step and extra dependency-check-data cache include.
The new job uses existing checkout/JDK/setup-gradle policy, explicitly excluding
dependency-check-data from setup-gradle so the explicit data cache is its sole owner.

After setup derive data directory from actual required GRADLE_USER_HOME, parse
versions.dependencyCheck from actual TOML with Python standard library and reject
unsafe version tokens. Emit `dc-data-v1-${runner.os}-${runner.arch}-dc${version}-`
compatibility prefix and unique run_id/run_attempt primary key. Never use source
hash/commit as DB compatibility, previously matched key for save, or broad fallback.

Capture NVD eligibility only, never its value, using existing secret binding and
Kotlin isNullOrBlank semantics. Stdlib Unicode-category/separator checks may be
used; tests must include absent/empty/ASCII and Unicode whitespace. Do not treat
a key's presence as permission to change root scanner policy.

For eligible code changes, restore compatible data and verify caller ownership
before use. Run `./gradlew dependencyCheckUpdate --info --no-configuration-cache
--no-daemon` with existing NVD binding, pipefail log and150-minute step budget.
Verify checkout/actual Gradle home/data ownership again; no repair. Explicitly
save restore's new primary key immediately after successful update+ownership.
No always-save or partial-update acceptance.

Then run unchanged `dependencyCheckAnalyze --info --no-configuration-cache
--no-daemon` with the existing binding. Explicit non-cancellation/prerequisite
condition allows analysis after upload failure, without masking the failed upload
or analysis. Failed update/ownership prevents both save and analysis; cancellation
does not launch analysis. Missing/blank key prevents update/save; retain the old
analysis task's skip policy and expose it as skipped, not an executed scan. Docs-only
uses the old no-scan successful-job semantics, not a skipped prerequisite job.

Final caller-ownership validation and logs/reports use always(). Stable join
`Build, Test, Security Scan & Publish` adds security-check to core/mutation needs
and literal-success shell checks under always();125 result triples, only all-success
passes. Publication dependencies/default-push guard/package-write command stay
identical. A saved database is not a passed analysis or a publication permission.

## Proof and rollout

Existing source-bound CI policy and fixtures must reject unsafe lifecycle/cache
identity/ownership/save conditions, cache-hit bypass, stale freshness, missing real
analysis, changed filter/eligibility, and lost join/publication/native constraints.
Extract actual workflow shell for version/eligibility/key tests without secrets or
network. Actual YAML step-order/condition checks and actual125 join-shell matrix
are required, not a parallel model-only success simulation. Retain all old tests.

Use focused policy/host regressions then complete local build and one whole-change
review. No local NVD/network scan is needed to prove wiring. Hosted PR scans may
skip under existing secret policy; green job alone is not real analysis evidence.
Trusted default-branch cold run must finish updater, owned explicit save and real
analysis. Compatible warm run must restore that snapshot and analyze anew; later
update-due observation must show refreshed unique snapshot, not assume freshness
advanced inside normal validity. Record elapsed/runner-minutes/data size/ownership/
ref/version/task outcomes. Cache availability and external latency remain risks.
Target failed security/dependent jobs for recovery, not repeat whole native suites.

Unshipped build-logic gate later needs explicit join reconciliation retaining both
new prerequisites; neither branch may overwrite the other's required security.
The separate Portal cleanup cause remains unresolved and outside this repair.
