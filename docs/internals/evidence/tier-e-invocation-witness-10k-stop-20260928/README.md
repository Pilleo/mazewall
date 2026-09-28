# Tier E marker-ID 10,000-operation control — 2026-09-28

**`INCOMPLETE`, stopped at the only predeclared control's first full-stream
fork.** Unique-edge did not run; no arm was retried. Committed `fast-master`
source `8393bf7f879d8d7440eae80183d04d3fa2fb36d3`, clean source
fingerprint `0c54c2cebf15218203c7cb834483164daded4042aeec1f917517415f76a65ef3`,
XanMod `7.2.8-x64v3-xanmod1`, rootful Docker image
`sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56`,
native agent SHA-256 `05f0f2a768b66b0659347f265f0db80b37a9b0acda0e0e0a60c3fba513ecd5ff`.
The workload was startup-pinned to CPU 0 (13 target JVM threads verified
before gate release), with the collector on distinct physical core/CPU 2.
The profile retained 10,000 operations/mode at 1,000/s, complete audited
raw/main stage maps, bounded retention, and `bpf_stats_enabled=0`.

**All declared IDs joined exactly:** the original target-authored manifest
contained 10,000 distinct directory FDs. The versioned native `MZIF`
sidecar contained their 10,000 distinct published invocation IDs and
zero drops. Every one of those IDs had exactly one raw and one main
*pre-FD* token. Every original version-five FD-stage row had one raw and
one main pre/post count, one raw observation and one main exact
submission. All 10,000 raw target observations ran on CPU 0. Ring loss
and explicit pre-gate argument/lookup failures were zero. Raw/main
loaded program IDs were **1483/1482** and their exact byte digests are
bound into both complete sidecars.

**Why this control is still `INCOMPLETE`:** raw and main each observed
**10,004** target `openat` entries before the original FD gate. Their
armed witness-failure counters were **one missing task-storage state**
(keys 9/10) and **three zero-token states** (keys 11/12) per program.
Map insert/lookup failure keys 13/14 were zero. The predeclared
conservation rule permits residual zero-token entries only when all
declared IDs join exactly; it **does not permit missing task storage**,
which cannot be assigned to an individual declared syscall from this
aggregate alone. The runner correctly stopped and retained the source
volume. Program-wide raw/main recursion-miss deltas were **166/346**;
these do **not** attribute any declared ID or prove a recursion-guard
cause. That all declared IDs joined under this instrumented source
cannot be compared causally to the earlier uninstrumented incomplete
`253e4dda` result.

The separate, source-matched 200-operation [conservation feasibility
pilot](../../evidence/tier-e-invocation-marker-join-pilot-stop-20260928/README.md)
completed with all declared IDs exact and three conservable zero-token
calls per program. `pilot-pass.tar.gz` preserves that newer positive
pilot; the earlier `42211aa6` false pilot terminal in its own archive
remains unchanged. `raw-control.tar.gz` holds the predeclared 10,000-ID
plan and profile, full original manifest and both complete sidecars,
native and program hashes, raw child/host logs and recovery receipt.
`SHA256SUMS` seals both archives and the report. Host originals remain
under `/tmp/tier-e-invocation-witness-8393bf7f.*`.

Runner `tier-e-trustworthy-62f195cc-df73-485c-93e9-3de577b24f7c-runner`
was independently verified exited 1; volume
`tier-e-trustworthy-62f195cc-df73-485c-93e9-3de577b24f7c-artifacts`
remains retained. `policy_eligibility=false`,
`REJECT_DEFAULT_PROMOTION`; default five-pair unaudited qualification,
native timing and Podman parity remain separate.
