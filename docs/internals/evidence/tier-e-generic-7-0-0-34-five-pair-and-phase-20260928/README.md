# Tier E same-host Ubuntu generic-kernel comparison — 2026-09-28 UTC

**Five-pair unaudited correctness: `COMPLETE` on `7.0.0-34-generic`.**
The original Kotlin `tierENodeNoiseQualification` task ran once on a
fresh checkout of previously measured `fast-master` commit
`fbbaad44bb2be272f2be863a4a049998a8894903`, using the unchanged
10,000-operation/mode, 1,000/s, five-pair AB/BA acceptance profile.
Raw-entry and exact-admission audits and the invocation witness were
disabled. The new [generic-kernel plan](./five-pair-raw.tar.gz) fixed
the actual booted kernel/config, image, native agent and CPUs *before*
acquisition; it did not resume the prior XanMod attempt.

| Pair | Order | Control full / unique | Noisy full / unique |
| --- | --- | --- | --- |
| 1 | control, noisy | 10,000 / 10,000 | 10,000 / 10,000 |
| 2 | noisy, control | 10,000 / 10,000 | 10,000 / 10,000 |
| 3 | control, noisy | 10,000 / 10,000 | 10,000 / 10,000 |
| 4 | noisy, control | 10,000 / 10,000 | 10,000 / 10,000 |
| 5 | control, noisy | 10,000 / 10,000 | 10,000 / 10,000 |

All **20 source-volume and guest terminals completed** and reconciled
the exact operation oracles. No reached fork reported missing, wrong,
duplicate or unclassified declared operations, or ring loss. Unique-edge
forks each retained one acknowledged representative and 9,999 explicit
suppressions. Each noisy arm preserved a target-PID-bound attachment
marker and distinct three-second host-noise samples while the **first
full-stream fork** was active; the protocol does not prove overlap with
the later unique-edge fork.

Every target retained the same clean source fingerprint
`4c83180fc562f9b430666ede7712b9d63db92c9669ac89538ed37f009a69ae0a`,
source digest `194d8ba3e8c2bc8114584c5e4a6c4f29c0cb646a2867ddce4218a76524e24e8e`,
rootful image ID
`sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56`,
and Release native agent SHA-256
`05f0f2a768b66b0659347f265f0db80b37a9b0acda0e0e0a60c3fba513ecd5ff`.
Target CPU 0/core 0 and collector CPU 2/core 1 were separate; noise
used CPU 3. The running kernel was **Ubuntu `7.0.0-34-generic`** with
config SHA-256 `e75c0adab6020e72844d7431cf801a02b94ed32efc251c3b0fb80c021d065180`.
Direct raw-tracepoint and marker attachments completed on this kernel.
The pre-reboot [XanMod first-failure](../tier-e-five-pair-unaudited-cpu-pinned-stop-20260928/README.md)
remains independently **`INCOMPLETE`**; these kernel versions and
configurations differ, so the comparison does not identify a causal
XanMod patch.

**Separate native timing stability: three of three phase runs `COMPLETE`.**
Each run captured three full and three unique fresh 100-iteration native
CTest samples. Both modes passed the **unchanged version-two historical
GetStackTrace, raw-key, lookup/logical and total bounds** in every run.
Full `GetStackTrace` medians were **1,466,543 / 1,472,368 / 1,582,870 ns**;
unique medians were **1,427,842 / 1,512,199 / 1,783,267 ns**, all below
the unchanged unique limit **2,307,381 ns**. Exact source digest, image
ID, CPU set, JDK/compiler, workload-class SHA-256 and Debug native
agent SHA-256
`ff1e640c079de05ba407eab963cfb8939553e7f49390e60fdb20645faa3f790a`
were continuous across three distinct phase runner IDs. The Debug
timing agent intentionally differs from the Release correctness agent;
both used the same native source, image and generic kernel.

**Decision:** Ubuntu `7.0.0-34-generic` is a **candidate exact supported
environment** for this source and image. `policy_eligibility=false` and
`REJECT_DEFAULT_PROMOTION` remain in force; this receipt is not blanket
release certification. The other rootful merge gate, deferred Podman
parity and operator-controlled promotion decision remain separate.

`five-pair-raw.tar.gz` retains the predeclared plan, exact profile,
all five pair result rows, each of the 20 fork logs/source manifests,
noise samples, PID markers and terminals. `native-phase-raw.tar.gz`
retains the separate predeclared phase plan, unchanged baseline file,
all 18 native CTest logs and three phase identities/verdicts. Original
host artifacts remain at
`/home/leanid/Documents/tier-e-generic-kernel-qualification-20260928/`;
`SHA256SUMS` seals both archives and this report.
