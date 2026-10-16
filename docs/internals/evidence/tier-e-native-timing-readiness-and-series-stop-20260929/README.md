# Tier E exact-source timing diagnostic: readiness passed, series stopped at fork 34 — 2026-09-29

**The separately bounded six-fork readiness completed; the ensuing
36-fork diagnostic is `INCOMPLETE`.** Both were acquired from a clean
clone of `fast-master` commit
`3775ab654e43276d91889ccf5fdd5df2b6f52c00`, source digest
`628bd249d419e080ae758635823477db88f46938559a86b3292d887c0389fd84`,
Ubuntu `7.0.0-34-generic` (config SHA-256
`e75c0adab6020e72844d7431cf801a02b94ed32efc251c3b0fb80c021d065180`),
image ID
`sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56`,
and container CPU set `0,2`. The old Debug agent SHA-256 was
`ff1e640c079de05ba407eab963cfb8939553e7f49390e60fdb20645faa3f790a`;
the new disabled/instrumented agent SHA-256 was
`9dafc1495eb42e7c554637b748087fcb7cc42e286d69fa8165776565aa923e76`.
All six readiness modes and all reached campaign forks used the identical
`AgentWorkload.class`, with 514 captures, zero stack failures, and
504 cache hits / 10 misses per fork. Both schedules and all binary IDs
were fixed and checksummed before each lane's first timed sample.

**Readiness `READINESS_COMPLETE`, separately non-qualifying:** A/full,
A/unique, B/full, B/unique, C/full and C/unique each completed a fresh
100-iteration JVM. The `C` clocks reported 514/514 valid captures and
clock-pair probes; every reached readiness fork had valid pre/post
cgroup and host CPU-pressure evidence with no cgroup throttling delta.
The independently checked owner export matched every raw sample and
telemetry file byte for byte. The readiness runner/volume were retired
only after verified export. This receipt admitted only the separately
predeclared diagnostic attempt, not qualification.

**Diagnostic `INCOMPLETE`:** The fixed 36-row six-block A/B/C schedule
reached 34 JVM forks; 33 were accepted into `observations.jsonl`.
The successfully executed **fork 34, C/full**, reported 514 completed
and zero invalid capture-clock readings, a workload of 35,555,084 ns,
`GetStackTrace` phase of 2,081,191 ns, diagnostic elapsed capture of
**1,726,964 ns**, and diagnostic thread CPU capture of **1,734,675 ns**.
The independent clock intervals crossed by **7,711 ns** in aggregate.
The controller's `cpu <= elapsed` assertion rejected the sample as
`Invalid capture CPU/elapsed timing`. That ordering is **not** a sound
validity requirement for separately sampled wall and thread-CPU clocks;
the raw measurements are retained uncorrected. The campaign stopped
on that first integrity check per plan; forks **35–36 were not run**.
The last fork's pre/post telemetry and raw JVM log were retained even
though the parser did not publish a row. Its cgroup CPU usage advanced,
with zero new throttled periods, as did the preceding 33 forks.

**Descriptive subset only:** The first five complete blocks supplied five
full and five unique samples per arm. Median `GetStackTrace` phase
totals (ns) in that subset were:

| Mode | Original A | New agent, disabled B | New agent, clocked C |
| --- | ---: | ---: | ---: |
| Full | 1,444,278 | 1,444,544 | 2,015,921 |
| Unique | 1,504,406 | 1,460,329 | 2,178,311 |

The instrumented phase includes extra clock and rusage reads. C's
median elapsed-minus-thread-CPU capture sum across those five blocks
was 14,087 ns/full and 17,296 ns/unique. These clocks are sampled
sequentially and include measurement overhead; they cannot by
themselves explain the earlier different-source 3,157,189 ns unique
timing failure. No threshold, baseline, historical verdict or release
eligibility is changed by this subset.

The original terminal and artifacts remain under
`/home/leanid/Documents/tier-e-native-timing-readiness-3775ab65-20260929/`
and `/home/leanid/Documents/tier-e-native-timing-diagnostic-3775ab65-20260929/`.
The failed diagnostic runner
`tier-e-timing-a3342104-3c7d-4bbb-a990-859566467194-runner` and
its matching `-artifacts` volume remain retained (the historical phase
runner and earlier stopped diagnostic runner are separate and untouched).
The sealed archives preserve all six readiness forks, all 34 reached
campaign JVM logs, full host telemetry, original agent copies, source
plans/schedules, ownership receipts and verified exports.

**Later-source repair:** A regression test with byte-identical fork-34
raw input removes only the invalid ordering assertion while retaining
positive clock, completeness, workload and identity checks. That
change belongs to a *different* source revision; it does not resume,
reclassify or extend this `INCOMPLETE` attempt. A future experiment
would need its own source-bound plan and readiness decision.
`policy_eligibility=false`, `REJECT_DEFAULT_PROMOTION`, and the
historical unique `GetStackTrace` limit of 2,307,381 ns still apply.
