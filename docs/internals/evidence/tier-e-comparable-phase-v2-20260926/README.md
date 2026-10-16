# Version-two Tier E native phase gate — 2026-09-26

**Qualification:** A clean rootful Docker gate completed with version-two
native phase evidence for both dictionary/full-stream and dictionary/unique-edge.
This is a corrected comparison of the same work, **not** a retroactive pass of
the historical version-one raw-key gate, a policy input, or a change to
`REJECT_DEFAULT_PROMOTION`.

## Contract and immutable bounds

The retained `profiler-native/tier-e-benchmark-baseline.env` is unchanged.
Version one remains available for historical output and rejects version-two
records. Version two requires three complete, mode-bound samples per arm:
514 stack captures, zero stack failures, positive native/proxied binds and
workload duration, active cache hits/misses summing to the 514 captures, and
separate positive measurements for stack capture, raw-key construction,
canonical-cache lookup and logical metadata resolution. Key construction is
checked against the old per-mode raw-key limit; stack capture retains its old
per-mode limit. The median of each sample's **non-overlapping sum** of key,
lookup and logical metadata must stay below 125% of the sum of the two
unchanged historical baseline values, separately for full and unique.

The historical total and version-two limit are respectively **10,730,232 /
13,412,790 ns** for full-stream, and **1,695,220 / 2,119,025 ns** for
unique-edge. Neither a missing cache measurement nor a zero-hit/zero-miss
mode can pass by moving costs elsewhere. The native agent's legacy
`raw_key_ns` is now key construction only; its new
`canonical_cache_lookup_ns` includes hash/equality, weak-class guards and
generation validation. The benchmark's attribution behavior is unchanged.

## Clean rootful gate

`TIER_E_CONTAINER_ENGINE=docker MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock
TIER_E_ARTIFACT_DIR=<fresh-output> bash scripts/run_tier_e_rootful_gate.sh`
completed with `terminal.json.success=true`,
`benchmark-gate-contract.txt=2`, `benchmark-gate-status.txt=PASS`, and
`policy_eligibility=false`. Profiler fresh-JVM and native CTest stages ran
before the phase gate; their raw logs and native-backed XML results are
retained in `rootful-gate-raw.tar.gz`.

| Mode | key median | lookup median | logical median | median per-sample total | fixed total limit |
|---|---:|---:|---:|---:|---:|
| full-stream | 14,742 ns | 852,611 ns | 567,678 ns | **1,355,282 ns** | 13,412,790 ns |
| unique-edge | 14,925 ns | 812,446 ns | 517,404 ns | **1,344,775 ns** | 2,119,025 ns |

The total is computed per sample and then median-selected; it need not equal
the sum of independently selected phase medians. The rootful result checks
both modes, retains the old baseline numbers, and does not count 100 native
operations as 100 independent forks.

The historical `check_tier_e_benchmark.sh` still exits nonzero on version-two
native output (`Legacy phase gate cannot qualify the v2 measurement contract`).
That rejection is retained in `legacy-v2-denial.txt`.

## Pinned fifteen-pair diagnostic comparison

`paired-decision.json` and `paired-raw.tar.gz` retain **15 predetermined,
alternating** historical/current Debug CTest pairs for each mode under one
rootful image/JDK/compiler/kernel/CPU set (`0,2`). All 60 CTest arms exited
zero, captured 514 stacks with zero failures, and the current arm had positive
cache hits and misses summing to 514. The historical source was detached at
`ce31883b1`; current source bytes and image/native identities are listed in
`paired-raw.tar.gz` (`source-sha256.txt`, `identities.txt`).

| Mode | historical median total | current median total | paired median delta | deterministic paired-bootstrap 95% interval |
|---|---:|---:|---:|---:|
| full-stream | 7,280,053 ns | 1,006,450 ns | -6,293,877 ns | [-7,364,122, -5,883,614] ns |
| unique-edge | 1,383,245 ns | 1,005,050 ns | -342,416 ns | [-519,385, -211,165] ns |

These are native **phase** comparisons and do not qualify application-wide
slowdown or default-policy promotion. The old/new CTest commands are recorded
in the archive; the current test names an output definition file while the
historical one does not. The prior isolation record explains why the old
raw-key-only values cannot be compared directly:
[`tier-e-raw-key-comparability-20260926`](../tier-e-raw-key-comparability-20260926/README.md).

Every file and archive in this directory is sealed by `SHA256SUMS`.
