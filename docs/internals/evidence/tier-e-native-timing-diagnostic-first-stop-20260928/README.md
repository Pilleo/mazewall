# Tier E bounded native timing diagnostic: first-fork controller stop — 2026-09-28

**`INCOMPLETE`; no timing attribution or qualification result.** The
operator-approved, non-qualifying 36-fork A/B/C diagnostic launched once
from a clean clone of committed `fast-master`
`005086b9f44e1419adf5ffc7fb33ed4ce8cb1af3` (source digest
`fca8801de88ce2bd89ddfb9559876d9072864681c761741478bc9a8833fc7306`).
The planned fixed schedule and source/image/binary contract were checksummed
**before** the first measured JVM sample. The original Debug agent hash
`ff1e640c079de05ba407eab963cfb8939553e7f49390e60fdb20645faa3f790a`
was verified from the separately retained old phase runner; the newly built
diagnostic agent hash was
`9dafc1495eb42e7c554637b748087fcb7cc42e286d69fa8165776565aa923e76`.
The pinned rootful image, Ubuntu `7.0.0-34-generic` kernel config, CPU set
`0,2`, and byte-identical `AgentWorkload.class` passed preflight.

Only **sample 01, arm A/full** ran. Its JVM exited successfully with 514
captures, zero stack failures, 504 cache hits / 10 misses, a 36,341,546 ns
workload and `GetStackTrace` total 1,600,105 ns. It emitted the native
capture summary plus 12 distinct binding-detail lines. The new controller's
parser erroneously selected **all** `mazewall-stack-agent:` lines when it
expected exactly one summary; it rejected the log as `Missing or duplicate
therefore remained `INCOMPLETE` after the **first fork**. Samples 02–36 were
not run. The first sample is insufficient for any A/B/C or timing-cause
comparison. No native acceptance phase or rootful aggregate ran.

The stopped, separately owned runner
`tier-e-timing-f2620dc9-6905-450b-bf26-d7f266748ab6-runner` and its
`-artifacts` volume remain retained. The original failed qualification
runner and volume remain separate and untouched. The sealed raw archive
contains the predeclared plan, schedule, preflight hash, original agent
copy, first log, terminal and ownership recovery receipt. Complete originals
remain at `/home/leanid/Documents/tier-e-native-timing-diagnostic-005086b9-20260928/`.
`sha256sum -c preflight.sha256` passed after the stop.

A regression test now reproduces the parser defect using a real binding
detail line; the controller selects only `native-binds=` summary lines in
the **later** source revision. Later instrumentation also persists pre/post
host telemetry before parsing. Those changes do not repair this source's
`INCOMPLETE` receipt. Do not restart, extend, or pool the stopped series:
a new source revision and independently predeclared plan would be required
for another attempt. The historical 2,307,381 ns unique bound, previous
`8a0d6d32` timing failure, `policy_eligibility=false`, and
`REJECT_DEFAULT_PROMOTION` remain in force.
