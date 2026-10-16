---
title: "Validate captured interpreted HotSpot method identity before cached lookup"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless/patches/**"
  - "experiments/hotspot-agentless/internal/tooling/prepare.go"
  - "experiments/hotspot-agentless/README.md"
  - "experiments/hotspot-agentless/tests/fixtures/InterpretedIdentityCollisionProbe.go.txt"
  - "experiments/hotspot-agentless/scripts/probe-identity-boundary.sh"
  - "experiments/hotspot-agentless/fixtures/java/UnloadDriver.java"
  - "experiments/hotspot-agentless/fixtures/java/UnloadG00000.java"
  - "experiments/hotspot-agentless/internal/unloading/**"
  - "experiments/hotspot-agentless/internal/identity/**"
  - "experiments/hotspot-agentless/internal/capture/**"
  - "experiments/hotspot-agentless/internal/model/**"
  - "experiments/hotspot-agentless/cmd/mw-hotspot-demo/main.go"
  - "experiments/hotspot-agentless/gadgets/openat-stack/**"
  - "experiments/hotspot-agentless/scripts/rebuild-profiler-ebpf.sh"
  - "experiments/hotspot-agentless/tests/integration/unloading_test.go"
  - "experiments/hotspot-agentless/scripts/run-demo.sh"
  - "experiments/hotspot-agentless/Makefile"
  - "experiments/hotspot-agentless/results/README.md"
  - "experiments/hotspot-agentless/results/identity-investigation.md"
  - "docs/internals/backlog/README.md"
  - "docs/plans/2026-10-01-agentless-hotspot-epoch-design.md"
  - "docs/plans/2026-10-01-agentless-hotspot-epoch-plan.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Validate captured interpreted HotSpot method identity before cached lookup

**Context:**
The pinned profiler's `interpreter/hotspot/instance.go:260` declares
`getMethod(addr libpf.Address, _ uint32)` and accepts the existing method cache
entry using the address alone. Interpreter capture sends `cmethod >> 3` as
`ptr_check`, and `Symbolize` passes it to `getMethod`, but it is ignored. Compiled
JIT lookup separately checks its compilation ID. Quality metadata cannot prove
that an interpreted cached method belongs to the captured incarnation.

**Needed:**
1. Add an upstream regression with reused method address and changed captured
   ConstMethod check; assert no old method attribution.
2. Determine a bounded validation strategy preserving existing cache ownership,
   read-error behavior and fail-closed attribution; prepare an independent patch
   candidate for review (upstream submission requires operator action).
3. Verify class-unloading/reuse behavior before accepting the runtime identity
   matrix. Do not claim the current quality patch resolves this issue.

**Bounded fix implemented locally; review/runtime matrix pending:**
`profiler-identity.patch` routes interpreted frames through an explicit checked
lookup, including token zero. Two checked eight-byte reads bracket cached/decoded
method lookup. It compares the captured `(ConstMethod >> 3)` low 32 bits, retains
the full ConstMethod address in the existing method cache, reloads a changed
cached address, and rejects replacement during lookup. Read failures remain
wrapped errors (EPERM/EACCES included); no unreadable-pointer-to-zero fallback.
Remote-memory bias and short reads are handled explicitly.

Red regression reproduced stale frame emission and cache attribution despite
unreadable memory. Green tests cover matching cache, changed address/reload,
replacement during lookup, valid zero token, null/overflow/short reads, bias and
permission failures. `make verify` and live Xint/tiered quality/recovery gates
passed. Artifacts: `capture.Sqmafu`, `capture.eFqJZ6`, `capture.xzcjqm` under the
experiment's ignored `build/` directory.
Backlog validation and `./gradlew build` passed after focused checks.

**Remaining identity limitations:** the producer fingerprint is truncated and
cannot distinguish all address reuse/ABA cases. Frame-cache reuse and compiled
scope lookup retain existing behavior. The bounded checks do not establish a
generation identity or replace the class-unloading/reuse acceptance matrix.

**Reproduced blocker for full generation acceptance (2026-10-01):**
`bash scripts/probe-identity-boundary.sh` fails the generation assertion in an
isolated provider copy. ConstMethod pointers `0x200` and `0x800000200` produce
the identical captured 32-bit shifted fingerprint. A delayed old frame can be
accepted against an already cached replacement incarnation with matching live
pointer checks. Artifact: `build/identity-boundary.iAwoCp/probe.log`.

This is a diagnostic fixture outside the ordinary passing test suite. Full
generation correctness cannot be asserted from the current capture fields.
Continuing that gate requires a separately approved captured-incarnation evidence
and cache/protocol design; widening a pointer fingerprint alone does not rule
out full-address recycling/ABA. Do not weaken the assertion or advertise the
stable-workload matrix as class-unloading/reuse proof.

**Root-cause investigation:** source tracing confirmed 32-bit truncation during
capture, current-state-only pointer checks and an earlier raw-frame cache fast
path that bypasses revalidation. A second synthetic probe demonstrated unchanged
address/cache ABA independently of the 32-GiB collision. Both fail in
`build/identity-boundary.UanQmL/`. OpenJDK 25u allocator/lifetime source supports
metadata release/reuse. The subsequent live follow-up below establishes actual
misattribution; frequency in arbitrary applications remains unmeasured. Details:
`results/identity-investigation.md` in the experiment.

**Live failure now reproduced:** eighty disposable class loaders each performed
twenty generation-tagged opens, then were confirmed unloaded by JVM logs. Three
fresh runs, including the normal uninstrumented collector, each observed all
1,600 tokens but emitted 1,560 wrong-generation results: generations 2–79 were
labelled generation 1. Artifact control: `build/capture.UueXxk/`; diagnostics:
`build/capture.ZlX9mH/` and `build/capture.x8pMCm/`.

Four producer references published in generation 1 were reused through generation
79, preventing later definitions and interpreted validation. The current fix
therefore cannot satisfy unloading identity acceptance. Producer, frame and
method caches all need to participate in the remedy. This live result does not
prove the particular synthetic 32-GiB collision, and full ConstMethod readings
were available only on published definitions. Keep the failing live assertion.

**Scoped remedy architecture approved (2026-10-01):** capture a stable even JVM
safepoint epoch, isolate producer/frame identities by epoch, invalidate existing
method/Symbol/JIT metadata caches across boundaries, and bracket deferred decoding
with checked epoch reads. Preserve already validated immutable historical
definitions and reject cross-boundary tentative labels. Initial support is the
exact pinned interpreter-only Serial-GC profile; live flags and ELF identity must
be verified. See the [epoch design](../../plans/2026-10-01-agentless-hotspot-epoch-design.md)
and [implementation plan](../../plans/2026-10-01-agentless-hotspot-epoch-plan.md).
Implementation is not started; retain
this issue as open and require useful resolution across generations, not merely
an all-unavailable zero-wrong-label result.

**Second detailed review: changes requested (2026-10-01):** the high-level epoch
architecture remains approved, but the first detailed plan is not executable.
Five focused issues cover shared-reader/mapping concurrency, rejected metadata
cache rollback, session-fatal error delivery without a lookup, captured evidence
versus validation outcome, and enforcement of the excluded exec lifetime.
The design lists their links. Isolated decoder probes reproduced rejected method
cache retention and loss of a later permission cause in the proposed ledger.
Corrected the plan's read-address bias rule. No remedy has been applied; keep
this parent issue open until the revised contracts and live gates are reviewed.

**Bounded epoch remedy implemented (2026-10-01):** all five review corrections
are implemented in the standalone patches/collector. Capture/definition epochs
are isolated across producer/frame caches; decoding uses immutable copied memory,
abort-time metadata-LRU invalidation and separate validation. Fatal errors have a
required session owner, and an owned pre-discovery exec barrier closes unsupported
incarnations. ABI v2 layouts and both BPF objects are regenerated with pinned tools.

Normal strict runs `capture.pq4kaX`, `capture.mBTB50`, `capture.DOGzCx`, and final
audited `capture.FMeuii` each observe 1600 tokens, confirm 80 unloads, and return
1580 epoch-checked correct segments/20 cold unavailable/zero wrong labels. Both
branches of generations 1–79 resolve all ten operations. Non-epoch control
`capture.Enk1ls` still produces 1560 wrong labels. Host race/upstream, real exec
and LRU/pruning, legacy/strict genuine recovery, C2 regression and pinned build
checks pass. Exact source/object/image hashes are in the evidence report.
Keep the parent and focused findings open for independent review; concurrent
collectors, JIT reclamation, arbitrary JDKs, BCI/loss completeness and overhead
remain separate gates.

---
<!-- id: issue-20260930-220901-114-validate-captured-interpreted-hotspot-method-ident  file: issue-20260930-220901-114-validate-captured-interpreted-hotspot-method-ident.md -->
