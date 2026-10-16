# Version-four post-session diagnostic guard — 2026-09-27 UTC

**Source-dirty verifier `INCOMPLETE`, first full-stream fork.** After the
previous register-eligibility correction, a separately predeclared run
counted 10,000/10,000 declared raw and main pre-session events, 10,000
raw after-session events, 10,000 exact submissions and matching armed
raw/main target aggregates of 10,002. Main post-session descriptor counts
were **33,045**, though main exact admission was 10,000. This was another
**probe defect**: the new main post-session checkpoint ran for non-`openat`
syscalls and consulted its stale descriptor scratch slot. The existing
kernel observations and exact oracle were complete; these excess audit
counts cannot be used to infer additional workload operations or a kernel
delivery cause. A focused builder test reproduced the absent syscall guard
before a guarded main post-session lookup made it pass (red → green).

The run stopped after reporting `INCOMPLETE`; unique mode did not execute.
The exact stopped runner
`tier-e-trustworthy-7021809b-fd88-4f55-8c51-062a6d8168ee-runner`
and matching `-artifacts` volume were verified retained. `raw-verifier.tar.gz`
stores the predeclared plan, source and image receipt, first-fork child
diagnostic, terminal and recovery record. `SHA256SUMS` seals the receipts;
host-local originals remain at `/tmp/tier-e-v4-eligibility-fixed-1790547258*`.
No source-clean diagnostic or default-policy qualification is claimed. If a
third changed-source verifier still cannot satisfy the independent audit,
stop and review the diagnostic architecture rather than layering another
unqualified probe onto the original BPF program.
