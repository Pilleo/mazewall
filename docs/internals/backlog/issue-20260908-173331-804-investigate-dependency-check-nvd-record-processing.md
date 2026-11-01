---
title: "Investigate Dependency-Check NVD record processing failure"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "ci"
target_modules: [":platform", ":enforcer", ":profiler"]
target_files:
  - ".github/workflows/ci.yml"
  - "gradle/libs.versions.toml"
  - "gradle/verification-metadata.xml"
  - "gradle.lockfile"
  - "build.gradle.kts"
  - "buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt"
  - "buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt"
  - "docs/internals/designs/core/testing-strategy.md"
target_symbols:
  - "Run Security Check"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Investigate Dependency-Check NVD record processing failure

**Context:**
CI run 34255417287 attempt 1 logged DatabaseException while processing CVE-2026-6785 because an NVD reference URL exceeded the plugin database column length; the scan still succeeded, but the affected record may be omitted.

**Needed:**
1. Reproduce with the pinned Dependency-Check plugin, then upgrade to 13.0.0 and review regenerated dependency verification/locks; do not suppress or truncate CVE data.
2. Reject record-processing errors even when the updater returns success. Verify both affected CVEs retain their references and software matches after complete ingestion.

## Reconfirmed 2026-10-15

CI38814643561 atb6235c38 with the unchanged10.0.4plugin ingests a cold408708-record
update and returns success, but logs URL-column failures for both CVE-2026-6785
(1585-character reference) and CVE-2026-6786 (1115-character reference) against
the1000-character column (`run-6efcfa27c113`). The successful exit therefore does
not prove complete ingestion. This remains independently open; no CVE suppression,
plugin/database patch or reduced scan threshold was introduced by the cleanup.

## Integrated repair and qualification — 2026-10-25

Dependency-Check 13.0.0, compatible security-data retention/recovery and fail-closed
record-processing checks are integrated through
[PR #554](https://github.com/Pilleo/mazewall/pull/554). The historical 10.0.4
failure above is not the current plugin version. Cold bootstrap and authenticated
refresh passed in [default run 40038896483](https://github.com/Pilleo/mazewall/actions/runs/40038896483);
the update downloaded all 4,092/4,092 records before successful analysis and save.

[Default run 40137910686](https://github.com/Pilleo/mazewall/actions/runs/40137910686)
at `2f80586bf7d5a33df43b301d8258ee41603f4bf7` then had a genuine security-cache
miss. It recovered retained artifact `12558567452` from completed default run
`40090736520`, checked the artifact-service SHA-256 and internal snapshot manifest,
refreshed all 5,271/5,271 records, and completed actual dependency analysis. It
saved `dc-data-v1-Linux-X64-dc13.0.0_40137910686-1` and retained snapshot artifact
`12578102443`. Record-processing rejection and required security/publication gates
passed without suppression or stale-data bypass.

**Still open:** fresh cross-run warm reuse on an eligible ordinary default-branch
run: restore completed data, skip the NVD refresh because it is still within the
240-minute window, and actually run analysis. Run `40137910686` skipped refresh
only during analysis **after its own update**, so it is retained-recovery/refresh
proof, not that cross-run warm-reuse proof. PR555 supplied fresh-cache PR-run
evidence, but that is not a separate default-branch run. Docs-only default run
`40117220432` skipped the security workload and does not qualify either.

Keep this item open with the linked
[Go/NVD qualification](issue-20261021-115502-293-require-path-gated-agentless-go-tests-and-vulnerab.md).
Use only eligible ordinary CI runs; do not trigger artificial runs, delete shared
caches, or start a future-window watcher to obtain the remaining evidence.

---
<!-- id: issue-20260908-173331-804-investigate-dependency-check-nvd-record-processing  file: issue-20260908-173331-804-investigate-dependency-check-nvd-record-processing.md -->
