# Tier E CPU-matched unaudited/witness comparison — 2026-09-28 UTC

**`NOT_REPRODUCED_WITHIN_FIXED_BUDGET`: all four predeclared forks
completed, without extending the series.** This is not the original
five-pair unaudited qualification and does not identify the cause of
earlier intermittent missing operations. Production eligibility remains
false and `REJECT_DEFAULT_PROMOTION` remains in force.

The frozen plan (retained in `arm-1.tar.gz`) selected committed `fast-master` source
`afc1ed222202cbb60a87f6b4bca2290715340b3a`. All four source
manifests reported zero dirty inputs and the same fingerprint
`4c83180fc562f9b430666ede7712b9d63db92c9669ac89538ed37f009a69ae0a`,
XanMod `7.2.8-x64v3-xanmod1`, Docker image
`sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56`,
and native agent SHA-256 `05f0f2a768b66b0659347f265f0db80b37a9b0acda0e0e0a60c3fba513ecd5ff`.
Workload CPU 0/core 0 and collector CPU 2/core 1 remained distinct.
Each target JVM reported 13 existing threads CPU-0-pinned before gate
release. The settings fixed 10,000 operations/fork at 1,000/s, one
batch, zero warmups, one selected mode and no host noise.

| Ordered fork | Mode | Original target IDs | Declared operations | Diagnostic integrity | Qualification |
| --- | --- | ---: | --- | --- | --- |
| 1 | Dictionary/full, audits disabled | 10,000/10,000 | PASS | DISABLED | UNVERIFIED |
| 2 | Dictionary/full, paired witness | 10,000/10,000 | PASS | PASS | UNVERIFIED |
| 3 | Dictionary/unique, audits disabled | 10,000/10,000 | PASS | DISABLED | UNVERIFIED |
| 4 | Dictionary/unique, paired witness | 10,000/10,000 | PASS | PASS | UNVERIFIED |

All four original target-authored FD manifests contain 10,000 distinct
IDs and their hashes match the fork receipts. No arm reported missing,
wrong, duplicated or unclassified declared operations or ring loss.
The unique-edge forks each retained **one acknowledged representative
and 9,999 explicitly suppressed** operations; no missing singleton
was inferred from suppression. Both paired-witness forks have 10,000
native invocation-ID/FD records with zero drops, 10,000 raw and main
pre-FD tokens, and complete original before/after FD-stage sidecars.
Their raw CPU count was `0:10000`. Each observed three extra target
zero-token `openat` calls per program; the per-ID join was complete and
their early counts were 10,003/10,003, satisfying the *predeclared*
conservation rule. Program-wide recursion-miss deltas remained nonzero;
they cannot be assigned to individual declared IDs.

**Decision:** The corrected comparison contract is suitable for an
explicit decision to start the **separate original five-pair unaudited
AB/BA gate**; it is not that gate's result. The earlier uninstrumented
10,000-ID CPU-verified failure and the later instrumented missing-
storage failure remain `INCOMPLETE`. Neither is retroactively upgraded
by these four positive forks. The unchanged native timing gate remains
separate and unverified for this source; Podman parity remains deferred.

This series has its own source and identity, separate from the earlier
`7d0c8453` [host-export failure](../tier-e-sustained-comparison-host-export-stop-20260928/README.md).
That guest exited successfully, but its export terminal remains
`INCOMPLETE` and its runner/volume stay retained. A regression-backed
host-only change produced the new `afc1ed22` contract. `arm-1.tar.gz`
through `arm-4.tar.gz` contain the exact result JSONL, source manifest,
target-authored FD list, child/host logs, terminal and complete paired
sidecars where enabled. `SHA256SUMS` verifies this report, summary,
and all four arm archives. Original host artifacts remain under
`/tmp/tier-e-comparison-afc1ed22.result/`.
