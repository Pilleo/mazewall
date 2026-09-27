# Version-four verifier: probe-eligibility regression — 2026-09-27

**`INCOMPLETE`, source-dirty, stopped on first full-stream fork.** The
predeclared 10,000-operation/1,000/s opt-in rootful Docker verifier loaded
the fixed 131,072-entry, 40-byte/entry descriptor array (5,242,880 value
bytes plus kernel overhead), but the first report failed its exact-audit
oracle. The independent raw and main *before-session* per-ID counts were
10,000/10,000, and both armed target aggregates were 10,002. Main after-
session and exact submission rose to **32,847 and 30,000** respectively;
all 10,000 IDs were thus non-singletons. This is **not** evidence that the
main program missed a declared syscall: the new opt-in pre-gate helper left
register `r5` nonzero. The older exact-audit setup copied that register to
its eligibility flag before testing `openat`, causing later syscalls to
reuse a descriptor and inflate audit counts. A focused bytecode regression
test failed before and passed after explicitly clearing `r5` at the v4
boundary. Production audit-disabled and older v3 bytecode are unchanged.

The report printed the failure and exited nonzero. The exact stopped
runner `tier-e-trustworthy-943f384c-f38a-4485-9239-aa401ae8063d-runner`
and matching `-artifacts` volume were verified retained. `raw-verifier.tar.gz`
holds the original plan, source/image receipt, first-fork child report,
terminal, kernel logs and recovery record; `SHA256SUMS` seals all retained
receipts. Host-local originals remain at `/tmp/tier-e-v4-verifier-1790546496*`.
This was an instrumented verifier attempt, **not** a capacity or default
qualification. A changed-source, separately predeclared verifier run is
necessary before a clean-source discriminator can begin.
