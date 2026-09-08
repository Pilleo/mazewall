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

## Modernization reference run

- Host: Linux 7.1.13 x86-64
- JDK: Oracle GraalVM 25.0.2
- Gradle: 9.6.1
- Strict host gate: 79 seconds to populate the configuration cache; 7 seconds
  warm with 50 of 51 actionable tasks up-to-date
- Full merge gate: 304 seconds to populate the configuration cache; 8 seconds
  warm with 172 of 175 actionable tasks up-to-date

The warm full gate reported `Configuration cache entry reused`. A tracked-state
snapshot taken immediately before and after that gate was byte-for-byte equal.
The task-graph contract is also enforced by the BuildSrc TestKit suite and
`scripts/check_gradle_lifecycle.sh`.
