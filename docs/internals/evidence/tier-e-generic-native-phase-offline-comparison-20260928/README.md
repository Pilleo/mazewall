# Tier E generic-kernel native phase: retained-sample comparison — 2026-09-28

This is a **read-only diagnosis of existing samples**, not a new phase run or
acceptance result. The source-clean `8a0d6d32` [first-phase terminal](../tier-e-generic-pinned-image-phase-stop-20260928/README.md)
remains `INCOMPLETE`; phase runs 2–3 and the pinned-image rootful aggregate
did not run. The three passing `fbbaad44` runs retain their own, different
source identity. No old and new samples are pooled for a gate verdict.

The comparison reads the 18 `full-{1,2,3}.log` and `unique-{1,2,3}.log`
samples from the three passing native-phase runs in
`../tier-e-generic-7-0-0-34-five-pair-and-phase-20260928/native-phase-raw.tar.gz`
and the six samples in
`../tier-e-generic-pinned-image-phase-stop-20260928/native-phase-raw.tar.gz`.
Both archives have their own sealed `SHA256SUMS`. Originals remain under
`/home/leanid/Documents/tier-e-generic-kernel-qualification-20260928/native-phase-{1,2,3}/`
and `/home/leanid/Documents/tier-e-generic-pinned-rootful-20260928/phase-1/`.
The figures below are per-mode **medians of nine historical samples** versus
**medians of three new samples**, purely descriptive; the actual checker uses
a median of three for each separate run.

| Mode and measured field | `fbbaad44` nine-sample median (range), ns | `8a0d6d32` first-run median (range), ns |
| --- | ---: | ---: |
| Full `get_stack_trace_ns` | 1,472,368 (1,392,453–1,806,234) | 2,022,989 (1,884,894–3,089,791) |
| Full `canonical_cache_lookup_ns` | 555,600 (526,144–712,032) | 772,394 (693,786–1,286,497) |
| Full `workloadNs` | 35,279,694 (32,726,184–41,713,279) | 52,933,052 (46,189,969–83,890,714) |
| Unique `get_stack_trace_ns` | 1,512,199 (1,321,610–2,023,252) | **3,157,189** (2,557,668–3,711,061) |
| Unique `canonical_cache_lookup_ns` | 567,184 (519,610–740,861) | 1,128,469 (953,735–1,518,272) |
| Unique `raw_key_ns` | 10,373 (10,169–16,125) | 21,842 (17,878–24,386) |
| Unique `logical_metadata_ns` | 410,022 (381,673–551,113) | 735,719 (709,361–876,286) |
| Unique `workloadNs` | 39,645,799 (33,889,310–46,607,071) | 70,157,241 (68,666,816–105,124,510) |
| Unique `publication_cpu_ns` | 814,524 (693,466–959,011) | 1,509,258 (1,247,111–1,791,247) |

Every compared workload reports 514 captures, zero stack failures and the
same 504 cache hits / 10 misses. The native-phase identities fix the image
ID, CPU set `0,2`, JDK, compiler, Debug agent SHA-256
`ff1e640c079de05ba407eab963cfb8939553e7f49390e60fdb20645faa3f790a`
and workload class SHA-256
`12ac99613bffb53253c02545063b37f01eb1860657233acf7879f90365d7c0e6`;
the previously recorded host kernel is Ubuntu `7.0.0-34-generic`. The native
build key and source digest **differ**, as required by the changed revision.
The intervening code diff changes rootful gate tooling and tests, not
`profiler-native` or the native test workload. None of these controls records
per-sample effective clock frequency, cgroup throttling, run-queue delay or
CPU time spent specifically inside `GetStackTrace`.

In `profiler-native/src/agent.cpp`, `get_stack_trace_ns` accumulates
`std::chrono::steady_clock` elapsed time around each synchronous JVMTI
`GetStackTrace` call, including possible off-CPU time. `workloadNs` uses
`System.nanoTime()` around the Java workload. In contrast,
`publication_cpu_ns` uses `CLOCK_THREAD_CPUTIME_ID` for the **publication
thread only**; it cannot isolate stack-capture CPU time. Multiple native
phase fields and the workload rose alongside `GetStackTrace`, and publication
thread CPU time rose too. The evidence is compatible with a wider host/JVM
timing shift, but does **not** establish scheduling preemption, CPU-frequency
change, a tooling regression, or a causal source effect. Byte-identical
measured binaries do not override the source-bound acceptance rule.

**Decision:** Keep the `8a0d6d32` timing and aggregate `INCOMPLETE` under the
unchanged unique `GetStackTrace` limit of 2,307,381 ns. No retry, phase reuse,
rebaseline or default promotion follows from this comparison. If the operator
later wants to investigate timing variability, define a separate, bounded,
predeclared diagnostic with paired CPU-time/elapsed-time and host scheduling
provenance; treat that diagnostic as non-qualifying until a separately
reviewed timing acceptance contract exists. Preserve the original failed
terminal and runner/volume.
