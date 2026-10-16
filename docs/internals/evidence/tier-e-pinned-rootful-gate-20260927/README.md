# Clean pinned rootful Docker gate — 2026-09-27

**PASS at committed `de268c9e` only.** A clean detached Git checkout used
`config/testing/tier-e/rootful-gate.json` with explicit `--cpuset-cpus=0,1`
and rootful Docker at `unix:///var/run/docker.sock`. The complete native
CTest suite passed **39/39**, host-side profiler tests and rootful integration
tests passed, and both native-backed fresh-JVM integration suites had zero
skips/errors/failures. Three new version-two samples per mode passed the
unchanged historical 125% phase limits: full `GetStackTrace` median
**1,634,066 ns**, unique **1,621,907 ns**. Their exact key+cache+logical
per-sample totals were **1,111,795 ns** and **1,060,377 ns** respectively,
below unchanged full/unique total limits **13,412,790 ns** and
**2,119,025 ns**.

The terminal was `COMPLETE`, benchmark gate `PASS`, and
`policy_eligibility=false`. The exact runner and per-run artifact volume were
retired only after the evidence was exported and verified. A persistent
content-keyed native **build** volume and Gradle download/build cache remain;
they are not new measurements. This clean run took **9m25s** inside its
container because live kernel tests intentionally execute afresh and the
committed source's initial Gradle checkout was cold. The prior unpinned
failures remain preserved, with no baseline change.

`manifest.json` records revision, image/native build identity, CPU placement,
39 native CTests, JUnit suite counts and both phase verdicts. The
`raw-gate.tar.gz` archive contains the 39-test CTest transcript, Gradle
profiler test log, six individual phase samples, tested JUnit XML and terminal.
The ledger verifies these small retained receipts. This passing gate predates
the subsequent typed capacity/bundle-cache revision; it does not by itself
qualify a later commit or Podman.
