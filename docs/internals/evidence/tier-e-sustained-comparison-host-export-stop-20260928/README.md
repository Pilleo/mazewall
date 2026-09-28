# Tier E four-arm comparison: first host-export stop — 2026-09-28

**`INCOMPLETE` on arm 1, full-stream with both audits disabled.** The
predeclared four-arm series stopped; full witnessed and both unique-edge
arms did not run. Clean `fast-master` source
`7d0c84536bf3944db07d1fa6f67f540dff69c8a0`, source fingerprint
`bf3d457b40a4866a2e40ee11c69861d1dd428fa086483bfb3eb415997a747269`,
XanMod `7.2.8-x64v3-xanmod1`, rootful image
`sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56`,
native agent SHA-256 `05f0f2a768b66b0659347f265f0db80b37a9b0acda0e0e0a60c3fba513ecd5ff`,
workload CPU 0/core 0, collector CPU 2/core 1. The requested workload
was 10,000 operations at 1,000/s, zero warmups, one fork, with
`policy_eligibility=false` and `REJECT_DEFAULT_PROMOTION`.

The child reported **10,000/10,000 matched declared IDs**, zero missing,
duplicate, wrong or unclassified operations, `evidence_complete=true`,
and no ring loss. Its independently copied target-authored manifest has
10,000 distinct FDs; the pre-gate receipt confirms 13 target JVM threads
were CPU-0-pinned before release. The three reported axes were
`declared=PASS`, `diagnostic=DISABLED`, `qualification=UNVERIFIED`.
These are **child observations**, not a successful source-volume terminal.

The newly added host comparison verifier failed at source export: it
selected every line starting `tier-e-kernel-smoke:`, thereby treating
the earlier `attachment_ready` line and the final result as duplicates
instead of identifying the versioned final report. The source volume
was not retired; `terminal.json` remains `INCOMPLETE` and the original
failure is not retroactively upgraded by any corrected offline parse.

`raw-arm1.tar.gz` contains the exact predeclared plan, all four pinned
profiles, original target manifest, guest and host logs, source/image
manifest, JSONL, terminal and recovery receipt. `SHA256SUMS` seals the
archive and this report. Host originals remain under
`/tmp/tier-e-comparison-7d0c8453.*`. Exact runner
`tier-e-trustworthy-b60e5093-3c5d-4f3c-8ce0-70f8f915c0f3-runner`
exited 0 (the guest completed); it and the volume with the same prefix
ending `-artifacts` were retained because independent host export failed.
The volume remains available for
recovery. A regression-backed host-only fix requires **a new committed
source and separately predeclared series**; these arms are never resumed.
