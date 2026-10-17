# Remaining HotSpot reliability, performance and capability specification

## Status and baseline

Planning baseline: `3099e2b0afa9a4c8168e52dd07d92c27b51fcdda`.
This specification extends [the performance/capability design](2026-10-05-hotspot-performance-capability-design.md).
It authorizes planning, not activation of new native/public interfaces or runtime profiles.
The accompanying plans divide independently reviewable subsystems; their approval
gates are part of the requirements, not successful fallback paths.

The commit hook already selects the complete Gradle build. Actual `build` and
`build check` graphs are identical389tasks; do not duplicate or weaken that gate.
The current experimental tools are Go, not the retired Python scripts in old plans.
Canonical native source is `profiler/hotspot-agentless`; the experiment has a
materialized snapshot and separate driver/fixtures. Changes to shared native
semantics must update the canonical source first and the experiment snapshot
explicitly, rather than assuming a symlink.

## Requirements copied into each execution plan

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
- Native enrichment workers4, pending events1024, pending payload16MiB, producer LRU256, frame cache16384, frame data3072words and perf data524288bytes/possible CPU remain fixed.

A proposed32byte per-CPU accounting entry is
instrumentation, not permission to enlarge any delivery buffer.

## R1: Remaining native completion failure

The original pid88950 fatal report shows FutureTask.set/VarHandleGuards after its
callable returned. It does not identify that callable. pid199455 is a different,
already-corrected applyBpfFilter callback case. Exact reports are retained read-only
under `.worktrees/repository-cleanup/enforcer/`; absence of those local artifacts
must be recorded rather than reconstructing a fictitious original failure.

Deliver an owned disposable reproducer and bounded pre-install identity/phase
journal. Distinguish library entry/return, callable return, Future completion and
actual thread/process retirement. A strict NATIVE_IMAGE control may complete or
fatally reject late code generation; no test may require it to crash on every run.
The HOTSPOT_JIT control must complete, and independent strict native probes must
still observe genuine EPERM. Attribute a historical failure only from actual
matching evidence. Leave the parent issue open if provenance remains unknown.

## R2: Dense interpreter readout

Freshly measure asset admission, child attachment, marked workload, native stop,
stdout parse/map/consume, final summary and real resource joins on one graph.
Keep failure/partial packets. The last inspected native XML records280received,
266paired,14pending; passing success-or-rejection tests is not dense promotion.
Remove a measured copy/allocation bottleneck with unchanged typed/raw validation.
Promote600marked operations/1ms only after the actual public facade preserves
all600tokens, process birth, A/B order, terminal accounting and checked retirement
in existing bounds. Three alternating fresh-process pairs must include diagnostic
serialization, not merely parser timing. No observed improvement means no promotion.

## R3: Admission hashing

First create a private, testable provider boundary with Java as the unchanged
default. Every provider must consume all bytes, count actual streamed bytes, and
return the same32byte SHA256; OCI and ownership validation remain common.

The historical AF_ALG result is qualification, not permission to activate it.
Current upstream Linux documentation (7.3.0-rc7 retrieved2026-10-17) marks AF_ALG
deprecated and describes Linux7.3 restrictions. That is not a claim that the
recorded7.2.10host has those defaults. The provider decision must record actual
kernel/support/restrictions and whether this dependency-free provider remains
acceptable. Never change host crypto policy to make a probe pass.

Before native activation require an approved platform boundary, explicit operator
selection, compatible legacy constructors, FD/arena/short-I/O/error review and an
owned deadline design. A timeout or interrupt is not proof a native/file operation
physically ended. Pending workers/FD owners remain inspectable and retryable;
provider selection never falls back after permission or integrity failure.

## R4: Real loss accounting

Associate final counter snapshots with session, PID/birth, actual map ID and
possible-CPU cardinality. Native admission, successful submit and reserve failure
must conserve per CPU before checked aggregation. Sample only after actual
producer detach and receiver/callback joins. Wrong map/session, missing slot,
overflow, unknown admission lane or non-final owner yields unknown.

Wire an optional selected-event diagnostic only through reviewed map/schema/pin
negotiation. Build a structured all-source inventory for selected events,
definitions (including auxiliary callbacks), enrichment, wire and retention.
Missing proof remains missing; reader exit is not producer loss proof. No task in
this round sets global loss completeness or enables policy conversion.

## R5/R6: JIT/GC and Loom lifetime qualification

Current native ABI2 captures safepoint identity, not a compiled-generation lease,
metadata reclamation lease or virtual-thread mount/object lease. Existing Go
Facts are offline evaluators and are never production authorities.

Deliver machine-readable native capability audits, occurrence-bound proof models,
wrong-generation/mount/reuse negative corpora, and independently bound actual
candidate reports. A supplied boolean is never a captured lease. Useful Java
prefixes do not establish lifetime safety. Carrier TID does not identify a
virtual thread or causal parent.

These plans finish with a concrete accepted/rejected mechanism decision. Native
lease capture is a research/architecture boundary: no sound mechanism is currently
approved. If the source/binary audit cannot prove capture-time ownership through
decode, the executable deliverable must report the exact missing capability and
production remains refused. A production admission/ABI implementation requires
an approved mechanism specification identifying the actual capture hook, immutable
stamp, owner acquisition, reclamation exclusion and final release. This is a
hard dependency, not an instruction to fabricate that mechanism in code.

## Sources and execution authorities

- [Epoch/lifetime design](2026-10-01-agentless-hotspot-epoch-design.md)
- [Latest qualification remediation](2026-10-17-benchmark-exact-owner-evidence.md)
- [Kernel digest evidence](2026-10-05-hotspot-kernel-digest-evidence.md)
- [Loss inventory](2026-10-05-hotspot-loss-accounting-evidence.md)
- [Linux crypto userspace interface](https://docs.kernel.org/crypto/userspace-if.html)
- [JDK25 Linker contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.html)
- [Testing strategy](../internals/designs/core/testing-strategy.md)

JDK FFM and Linux UAPI are authoritative for native carriers/layouts. The native
proposal must additionally check the actual installed UAPI headers and the pinned
JDK source/binary; current website documentation does not change those pins.
