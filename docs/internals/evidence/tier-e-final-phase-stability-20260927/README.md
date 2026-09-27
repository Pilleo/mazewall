# Final-code predeclared native phase stability gate — 2026-09-27

**`INCOMPLETE`: stopped at the first of three planned phase runs.** A clean
detached `75d68260` checkout pinned the exact source digest, immutable Docker
image, Debug native package SHA-256, JDK/compiler, CPUs `0,1`, six native
CTest samples per run and the **unchanged** historical version-two baseline
before starting. `phase-1` executed all six individual native CTests
successfully. Full mode passed its unchanged `GetStackTrace` and
key+lookup+logical bounds. Unique mode's three `GetStackTrace` samples were
**2,545,657**, **2,059,876**, and **2,553,066 ns**; median **2,545,657 ns**
exceeded its unchanged **2,307,381 ns** limit. The suite stopped immediately.
There was no phase-2/phase-3 attempt and no full rootful aggregate gate using
this failed predeclared series.

The earlier clean `ae38c8ab` complete receipt is preserved independently.
The two revisions' native `.text` sections had the same SHA-256, as did the
compiled Java workload class; their full library hashes differ because the
new Gradle native task builds at a different stable CMake path. Identical
native source and code-section hashes do **not** explain the observed host
timing variability or allow a failed current decision to inherit an old
`PASS`. No baseline, 125% threshold, operation denominator, native scan,
or promotion decision was edited. This lane remains policy-ineligible.

The phase controller recorded an `INCOMPLETE` terminal, stopped the owned
runner, and retained its exact artifact volume for recovery. `manifest.json`
binds the predeclared plan, the current and prior identities, all six raw
CTest values and both verdicts. `raw-phase.tar.gz` retains those inputs,
terminal, native package manifest and recovery receipt; `SHA256SUMS` seals
the small archive. A versioned replacement timing contract would require
explicit operator approval and fresh predetermined comparative evidence.
