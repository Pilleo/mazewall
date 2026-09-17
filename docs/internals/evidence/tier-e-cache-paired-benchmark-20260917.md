# Rootful Tier E paired-cache benchmark, 2026-09-17

## Environment and verdict

- Rootful Docker image: `mazewall-tier-e-rootful:perf-20260916`.
- Native build: `Release`; JDK: OpenJDK 25.0.4.
- Workload: five rotated JVM forks, three warm-up batches, ten measured batches of 100 operations.
- Result: `workload_correctness=PASS`, `policy_eligibility=false`, 450 samples, and a successful
  terminal record.
- Whole-session completeness: zero complete forks in every Tier E variant. The controlled workload
  passed, while background `NO_ACTIVE_INVOCATION` observations remained explicitly incomplete.

## Median workload duration

| Variant | Cache disabled | Cache enabled | Paired median disabled-minus-enabled |
| --- | ---: | ---: | ---: |
| Dictionary/full-stream | 13.984 ms | 12.882 ms | 0.762 ms |
| Dictionary/unique-edge | 14.426 ms | 12.379 ms | 2.015 ms |
| Inline/full-stream | 14.347 ms | 11.852 ms | 3.172 ms |
| Inline/unique-edge | 13.320 ms | 12.031 ms | 0.829 ms |

The five-pair bootstrap 95% intervals all include zero. This run demonstrates the cache toggle,
preserved exact-workload verdict and retained metrics, but it is not sufficient evidence to accept a
new cache-path optimization. The paired runner must be repeated after a candidate change and compared
against the same benchmark profile.

## Retained artifacts

- `tier-e-cache-paired-benchmark-20260917.jsonl`: metadata, raw child output, all fork records,
  samples, summary and terminal record.
- `tier-e-cache-paired-benchmark-20260917-build.log`: native Release build and isolated Gradle build.
- `tier-e-cache-paired-benchmark-20260917-runner.log`: rootful benchmark runner output.
