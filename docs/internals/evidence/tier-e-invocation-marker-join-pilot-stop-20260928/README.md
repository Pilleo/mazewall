# Tier E marker-ID join feasibility pilot — 2026-09-28

**`INCOMPLETE`; the only predeclared 200-operation full-stream pilot stopped.**
Clean `fast-master` source `42211aa60f5ff07fcb86e870f2e6a43a4f7c8613`,
fingerprint `47303c0359f3ae4d2db7b87dd4c38869065dc26d3cf49bc055637d7c06b40983`,
XanMod `7.2.8-x64v3-xanmod1`, rootful image
`sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56`,
native agent SHA-256 `05f0f2a768b66b0659347f265f0db80b37a9b0acda0e0e0a60c3fba513ecd5ff`.
The workload ran on CPU 0 (13 existing JVM threads verified before gate
release), with the collector on physically separate CPU 2. The profile
requested exactly one dictionary/full-stream fork of 200 `openat` calls
at 1,000/s, benchmark-only raw/main invocation-ID auditing, and no host
noise. `/proc/sys/kernel/bpf_stats_enabled` remained `0`.

The original target-authored 200-FD manifest, native `MZIF` sidecar,
invocation-token sidecar, and original version-five FD-stage sidecar are
complete and hash-bound. Every declared ID has **exactly one native marker
ID, one raw token, one main token, and one raw/main FD-stage increment**.
Raw and main each counted 200 declared syscalls; all 200 observed raw
target events ran on CPU 0, and exact admission and ring retention were
complete with zero ring loss. This establishes that the new token join
worked for these 200 IDs. It does **not** turn the terminal green.

Both programs also reported **three target `openat` entries while armed
with zero marker token** (failure keys 11/12 each 3), and each independent
pre-decode target count was **203**. These extra observations are consistent
with ambient target openat traffic, but the predeclared contract required
all witness failure counters to be zero, so the fork correctly stopped
`INCOMPLETE`. Raw/main installed-program recursion-miss deltas were 3/5;
they remain program-wide, not specific to any declared ID. No retry or
full 10,000-operation experiment was started under this plan. A subsequent
contract may distinguish ambient residual events only with a separately
tested conservation rule that also rejects *any* missing declared token;
this terminal is not retroactively reclassified.

The original plan, profile, source/image/native identities, child log,
original manifest, complete native/per-ID files and recovery receipt are
in `raw-pilot.tar.gz`; `SHA256SUMS` seals the archive and this report.
The exact runner
`tier-e-trustworthy-d82abefd-1efa-4da2-acc6-3301d0b2f04c-runner`
was verified stopped with exit code 1; volume
`tier-e-trustworthy-d82abefd-1efa-4da2-acc6-3301d0b2f04c-artifacts`
remains retained. Host originals are `/tmp/tier-e-invocation-witness-42211aa6.pilot*`.
`policy_eligibility=false`, `REJECT_DEFAULT_PROMOTION`; the unaudited
five-pair and native timing gates remain separate.
