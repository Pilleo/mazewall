# Loom carrier versus mounted logical identity qualification

Review update: carrier/token counts are historical diagnostic observations, not
promotion proof. The carrier-only verifier skips terminal and target identity checks;
see [implementation review](2026-10-05-hotspot-implementation-review.md). Rerun with
independent expected PID/birth/path context after hardening, not self-derived identity.

Implemented offline carrier/mount/object scope gate and oracle-only virtual-thread
fixture/verifier. The actual candidate experiment does not inject oracle logical
IDs/parents into capture and cannot promote production support.

Pinned Temurin25.0.4.1+1-LTS, -Xint/SerialGC, ordinary target user and the same
verified stripped/native graph as JIT qualification.32virtual tasks each open8
unique A/B tokens then park. Carrier scheduler parallelism1/2/4 is varied.
Native JSONL, oracle and JVM logs retained under
`profiler/build/reports/hotspot-capability-ready-qualification/`.

|Carrier parallelism|Marked native events|Distinct native carrier TIDs|Proved logical IDs|Parent available|
|---|---:|---:|---:|---|
|1|256|1|0|false|
|2|256|2|0|false|
|4|256|4|0|false|

This directly demonstrates carrier reuse across many virtual tasks. Java oracle
threadId/isVirtual values are retained solely for comparison, not used as event
identity. Current native records have no event-time logical thread/mount/object
proof, so logical_observed0 is the correct result. Targets/helpers exit0 and cleanup
retires all wrapper processes/containers. Initial pre-attachment token misses remain
retained; actual attachment readiness is now used. Host/race and Python tests reject
swapped logical IDs, changed/absent mounts, absent object lease, oracle substitution,
wrong route, missing terminal/token and unsupported causal-parent claims.

## Source proof boundary

Pinned source520406d87195, SHA manifest beside reports:
`javaThread.hpp:100` identifies _vthread as an OopHandle, not an immutable logical ID;
`javaThread.cpp:158–180` creates handles, resolves vthread and replaces its value;
`javaThread.hpp:328` declares VTMS transition state. Resolving thread objects requires
oop-safe access (`javaThread.cpp:166–171`). Capture-time GC/handle lifetime and mount
generation therefore cannot be guessed from later remote reads or carrier TID.
Current program.bpf.c has physical TGID/TID/birth/epoch only; no such logical stamp.

No event-time mount/object lease has been implemented. Mounted continuation frames,
logical thread identity and causal async-parent history are different properties.
No authoritative capture-time parent source is established here: parent_available
remains false. Qualification harness is delivered, but migration/GC/object reuse and
native protocol/ABI promotion require the missing capture mechanism and new review.
Production remains process-wide pinned interpreter/Serial physical scope, with
unchanged policy incompleteness and no virtual-thread seccomp installation.
