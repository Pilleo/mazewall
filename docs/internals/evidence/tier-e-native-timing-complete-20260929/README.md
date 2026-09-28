# Tier E native timing diagnosis: source-matched readiness and 36-fork A/B/C — 2026-09-29

**Diagnostic `COMPLETE`, qualification unchanged.** From a clean clone of
committed `fast-master` `1678f33d5efdc9bf64a50717165932bb958f8043`,
source digest `a4219212fd5185cb73a42f6eaeaa47f2e00113f660ca9c21e3ffeb95d6e1e7df`,
the separately owned six-fork readiness ran first and returned
`READINESS_COMPLETE`. Only after that receipt's source, plan, all six
raw samples, telemetry and byte-matched Docker export were independently
checked did one new fixed-budget **36-fork** A/B/C diagnostic start.
Its 36 fresh 100-iteration JVM forks and all 36 observed rows completed
under the predeclared order and timeouts. Both plan and schedule hashes
remained unchanged after acquisition, and the runner/volume retired only
after every sample and telemetry export matched its host original.

The exact environment was Ubuntu `7.0.0-34-generic` with config SHA-256
`e75c0adab6020e72844d7431cf801a02b94ed32efc251c3b0fb80c021d065180`,
immutable image ID
`sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56`,
container CPUs `0,2`, JDK Temurin `25.0.4+7` and compiler Ubuntu
`15.2.0`. The retained original agent (A) SHA-256 was
`ff1e640c079de05ba407eab963cfb8939553e7f49390e60fdb20645faa3f790a`;
both the disabled (B) and instrumented (C) diagnostic arms used one
agent SHA-256
`9dafc1495eb42e7c554637b748087fcb7cc42e286d69fa8165776565aa923e76`.
Every reached JVM reported 514 captures, zero stack failures and
504 canonical-cache hits / 10 misses. No sample introduced a cgroup
throttled period; each had positive container CPU usage, effective
CPU set `0,2` and recorded host pressure/frequency observations.

**Descriptive `GetStackTrace` wall-phase totals:** six observations per
arm/mode, medians of the six *raw per-JVM 514-capture sums* (ns):

| Mode | Original A | New agent, diagnostics off B | Same agent, clocks on C |
| --- | ---: | ---: | ---: |
| Full | 1,632,171.5 | 1,700,150 | 2,432,495.5 |
| Unique | 1,659,733.5 | 1,679,464 | 2,688,377 |

A/B totals were close in this fixed sample; that is not a statistical
equivalence proof. C materially raised the reported wall-phase sum
(roughly 43%/60% above B medians for full/unique). In C, median
elapsed-minus-thread-CPU capture sum was only **16,693 ns/full** and
**24,818 ns/unique**, but the two clocks are sampled sequentially and
their measurements include the extra probes. C/unique fork 19 had a
**361,067 ns** elapsed-minus-CPU gap and one involuntary switch; C/unique
fork 33 measured **4,011,679 ns** in the existing wall-phase counter
with a **30,151 ns** clock gap and zero reported capture switches.
Neither observation alone establishes why the earlier, different-source
unique timing phase failed. This bounded comparison shows that the
opt-in diagnostic significantly perturbs the phase measurement, so its
C totals cannot be substituted for uninstrumented release timing.

The original unaudited correctness series and historical
`8a0d6d32` first-phase `INCOMPLETE` verdict are separate. This diagnostic
does **not** constitute the exact-source three-run native timing gate,
the pinned-image rootful aggregate or a revised timing acceptance
contract. The unchanged historical unique `GetStackTrace` limit remains
2,307,381 ns; `policy_eligibility=false` and
`REJECT_DEFAULT_PROMOTION` remain in force. The earlier
[`3775ab65` readiness-complete / campaign-stopped receipt](../tier-e-native-timing-readiness-and-series-stop-20260929/README.md)
remains `INCOMPLETE` and is not upgraded by this new-source completion.

`readiness-raw.tar.gz` and `diagnostic-raw.tar.gz` preserve the original
plans, immutable schedules and preflight hashes, all 42 raw JVM logs,
per-fork pre/post host telemetry, exact original agent copies,
source/agent/image provenance, terminal receipts and byte-matched
Docker exports. Complete originals remain under
`/home/leanid/Documents/tier-e-native-timing-readiness-1678f33d-20260929/`
and `/home/leanid/Documents/tier-e-native-timing-diagnostic-1678f33d-20260929/`.
The historical failed phase runner and both earlier diagnostic
stopped runners remain separately retained and untouched.
