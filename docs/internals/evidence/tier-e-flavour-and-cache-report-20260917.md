# Tier E flavour and cache report - 2026-09-17

## Verdict

The rootful matched benchmark completed successfully for the full flavour matrix and the
paired cache comparison. Both artifacts report `workload_correctness=PASS`, terminal success,
and `policy_eligibility=false`.

- **Keep dictionary/full-stream as the default.** It has the lowest median among the Tier E
  transport/emission combinations in this workload.
- **Do not adopt inline-per-invocation for performance.** Its full-stream median is 3.0% slower
  than dictionary/full-stream; its unique-edge median is 2.6% slower. These unpaired flavour
  differences do not establish a meaningful advantage for inline transport.
- **Do not enable unique-edge for speed.** Dictionary unique-edge is 3.2% slower than
  dictionary/full-stream. Unique-edge remains useful when the product needs acknowledged
  coverage representatives rather than an event stream.
- **Keep canonical cache reuse enabled.** It is a measured improvement in every Tier E flavour:
  all five-pair bootstrap 95% confidence intervals are strictly above zero.

These are host/workload-specific measurements, not average-application slowdown claims or a
policy decision. Tier E evidence remains ineligible for policy input.

## Reproducibility and scope

Both runs used the same captured source state, native agent, rootful image, kernel, physical-core
pair, oracle workload, and dimensions:

| Field | Value |
|---|---|
| Source revision | `334547e7c4f8db9638247ef949608720bf737dbc` |
| Dirty-tree fingerprint | `3292de71a803230d6e6a45894b7656f4ddce3d1424ba34b3031d878de9d5962a` |
| Native agent SHA-256 | `fb90363311f216e5e17b3e2ac41cd717aacf477eb4d5146aecf8785ae7bd55cd` |
| Image | `sha256:6c6a570f7a849a5ce61c7d9371990680e6cc67826887cefa52bbb76dc63496b8` |
| Kernel | `7.2.4-x64v3-xanmod1` |
| CPU set | `0,2` (distinct physical cores) |
| Workload | oracle, 100 operations/batch, 3 warm-ups, 10 measured batches, 5 rotated JVM forks |

The privileged measurement phase mounted the source tree read-only and ran from writable `/tmp`
scratch space. Raw records and manifests are retained in:

- [full matrix JSONL](tier-e-full-matrix-20260917.jsonl)
- [full matrix manifest](tier-e-full-matrix-20260917.manifest.json)
- [paired cache JSONL](tier-e-cache-paired-20260917.jsonl)
- [paired cache manifest](tier-e-cache-paired-20260917.manifest.json)

## Full flavour matrix

Median workload duration is per 100-operation measured batch. The no-agent baseline is
`326,674 ns` (3.27 microseconds/operation).

| Mode | Median ns/batch | Median microseconds/operation | Evidence |
|---|---:|---:|---|
| Dictionary/full-stream | 1,853,827 | 18.54 | Tier E complete, workload PASS |
| Dictionary/unique-edge | 1,912,580 | 19.13 | Tier E complete, workload PASS |
| Inline/full-stream | 1,909,338 | 19.09 | Tier E complete, workload PASS |
| Inline/unique-edge | 1,902,549 | 19.03 | Tier E complete, workload PASS |
| USER_NOTIF, no stack | 2,145,558,452 | 21,455.58 | workload PASS; whole-session evidence not applicable |
| USER_NOTIF, stack | 2,139,442,501 | 21,394.43 | workload PASS; whole-session evidence not applicable |

The Tier E transport/emission medians cluster tightly around 18.5-19.1 microseconds per
operation. The matrix contains five rotated forks but no paired confidence interval for a
cross-transport comparison; therefore it supports **no demonstrated inline or unique-edge speed
benefit**, not a claim that their small median differences are universally real.

USER_NOTIF is roughly three orders of magnitude slower than the Tier E workload medians on this
native-call-heavy oracle workload. Its stack option does not materially change that conclusion.

## Correctness accounting

Every Tier E fork passed workload correctness and reported complete whole-session evidence:

- zero ring loss, wrong-stack, unexplained-unknown, missing-operation, unresolved-operation, and
  duplicate-operation counts;
- 6,500 declared operations matched for each unique-edge flavour across five forks;
- 5 acknowledged representatives and 6,495 explicit collector `SUPPRESSED` decisions for each
  unique-edge flavour.

The last point is important: suppression is an explicit collector decision after acknowledgement,
not inferred from missing resolved records. It validates one acknowledged representative per fork
for the single logical edge exercised by this workload.

## Paired cache result

The cache comparison pairs enabled and disabled runs by rotated fork. Positive delta means the
cache-disabled run took longer. All bootstrap 95% confidence intervals exclude zero.

| Mode | Disabled median ns | Enabled median ns | Paired median saving ns | 95% CI ns | Approx. median reduction |
|---|---:|---:|---:|---:|---:|
| Dictionary/full-stream | 2,385,377 | 1,977,056 | 448,725 | 334,044 to 533,782 | 18.8% |
| Dictionary/unique-edge | 2,516,113 | 1,977,972 | 497,850 | 351,088 to 924,582 | 19.8% |
| Inline/full-stream | 2,565,569 | 1,831,388 | 675,369 | 578,352 to 5,946,560 | 26.3% |
| Inline/unique-edge | 2,545,679 | 1,868,054 | 724,418 | 122,382 to 918,145 | 28.5% |

Cache accounting corroborates the timing result. Across the five forks of each flavour, disabled
runs had `0` hits and `39,075` misses; enabled runs had `39,010` hits and `65` misses. All cache
variants retained complete Tier E evidence, with a maximum collector retained-state peak of
301,682 bytes.

## Decision

1. Keep dictionary/full-stream with canonical cache enabled as the performance default.
2. Retain unique-edge as a coverage/reporting mode, not as a speed optimization.
3. Retain inline-per-invocation as an opt-in diagnostic transport, not as a performance option.
4. Do not prioritize incremental dictionary transport or USDT based on these results; neither has
   a demonstrated advantage over the cached default on this workload.

