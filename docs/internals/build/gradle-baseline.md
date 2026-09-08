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

## OWASP Dependency-Check cache

Pull-request run `34255417287`, attempt 1 (`ae809e89`), performed the expected
cold refresh: `dependencyCheckAnalyze` downloaded all 387,494 NVD records in
4m47s (17:21:53–17:26:40 UTC). `setup-gradle` then saved a 122 MB Gradle User
Home entry containing `dependency-check-data`.

Attempt 2 reran the identical revision. `setup-gradle` restored that exact 122
MB entry, including `dependency-check-data`; `dependencyCheckAnalyze` completed
in 16s (17:31:07–17:31:23 UTC) without logging a complete NVD-record download.
The vulnerability analysis and CVSS gate both remained active, and the rerun
completed successfully.
