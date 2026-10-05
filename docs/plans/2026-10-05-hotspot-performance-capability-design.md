# Remaining HotSpot performance and capability work

## Execution checkpoint

Runtime readout, native digest qualification, compiler/GC/Loom qualification
harnesses and conservative accounting/kernel counter qualification are committed.
Final root build passes(run-7d0cc2e4cea5), Go full host/race/digest/asset/distribution
checks pass(run-eb6a261baebd), oracle/readiness/backlog/whitespace pass
(run-c8798d59d5a5), repository lint and evidence links pass. Actual public7case
matrix and isolated512open native counter test execute rather than skip.
No production hash activation, compiled/mount lease or default loss binding is
claimed. Those promotion/integration scopes remain open and their plans explicitly
record the missing proof/contracts. Existing operator work/branch are preserved.

Status: operator approved inline execution of the named plans. Runtime readout
changes and experimental qualification deliverables are implemented and recorded
below. New platform/public interfaces and unsupported runtime admission still
require the explicit proof/approval gates; they are not inferred from qualification.

Execution evidence: [dense](2026-10-05-hotspot-dense-readout-evidence.md),
[digest](2026-10-05-hotspot-kernel-digest-evidence.md),
[JIT/GC](2026-10-05-hotspot-jit-gc-evidence.md),
[Loom](2026-10-05-hotspot-loom-evidence.md),
[loss](2026-10-05-hotspot-loss-accounting-evidence.md).
Default loss-owner integration/all-source instrumentation and native runtime
promotion remain pending rather than being labeled implemented.

## Starting point

Seven MazeWall integration milestones and the release-footprint change are complete.
The stripped helper is68,231,330bytes; matched interpreted checksum median44.966s.
Live admission remained69.8–78.3s. A600-operation/1ms self-profile rejected with
283received/182retained/101pending, with no pending resource ownership.
Offline600 replay median3.304s splits1.399s mapping/1.905s reader/decode/I/O.
JFR points to interpreted SHA2 and JSON parsing/diagnostic rendering. These are
measurements, not service-level bounds.

Evidence: [diagnosis](2026-10-05-hotspot-interpreter-performance-evidence.md),
[footprint](2026-10-05-hotspot-release-footprint-evidence.md), and existing
[epoch lifetime design](2026-10-01-agentless-hotspot-epoch-design.md).

## Global requirements (copy into every execution plan)

- JDK25 idioms; library compatibility floor JDK22. Supported host Linux-amd64.
- Production remains pinned Temurin25.0.4.1+1-LTS, `-Xint -XX:+UseSerialGC`, synchronous `openat_handler_entry` until a separately approved capability gate passes.
- Readiness <=10seconds, session <=60seconds, stop <=2 remaining seconds, event enrichment deadline1second; no larger worker/pending/native buffer budgets.
- Kotlin retention4096 occurrences/33,554,432 wire-payload bytes, max line1,048,576bytes, max wire rows65,536, max frames4096, max tracked occurrences32,768.
- Rehash every asset byte and validate the complete local OCI graph; no size/mtime/inode digest cache or trusted-helper self-verification shortcut.
- No dependencies or breaking public APIs; additive native/public interfaces require explicit approval and legacy ABI verification before promotion.
- No swallowed/downgraded EPERM/EACCES. Fallback is explicit operator choice only.
- Raw FFM remains behind NativeEngine/io.mazewall.ffi; confined arenas, immediate errno capture, acquired descriptor ownership and checked close.
- Captured occurrence/session/process generation/BOOTTIME identity is immutable; never substitute a later stack or infer logical identity from host TID alone.
- Physical drain, selected-stream accounting, whole-session collection and policy eligibility remain separate. Policy coverage stays incomplete for selected openat evidence.
- Never install seccomp from a virtual thread or change JVM coordination syscall floors; do not touch GITHUB_TOKEN.
- Native builds remain optional, reproducible and operator-invoked; no startup compiler/download/sudo or registry fallback.
- Preserve user work, scope commits to the relevant backlog item and use the orchestrator-managed review path; no default-branch push.

## Decomposition and order

|Priority|Plan|Independent deliverable|
|---|---|---|
|1|[Dense readout](../superpowers/plans/2026-10-05-hotspot-dense-readout.md)|Deferred canonical diagnostic rendering and reusable decoder, exact evidence/refusal regression plus live comparison|
|2|[Admission acceleration](../superpowers/plans/2026-10-05-hotspot-admission-acceleration.md)|Read-only AF_ALG SHA256 qualification and an explicit production-provider decision, with no default integrity change|
|3|[JIT/GC qualification](../superpowers/plans/2026-10-05-hotspot-jit-gc-qualification.md)|Versioned compiler/GC candidate evidence and conservative lifetime promotion gate|
|4|[Loom qualification](../superpowers/plans/2026-10-05-hotspot-loom-qualification.md)|Carrier versus mounted-virtual identity experiments and explicit logical-parent availability contract|
|5|[Loss accounting](../superpowers/plans/2026-10-05-hotspot-loss-accounting.md)|Conservative conservation proof, actual per-CPU event counters and all-source completeness inventory|

Plans1/2 are independent. Plans3/4 preserve production refusal while candidate
qualification is underway; logical virtual-thread ancestry is not automatically
derivable from a physical stack. Plan5 can improve selected-event loss visibility
without depending on a new JVM profile. Execute serially in one worktree unless
the operator explicitly approves isolated parallel work.

## What completes the first requested point

Performance changes must pass semantic/refusal/resource tests and compare the same
input/asset graph in three alternating fresh-process pairs. Preserve rejected rows.
For dense readout, promotion to an accepted600/1ms fixture requires the actual
public facade to return all marked tokens with exact identity/frame order and
checked retirement within unchanged budgets. A faster offline replay alone is
insufficient. For admission, qualify the kernel provider first; an opt-in native
bridge requires a separate approved platform/API design rather than an undocumented
property or automatic fallback.

## What completes the second requested point

Qualify tuples, not all JITs/GCs/JDKs collectively. Initial candidates are pinned
C1/Serial, C2/Serial and interpreter/G1, independently gated. Compiled code-cache
reclamation, concurrent metadata unloading and mount/unmount races require captured
generation evidence plus decode lifetime protection; a stable safepoint counter
alone is not enough. Class redefinition, async parents and other builds remain
explicitly unavailable until their own mechanism is proved.

Complete loss accounting requires final counters and leases for every native
producer/transport, definition delivery, enrichment terminal, wire delivery and
retention boundary. Missing/overflowing counters yield unknown, never zero. Even
an exact selected-stream proof cannot make selected openat observations an
enforceable whole-program policy.

## Review gates

Before a new native provider: measured kernel availability/performance, layout/errno/
FD review and explicit operator opt-in design. Before a runtime whitelist change:
source/binary lifetime evidence, useful wrong-generation oracle corpus, ABI/protocol
version decision and actual kernel acceptance on the selected graph. Before any
completeness flag: producer inventory and adversarial conservation tests, including
held callbacks/late records/CPU hotplug/transport drops. Gate failures retain current
supported behavior and a diagnostic result rather than admitting an unproved mode.
