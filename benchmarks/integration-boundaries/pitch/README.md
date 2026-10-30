# Bounded application measurements

This companion follows the boundary harness's separation of setup, ordinary serial
latency, pressure controls, resource observations and physical retirement. It does
not relabel the existing ASCII harness results or add a benchmark dependency.

Build the independent projects against your selected reviewed publications first:

```bash
./gradlew -p demos/koog-document-agent -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" installDist nativeProbeJar
./gradlew -p demos/spring-report-api -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" bootJar
python3 -m unittest discover -s benchmarks/integration-boundaries/pitch -p 'test_*.py'
```

On an otherwise idle compatible Linux/JDK25 host, capture each selected application
independently into **new** operator-owned raw files:

```bash
python3 benchmarks/integration-boundaries/pitch/measure.py koog demos/koog-document-agent
python3 benchmarks/integration-boundaries/pitch/measure.py spring demos/spring-report-api
python3 benchmarks/integration-boundaries/pitch/measure.py validate /absolute/path/to/raw.jsonl
```

Each application mode uses three fresh JVMs, 20 warmups and 100 serial successful
verified operations per JVM. Report each fork's median and p95 separately; never
pool warmup or pressure observations into normal latency. JSON retains every raw
latency, actual artifact hashes, runtime/environment, startup/drain scope and proc
RSS/thread observations. It is observations-only, not authenticated publication
custody. A nonzero/timed-out/missing-effect/unretired run fails, rather than accepting
partial data. The validator requires the complete selected mode inventory and exact
fork/sample counts and rejects changed derived statistics or missing retirement.

The Linux driver keeps its acquired private group leader **unreaped** with WNOWAIT
until all live group members stop, even when that leader exits first. Cleanup uses
the existing TERM/KILL bounds and verifies remaining members, not just the parent.
An otherwise successful parent leaving a live child is a failed measurement, even
if cleanup later kills it. This handles these first-party launches, not arbitrary
processes that deliberately escape their private session/group. Host self-tests
include an exited parent and a TERM-exiting parent with TERM-resistant children.
Owned command stdout and stderr are captured separately within the same combined
2MiB log bound. JSON candidates use stdout only; launcher diagnostics remain on
stderr or in the failure report. Inherited JVM options are not removed to silence
diagnostics.
If process inspection fails after ownership is confirmed, emergency group KILL is
still attempted; successful signalling is followed by bounded leader reaping. The
inspection failure remains a failed run; termination failures retain that original
cause. Denied emergency signalling does not claim leader/group retirement; best-effort
reaping of an already-exited leader on that error path remains a deferred minor.
The leader is revalidated before
emergency signalling, and an already-started KILL deadline is never reset. Unreadable
process state is not silently ignored or accepted as proof that every child retired.

- **Reader direct vs thread:** same real offline AIAgent construction/run/close and
  actual bounded document read; the protected variant uses the ordinary fixed policy,
  one worker and four admissions. Both install the compatible permanent host baseline.
- **Outline direct vs Portal:** same actual ATX implementation and offline agent
  work. The deliberately ordinary direct baseline loads the worker implementation
  into its own host; the protected mode keeps the normal worker bundle opaque.
  Portal records the actual distinct child's RSS/threads, not invented child heap.
- **Spring HTTP:** real loopback report, evaluation Basic authentication, request
  correlation and committed H2 row for each operation. Three private synthetic
  fixtures have explicit test-only review outside the implementation JVM. This is
  absolute application latency, **not a matched unprotected-Spring overhead result**.
  Each Python request opens a new connection. Host heap is unavailable, not zero.

Koog setup starts inside `main` and includes fixture/baseline/preparation; it excludes
JVM launch. Spring startup is measured from launch to observed ready. These startup
numbers are not directly comparable. Readiness polling/stop is included in Spring's
drain interval. The Kotlin entry checks actual worker/child end before emitting its
row; Spring additionally requires 120 real committed rows and clean contexts.

Separate Koog pressure controls hold one actual admission, queue three, reject the
fifth, then verify all four accepted results and canonical close. The Portal gate is
**host mapping**, not blocked guest computation. This is bounded admission behavior,
not throughput/capacity qualification. Spring held-cleanup/cancellation is qualified
by its separate native test, not an HTTP pressure or sustained-load measurement.

No live model, general hostile-code isolation, provider disclosure safety, exactly-once
external effects, negligible overhead, production load or presentation-machine result
is inferred. Run these commands on the intended presentation host and perform the
actual unfamiliar-human rehearsal separately. Do not automatically approve real
deployment candidates using this synthetic measurement harness.

These measurements remain the legacy small reader/outline/report workloads. They
do not measure incident analysis, provider inference, report validation or the new
actual-file CLI's end-to-end latency; useful application output does not expand the
measurement scope.
