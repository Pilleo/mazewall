# Tier E version-four third verifier — architecture stop, 2026-09-27 UTC

**`INCOMPLETE`, source-dirty; no clean-source series was started.** After two
separately retained, demonstrated instrument-only failures and focused
red/green fixes, a final predeclared changed-source rootful verifier stopped
on its first full-stream fork. The main operation oracle and exact admission
were 10,000/10,000 with zero ring loss. The independent raw audit observed
**9,997/10,000** target IDs on CPU 1; its bounded report names missing
`7746`–`7748`. Armed raw/main target totals were 10,000/10,003, and aggregate
raw/main pre-session descriptor totals were 9,997/10,000. All six explicit
pre-gate read/lookup failure counters were zero. The raw/main after-session
totals were likewise 9,997/10,000. These totals narrow the **aggregate**
gap to at or before the raw pre-session checkpoint, but do not prove which
specific per-ID branch diverged or that the raw BPF program was never run.

**The version-four bounded classifier in this source is not decision-quality.**
It labeled unrelated repeated target FDs `MAIN_HASH_GATE` because it treated
every low32 count other than one as missing, rather than distinguishing a
zero. Those lower-numbered ambient samples filled the first 16 slots, so
the output contains **no per-ID pre-gate stage for `7746`–`7748`**. Do not
quote the `pre_gate_audit_boundary` field as causal evidence from this run.
This is a diagnostic-design defect exposed by the first real raw shortfall,
not grounds for another ad hoc kernel attempt.

The terminal was `INCOMPLETE`; unique mode was not attempted. The exact
stopped runner `tier-e-trustworthy-e05d7aaa-c539-48cf-9e4d-8eed4f09e8ef-runner`
and matching `-artifacts` volume were verified retained. `raw-verifier.tar.gz`
preserves the predeclared final-attempt plan, source-dirty manifest, first
fork output and recovery receipt. `SHA256SUMS` seals this report and raw
archive; originals remain under `/tmp/tier-e-v4-guarded-1790547916*`.
The predeclared stop rule now requires an architectural review of the
benchmark-only diagnostic and its failure representation before another
code change or kernel run. The five-pair **unaudited** default qualification,
native timing gate, and policy promotion remain `INCOMPLETE`.
