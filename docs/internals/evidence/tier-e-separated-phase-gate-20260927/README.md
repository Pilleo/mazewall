# Source-bound fresh native phase and complete rootful gate — 2026-09-27

**PASS for clean committed `ae38c8ab`.** A detached clean checkout used the
versioned Docker rootful profile (`cpu_set=0,1`) and the unchanged historical
version-two native bounds. A **fresh**, isolated phase-only runner recorded
three CTests in each full and unique mode; both completed. Their
`GetStackTrace` medians were **2,477,129 ns** and **2,256,437 ns**, below
unchanged mode limits. The phase terminal was `COMPLETE` and
`policy_eligibility=false`.

The full rootful gate compiled its **own** agent and workload classes, then
accepted the separate receipt only after matching source digest and revision,
Docker image ID, exact agent SHA-256 and Java workload class SHA-256,
compiler/JDK versions, and CPU set. It independently replayed all six raw
samples against its own unchanged baseline. The recorded `phase-origin.json`
binds the original terminal hash and run ID. The live kernel/profiler/JVM
tests then ran with unrestricted CPU access; those tests are never substituted
by the phase receipt or Gradle output cache. The full gate passed all **39/39**
native CTests, the profiler/rootful/integration suite, and the native-backed
fresh-JVM suites (15 tests across the three named suites, zero skips/errors/
failures). Its benchmark status was `PASS`, terminal `COMPLETE`, and
`policy_eligibility=false`. Per-run owned resources were retired after
successful host evidence validation.

The earlier unrestricted and whole-runner-pinned phase/JVM failures remain
retained and **are not erased** by this bounded result. The first attempted
post-capacity gate timed out a four-thread integration test at its five-second
deadline; an identical retry passed the live suites but exceeded both native
phase limits. A phase-first, child-pinned attempt also exceeded both limits.
Those failures prompted the separate source-bound stage above. No historical
limit, operation denominator, native linear scan, or promotion decision was
changed. Podman remains operator-deferred and unverified. This gate does not
qualify the still-incomplete unaudited node-noise lane.

`manifest.json` records every exact identity, phase verdict and JUnit suite
count. `raw-receipts.tar.gz` includes both terminals, all twelve raw CTest
copies (six from acquisition and six verified by the gate), the 39-CTest log,
the profiler Gradle log, native-build identity, phase origin and required
fresh-JVM XML. `SHA256SUMS` seals these small receipts.
