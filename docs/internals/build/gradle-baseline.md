# Gradle build baseline

Use `./scripts/measure_gradle.sh --label <name>` to capture cold, warm-prime,
and warm measurements for `help`, `unitCheck`, and `build`. Reports are written
only below `build/reports/gradle-baseline/<name>/`; CI uploads that directory as
an informational artifact.

The CSV records elapsed time, peak resident memory, configured-project lines,
actionable tasks, build-cache hits, and configuration-cache reuse. Compare runs
on the same machine and JDK. A change is acceptable when warm `help` reuses the
configuration cache, warm `unitCheck` does not regress by more than 10%, and no
verification gate disappears from the lifecycle graph without an explicit
correctness rationale.

## Modernization comparison

- Host: Linux 7.1.13 x86-64
- JDK: Oracle GraalVM 25.0.2
- Gradle: 9.6.1
- Baseline revision: `3a0d0115`
- Modernized revision: `47360867`

Both revisions were measured from already-built worktrees on the same host. The
`cold` mode disables configuration cache but retains task outputs, isolating
configuration and graph overhead from compilation noise.

| Task | Mode | Before | After | Actionable tasks before | After |
| --- | --- | ---: | ---: | ---: | ---: |
| `help` | cold | 5s | 2s | 4 | 11 |
| `unitCheck` | cold | 2s | 2s | 108 | 60 |
| `build` | cold | 11s | 6s | 224 | 185 |
| `build` | warm-prime | 11s | 1s | 221 | 175 |
| `build` | warm | 1s | 2s | 221 | 175 |

The important stable changes are the 44% smaller host-gate graph and 17% smaller
full-build graph. Cold configured build time fell by 45%; the one-second warm
samples are below the precision where their ordering is meaningful. Peak RSS
remained effectively flat at roughly 240 MiB.

The warm full gate reported `Configuration cache entry reused`. A tracked-state
snapshot taken immediately before and after that gate was byte-for-byte equal.
The task-graph contract is also enforced by the BuildSrc TestKit suite and
`scripts/check_gradle_lifecycle.sh`.

## Historical OWASP Dependency-Check cache observation

Pull-request run `34255417287`, attempt 1 (`ae809e89`), performed the expected
cold refresh: `dependencyCheckAnalyze` downloaded all 387,494 NVD records in
4m47s (17:21:53–17:26:40 UTC). `setup-gradle` then saved a 122 MB Gradle User
Home entry containing `dependency-check-data`.

Attempt 2 reran the identical revision. `setup-gradle` restored that exact 122
MB entry, including `dependency-check-data`; `dependencyCheckAnalyze` completed
in 16s (17:31:07–17:31:23 UTC) without logging a complete NVD-record download.
The vulnerability analysis and CVSS gate both remained active, and the rerun
completed successfully.

## Current security rollout and explicit-gate comparison

The older setup-gradle data-cache mechanism above is historical. Current security
has its own completed-data-only cache with exact membership verification before
analysis; see [testing strategy](../designs/core/testing-strategy.md). A saved data
snapshot is not a security scan or publication pass.

[Default run39483882935](https://github.com/Pilleo/mazewall/actions/runs/39483882935)
at512565a7 completed in38m49s with93.72 summed runner-minutes. Actual cold scanner
update took20m29s, security21m09s, core21m31s, optional acceptance28m31s, mutation
12m17s and publication10m12s. This is a cold-data operational baseline, not a
controlled warm comparison or guarantee for another hosted runner.

The selectively recovered explicit build-logic gate needs new real-suite memory,
local warm help/unitCheck and hosted first/warm observations before a numerical
benefit is claimed. Match scanner due/cold state and cache warmth; compare wall,
queue/setup/execute time and total runner minutes. Cached PIT/verifier paths are
not executed-workload memory witnesses; fresh native results remain required.
Warm unitCheck's existing10% regression guard applies to matched local observations,
not a49-minute cold download versus a fresh-cache scan or old subsecond measurements.

[Rollout evidence](../evidence/2026-10-20-ci-rollout.md) records bounds and open
warm/due, scan-coverage, rootful, non-PIT memory and registry-identity gaps. No
default benchmark rerun may implicitly republish SNAPSHOT packages without the
normal release approval.
