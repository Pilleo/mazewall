# Reviewed version-four audit: kernel-positive, export-binding stop — 2026-09-27 UTC

**Development verifier `INCOMPLETE`; no qualification.** One predeclared
source-dirty rootful Docker target used 10,000 operations at 1,000/s in
both full and unique modes, benchmark-only raw and exact audits, and CPUs
`0,1`. Both child forks recorded 10,000/10,000 raw target IDs and exact
submissions, 10,000/10,000 raw/main pre- and post-session per-ID counts,
zero pre-gate read/lookup failures, 30,000/30,000 kernel exact/ring-ready
records and zero ring loss. Each child atomically published all **10,000
declared-ID rows** to a version-four sidecar and logged its SHA-256.

The host export then stopped `INCOMPLETE`: its first verifier compared the
sidecar's **workload TGID** to `fork-output.target_pid`, which is the
collector/launcher PID, not the admitted workload PID. For example, the
full fork's launcher PID was `3549434`, while the sidecar and child report
both recorded workload PID `3549459`. A test failed before and passed after
binding the sidecar header to the child report's `workload_pid`, while
still requiring the fork's variant and number. The corrected host verifier
was **replayed against these exact retained exported files without a new
kernel run** and passed hash, row-count, order, schema and identity checks
for both forks. The original aggregate terminal remains `INCOMPLETE`;
the replay cannot retroactively change that decision.

The runner `tier-e-trustworthy-e36ed687-3e75-4b87-884a-10dce50f1ed6-runner`
and matching `-artifacts` volume were retained. `raw-verifier.tar.gz`
contains the predeclared plan, manifests, both child logs, complete per-ID
files, JSONL, stopped terminal, recovery receipt and focused offline replay
log. `SHA256SUMS` seals the small receipts and archive; originals remain at
`/tmp/tier-e-v4-reviewed-1790551307*`. The different source-dirty
fingerprint means this is a **development verifier**, not a clean pair,
default-policy promotion, or a replacement for the five-pair unaudited
oracle. A separately predeclared clean-source result is still required.
