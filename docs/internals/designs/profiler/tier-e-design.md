# Tier E — eBPF Semantic Enrichment: Technical Design Document

**Status:** APPROVED (architecture locked 2026-08-25)
**Component:** `:platform`, `:profiler`, `ebpf-prototype/`
**Related:** [profiler-design.md](profiler-design.md) (Tiers P/S/A), [security-considerations.md](../core/security-considerations.md) (Shared-Memory ACE threat model)
**Work packages:** [docs/internals/backlog/implementation/issue-20260825-*tier-e*.md](../../backlog/implementation/)

---

## 1. Summary

Tier E adds a **semantic attribution plane** to mazewall profiling. Application code declares
*why* a thread is executing (`PDF_PARSE`, `STRIPE_CLIENT`, …); an eBPF uprobe records that
declaration into BPF task-local storage; a `sys_enter` tracepoint program reads it back at
syscall entry and emits attributed events. The result is a per-syscall answer to
*"which application-level operation caused this?"* — with **no JVM suspension, no
USER_NOTIF round-trips, and no per-syscall Java work**.

The end state is deliberately shaped for later reimplementation inside Kubescape's
node-agent:

```text
Java code                         Linux kernel / eBPF
─────────────────────────────     ────────────────────────────────────
MazewallContext.run(PDF_PARSE)
    │ FFM downcall
    ▼
mazewall_context_marker(42)       uprobe fires synchronously
                                  bpf_task_storage_set(current, 42)
    │
    ├─ Files.readString(...)
    ▼
openat("/tmp/in.pdf")             sys_enter raw tracepoint
                                  ctx = task_storage[current]   → 42
                                  ringbuf: { tid, openat, ctx=42 }
```

## 2. Position in the Tier Taxonomy

| Tier | Mechanism | Privilege | Output | Completeness |
|---|---|---|---|---|
| P | eBPF tracepoints / strace descendant | host root / rootful container | raw syscall stream | complete, unattributed |
| S | Out-of-process `USER_NOTIF` supervisor | unprivileged target | exact syscalls + JVM stacks | complete, slow, suspends tracee |
| A | Iterative deny-and-retry | zero privilege | Landlock paths + syscalls | converges over N runs |
| **E** | **uprobe marker → task storage → `sys_enter`** | **privileged daemon; target stays unprivileged** | **attributed syscall events** | **attribution-complete; event-stream lossy (ringbuf)** |

Tier E does not replace S or P. It is the **enrichment layer**: coarse semantic truth at full
speed. Exact stacks and policy-grade completeness remain Tier S's job.

## 3. Invariants (non-negotiable, enforced by review and where possible by types)

1. **Profiling/enrichment/detection hints only.** Never an enforcement input.
   Enforcement remains seccomp/Landlock + `USER_NOTIF`/`ADDFD`.
2. **Context metadata is tracee-controlled and therefore forgeable.** `ContextId` carries an
   `UNTRUSTED ATTRIBUTION METADATA` contract on the type itself.
3. **Fail UNKNOWN, never guess.** Missing registration, storage-create failure, ring-buffer
   drop, pre-attach window, post-detach residue — every uncertain case yields `UNKNOWN`,
   never "nearest context" or timestamp inference.
4. **Platform threads only.** Virtual threads are rejected fail-closed inside
   `MazewallContext` (Loom carrier-poisoning doctrine, see profiler-design.md §1).
5. **One BPF map set per session epoch.** Maps are never recycled across sessions. Task
   local storage is scoped per-map instance, so a fresh session observes no stale values.
6. **No bpffs pinning in v1.** The daemon owns all map/link FDs. Daemon death detaches
   everything.
7. **Session death is terminal.** `RUNNING → DEAD` only; no reconnect-and-splice. A new
   connection is a new epoch with a fresh map set.
8. **No policy generation from Tier E.** `toPolicy()` must not exist. Ring-buffer drops make
   the stream incomplete by construction; BoB compilation remains USER_NOTIF-oracle-only.
   The existing `ProfileCollector` KDoc ("Implementations must not compile policies
   themselves") already encodes this.
9. **Ring-buffer loss marks observations incomplete.** A per-syscall drop counter is
   mandatory; any drop ⇒ `drainComplete = false` in the collector drain.

## 4. Architecture

### 4.1 The handoff primitive

The context transition itself is made observable to eBPF via a synchronous native call:

```text
libmazewall_context.so
    void mazewall_context_marker(uint32_t context_id) { /* intentionally empty */ }
```

* Java calls it through an FFM downcall from inside `MazewallContext.withContext { }`
  (enter) and its `finally` block (restore).
* An eBPF uprobe attached at the exported symbol's file offset fires **synchronously on the
  calling Linux task** before the call returns (breakpoint trap runs the BPF program first).
* The BPF program writes the value into `BPF_MAP_TYPE_TASK_STORAGE` keyed on
  `bpf_get_current_task_btf()`.
* At `sys_enter`, a second program reads the same task's storage and emits an attributed
  ring-buffer record.

Because both programs run **on the same task**, and a task executes on exactly one CPU at
any instant, the write and all subsequent reads are serialized by construction. There is no
userspace/kernel memory-ordering proof to make — this is the property that eliminated the
previous mmap-slot design (§6).

### 4.1.1 Marker ABI: Mazewall USDT probe (production) vs plain uprobe (bring-up)

> **Decision (2026-08-25):** Tier E context transitions **SHOULD** be exposed as a Mazewall
> **USDT probe** rather than treating an ordinary native function symbol as the long-term
> tracing ABI. The underlying attribution mechanism remains uprobe → BPF task storage.

Production-facing interface:

```text
Provider: mazewall          Probe: context_switch(uint32 context_id)

void mazewall_context_marker(uint32_t context_id) {
    DTRACE_PROBE1(mazewall, context_switch, context_id);   /* sys/sdt.h */
}
```

Rationale (full review in Appendix A):

* **The marker becomes an ABI described by the binary itself.** Probe address, provider,
  name, and argument locations live in `.note.stapsdt`; the daemon verifies
  `mazewall:context_switch` exists *in the exact library mapped by the target* before
  declaring a session RUNNING. Missing probe ⇒ loud `ATTACH_FAILED`, never silently
  everything-UNKNOWN (sharpens §11 risk 4).
* **Argument portability:** `bpf_usdt_readarg()` resolves argument location per arch/ABI,
  eliminating hand-written register access (§11 risk 7) in the USDT variant.
* **Single-probe semantics:** one `context_switch` probe carries full state
  (`task_storage[current] = context_id`). Java nesting logic already knows restore values;
  BPF needs no stack. Enter/exit probe pairs are explicitly rejected.
* **Extension vocabulary:** later probes (`async_submit(operationId, contextId, …)`) give
  the future async/io_uring extension a request-keyed storage shape without inventing a new
  bridge (Appendix A.3).
* **Not adopted for v1:** USDT semaphores ("is-enabled"). Our v1 arguments cost nothing to
  prepare. Revisit when probes carry expensive-to-build metadata.
* **FFM downcall is still required** — USDT does not remove the Java→native hop; G1
  benchmarking applies identically to both variants.

Build-order contract (WP-03): **G0a** proves the plain exported-symbol uprobe end-to-end;
**G0b** swaps attachment to `usdt/<path>:mazewall:context_switch` (libbpf ≥ 0.8) and must
reproduce G0a byte-for-byte; **G1** benchmarks both variants.

> **Sign-off (2026-08-25, operator):** the Kotlin control plane ships **uprobe-first**
> under this clause's escape hatch — G0a/G0b parity is already recorded in Appendix B —
> with the `.note.stapsdt` parser as a focused follow-up before USDT becomes the default
> attach mode there. Dependency impact
(ask-first rule): `systemtap-sdt-devel` (`sys/sdt.h`) enters the *prototype build toolchain*
only; libbpf remains prototype-only. WP-14 must either parse `.note.stapsdt` itself or
justify a plain-uprobe fallback with recorded parity evidence before shipping without libbpf.

### 4.2 Data model

A context is a fixed unsigned 32-bit integer (`io.mazewall.core.ContextId`):

```text
0x00000000        UNKNOWN (fail-unknown sentinel)
0x00000001..      operator-assigned semantic scopes
```

Attribution provenance is recorded separately as `AttributionKind`:
`NONE | EXPLICIT_CONTEXT | AGENT_CONTEXT | USER_NOTIF_ORACLE`.

Wire form everywhere (BTF struct fields, ring buffer records, future control protocol):
exactly 4 bytes, big-endian, no varint, no nullable representation.

### 4.3 Kernel-side sketch

```c
struct context_event {
    __u64 ktime_ns;
    __u32 tgid;
    __u32 tid;
    __s32 syscall_nr;
    __u32 context_id;      /* ContextId wire form */
};

struct {
    __uint(type, BPF_MAP_TYPE_TASK_STORAGE);
    __uint(map_flags, BPF_F_NO_PREALLOC);
    __type(key, int);
    __type(value, __u32);
} context_storage SEC(".maps");

SEC("uprobe")                     /* attach: marker symbol offset */
int BPF_UPROBE(on_marker, __u32 ctx_id)
{
    __u32 v = ctx_id;
    bpf_task_storage_get(&context_storage,
                         (struct task_struct *)bpf_get_current_task_btf(),
                         &v, BPF_LOCAL_STORAGE_GET_F_CREATE);
    return 0;
}

SEC("raw_tp/sys_enter")
int BPF_PROG(on_sys_enter, struct pt_regs *regs, long id)
{
    /* TGID/cgroup filter first; then: */
    __u32 *ctx = bpf_task_storage_get(&context_storage,
                         (struct task_struct *)bpf_get_current_task_btf(),
                         NULL, 0);
    if (!ctx || *ctx == 0) { unknown_counter(id); return 0; }   /* invariant 3 */
    reserve ringbuf … commit;
}
```

Notes verified against kernel sources/docs (see §7): `sys_enter`'s second raw argument **is**
the syscall number, so no CO-RE dereference of `pt_regs->orig_ax` is required for v1;
arch-specific register access (`di` on x86_64, `x0` on arm64) is needed only inside the
uprobe program to read the marker argument when using classic (non-BTF) uprobe context —
compile-time per-arch selection.

### 4.4 Component layout

```text
ebpf-prototype/                 unwired until Gate G2 passes
├── bpf/mazewall_context.bpf.c  uprobe + sys_enter programs, maps, ringbuf
├── daemon/                     privileged C sidecar: load, attach, session control
└── client/                     throwaway test client (pre-FFM)

:platform                       io.mazewall.core: ContextId, AttributionKind
                                ffi additions (later PRs): marker downcall bind,
                                ringbuf mmap reader, bpf()/perf_event_open downcalls

:profiler                       LiveEbpfCollector : ProfileCollector (post-G2)
                                ProfileObservation gains optional context fields
```

### 4.5 Attach mechanics (FFM-feasible path, used by the PR-15 migration)

Uprobes are a dynamic PMU since Linux 4.17. Without libbpf:

1. Read PMU type from `/sys/bus/event_source/devices/uprobe/type`.
2. `perf_event_open(attr)` with `attr.config1 = uprobe_path`, `attr.config2 =
   probe_offset`, pid filter scoped to the target JVM.
3. `bpf(BPF_LINK_CREATE, …)` with `link_type = BPF_PERF_EVENT_LINK_TYPE`,
   `target_fd = perf_fd`.

`probe_offset` is resolved from the ELF symbol table of the exact file mapped by the JVM,
verified via `/proc/<pid>/maps` inode match plus `NT_GNU_BUILD_ID` comparison. A mismatch
is a loud attach failure (benign direction: without attachment every event is UNKNOWN).

## 5. Trust Model

> **Tier E context metadata is supplied by the observed JVM and is not trustworthy after
> compromise of that JVM.**

* Any ACE inside the target can call the marker with an arbitrary id and forge its own
  labels. This is acceptable because Tier E feeds detection/observability only (invariant 1).
* **Self-labeling only:** a uprobe fires on the process that executed the marker; task
  storage updates *that task*. No mechanism exists by which process A labels process B's
  syscalls. Unlike the rejected shared-array design, there is no writable cross-process
  surface at all.
* **Control socket** (`/run/mazewall/context.sock`, WP-04) is metadata-plane only:
  filesystem ownership + restrictive perms, `SO_PEERCRED` validation at accept time,
  duplicate-session rejection. It can never alter another process's attribution.
* **Daemon death:** links and maps die with the daemon's FDs (no pins). Breakpoints are
  restored; markers become inert plain calls. Storage entries persist until task exit but
  belong to a dead map object and are unreachable. Post-death events do not exist; the
  consumer sees session DEAD (invariant 7).
* **Stale-positive residue within a live session:** detach mid-scope leaves storage set
  until the next transition or task exit. Events emitted during that window carry the last
  declared context — accepted and documented because the reader contract requires an
  explicit RUNNING session epoch covering them.

## 6. Rejected Alternative: mmap'd Context Slots ("Tier E Fast")

The original design wrote `(generation << 32) | contextId` into a `BPF_F_MMAPABLE` array
slot from userspace, keyed by host TID resolved across PID namespaces. It was rejected for
the correctness path because:

1. **Cross-model memory ordering.** The JMM gives no guarantees to an eBPF reader; LKMM and
   hardware semantics govern the actual race. A release fence is not a proof; the kernel
   documentation itself warns that direct pointers into array-map values require explicit
   synchronization under concurrent access. On ARM64 a late-visible store could attribute a
   syscall to the *previous* scope — a confident lie, violating invariant 3's spirit.
2. **Identity joining machinery.** Container↔host TID translation (NSpid scans), slot
   allocation/exhaustion, generation counters, SCM_RIGHTS map-FD export, thread-exit cleanup
   — all existed solely to compensate for the indirect keying.
3. **Forgeability surface.** The whole array FD lived in the client; a malicious client
   could scribble other clients' slots.

It is recorded as **Tier E Fast — deferred optimization, not an implementation target**.
Reviving it requires: a formally reviewed cross-userspace/kernel release/acquire protocol,
mandatory ARM64 CI, and a written proof that wrong attribution cannot occur under
concurrent userspace/kernel access.

## 7. Verified Kernel Facts (floor: Linux 5.15 LTS)

| Fact | Since | Source |
|---|---|---|
| `bpf_task_storage_get/delete` helpers | 5.11 (LSM-only), tracing/kprobe/perf-event/raw_tp/tracepoint/tracing progs ~5.12 | Song Liu series, netdev 2021-02; eBPF docs helper page lists `BPF_PROG_TYPE_KPROBE` |
| Task storage auto-freed at task destruction | 5.11+ (`bpf_task_storage_free` in `__put_task_struct`) | same series |
| Uprobe dynamic PMU via `perf_event_open` (`config1`=path, `config2`=offset, pid filter meaningful) | 4.17 | `perf_event_open(2)`; LWN 740832 |
| Raw tracepoint `sys_enter` args: `(pt_regs*, long nr)` — nr directly available | 4.16 | kernel `bpf_trace.c`; tracepoint format |
| `bpf_get_current_task_btf()` trusted current pointer | 5.11 | kernel sources |
| Storage allocation refused for dying tasks (`usage == 0`) → treat as UNKNOWN | 5.12+ | same series (refcount guard) |
| BPF ring buffer (used for event delivery) | 5.8 | kernel docs |
| `BPF_F_MMAPABLE` (deferred Tier E Fast only) | 5.5 | kernel docs |
| Recursive task-storage deadlock guard (helper fails over instead of hanging) | 5.12 | same series |

CI/test environments must run rootful containers (Podman/Docker with `--privileged` or
equivalent caps) — identical ceiling to Tier P, documented in profiler-design.md §Tier P.

## 8. Gates

| Gate | Definition | Blocking |
|---|---|---|
| **G0** | Deterministic pairing: **G0a** single platform thread, no agent/containers/concurrency — `marker(42)`→`context==42`, `marker(7)`→`context==7` via plain uprobe; **G0b** identical results with USDT attach. 100% repeatable. | PR-03 |
| **G1** | Marker latency measured at realistic transition rates for **both** variants (µs-class uprobe cost quantified; skip-if-unchanged optimization evaluated). Numbers recorded as design doc addendum. | PR-03 |
| **G2** | Zero wrong pairings under stress: thread churn (≥100k create/destroy), nesting, executor reuse, deliberate pre-attach/post-detach windows. UNKNOWN and dropped reported separately; incorrect == 0. | PR-05 |
| **G3** | Oracle parity matrix (WP-10): Tier E vs `USER_NOTIF` oracle across 7 workload classes; incorrect attribution == 0. | PR-10 |

## 9. Work Packages

See backlog registry: `docs/internals/backlog/implementation/issue-20260825-*-tier-e-wp-*.md`.

| WP | Roadmap step | Issue |
|---|---|---|
| — | Master tracker | issue-…-tier-e-initiative.md |
| WP-01 | In-memory `MazewallContext` API + guards | issue-…-wp-01-mazewall-context-api.md |
| WP-02 | Standalone C eBPF syscall collector | issue-…-wp-02-collector-prototype.md |
| WP-03 | Marker `.so` + uprobe + task-storage PoC (G0/G1) | issue-…-wp-03-marker-uprobe-poc.md |
| WP-04 | Session lifecycle & trust protocol | issue-…-wp-04-lifecycle-trust.md |
| WP-05 | Concurrency stress suite (G2) | issue-…-wp-05-concurrency-stress.md |
| WP-06 | Noise budget & UNKNOWN counters | issue-…-wp-06-noise-budget.md |
| WP-07 | Container metadata association | issue-…-wp-07-container-metadata.md |
| WP-08 | FFM bridge client | issue-…-wp-08-ffm-bridge-client.md |
| WP-09 | `LiveEbpfCollector` integration | issue-…-wp-09-live-collector.md |
| WP-10 | Oracle comparison suite (G3) | issue-…-wp-10-oracle-comparison.md |
| WP-11 | Limited Java agent | issue-…-wp-11-java-agent.md |
| WP-12 | Performance harness | issue-…-wp-12-perf-harness.md |
| WP-13 | Sampling enrichment | issue-…-wp-13-sampling-enrichment.md |
| WP-14 | FFM loader/control-plane migration | issue-…-wp-14-ffm-migration.md |
| WP-15 | Kubescape integration PoC | issue-…-wp-15-kubescape-poc.md |

Dependency chain: WP-01 → WP-02 → WP-03 → WP-04 → WP-05 → {WP-06, WP-07} → WP-08 → WP-09 →
WP-10 → {WP-11 → WP-12}, WP-13; WP-14 after WP-10; WP-15 last. WP-02..05 live in
`ebpf-prototype/` and must not touch Gradle wiring.

## 10. Reuse Map (existing components, do not re-implement)

| Need | Existing component |
|---|---|
| Strategy-neutral collection + drop accounting | `ProfileCollector`, `CollectorDrain`, `ObservationMerger` (`:profiler .../collector/`) |
| Reserved integration seam | `EbpfCollector` stub ("live attach not implemented; would need a privileged sidecar") — Tier E *is* that sidecar |
| Privilege ceiling probe | `EbpfCapability.probe()` (`:profiler EbpfLoad.kt`) |
| SCM_RIGHTS over AF_UNIX (both directions) | `SupervisorSocketUtils`, `SocketManager.receiveDescriptor`, `FileDescriptor.adopt` (`:platform`) |
| Raw syscalls incl. `gettid`, `mmap`, socket set, `pidfd_*` | `SyscallInvoker` (`:platform ffi/internal`) |
| Daemon reactor state-machine pattern | `UnixListenDaemonMachine`, `SeccompDaemonEngine/Machine/Handler` (`:platform platform/daemon`) |
| Event identity model | `ProfileObservation.correlation(tgid, tid, ktimeNs)` |
| FFM memory discipline | `ManagedSegment`, `NativeArena`, `SegmentPool`, `LayoutValidator` (`:platform ffi/memory`) |

Non-refactor rule: leave the `TraceEvent` / `SyscallEvent` / `ProfileObservation` trio as-is
(wire shape vs neutral model separation is intentional).

## 11. Risks & Honest Limitations

| # | Risk | Direction | Mitigation |
|---|---|---|---|
| 1 | Marker latency: classic x86 uprobe ≈ trap + out-of-line step (µs-class), fires once per transition | measurable | G1 microbenchmark; optional skip-if-unchanged guard (ThreadLocal compare before downcall — semantics-preserving). If unacceptable ⇒ evidence for Tier E Fast, not for keeping two backends alive |
| 2 | Pre-attach window: scopes entered before attach emit UNKNOWN | fail-unknown | documented accepted behavior |
| 3 | Stale-positive after detach mid-scope (see §5) | bounded | session-epoch reader contract; per-map scoping kills cross-epoch leakage |
| 4 | Wrong-binary misfire (probing stale `.so` copy) | benign (silent UNKNOWN) but confusing | `/proc/<pid>/maps` inode + build-id verification, loud failure |
| 5 | Virtual threads: carrier retains context across unmount mid-scope → sibling vthread inherits label (**wrong fact**) | unacceptable | fail-closed `IllegalStateException` on `isVirtual()` inside `withContext` |
| 6 | Task-storage creation failure under pressure | fail-unknown | count and emit nothing |
| 7 | Arch-specific argument registers (x86_64 `di` / arm64 `x0`) | minor | eliminated in the USDT variant (`bpf_usdt_readarg`); compile-time per-arch BPF source remains the plain-uprobe bring-up fallback |
| 8 | Ring-buffer drops | data loss, never corruption | drop counter per nr; `drainComplete=false`; never compile BoB |

## 12. Open Questions

1. **Public surface placement for `MazewallContext`** (WP-01): new tiny published module vs
   public API nested in an existing artifact. Decide during WP-01 review; default is no new
   module.
2. **Kubescape node-agent kernel baseline** (WP-15): confirm 5.15 floor matches the
   supported matrix of the deployment target before promising compatibility.
3. **UNKNOWN sampling policy** (WP-13): whether sampled UNKNOWN events (1-in-N) justify
   their ring-buffer cost; decide with WP-06 numbers in hand.

---

## Appendix A. External Reference Review: Oracle, *"From kernel to user-space tracing"* (A. Maguire, 2022-11-15)

Reviewed 2026-08-25. Source: blogs.oracle.com/linux/from-kernel-to-user-space-tracing;
sample code: github.com/oracle-samples/linux-blog-sample-code (branch
`kernel-to-userspace-tracing`). License of any borrowed snippet must be verified before use
(reference-only by default).

### A.1 What it confirms about our design

* Uprobe mechanics: registration records the instruction at the target offset and plants a
  breakpoint (x86_64 INT3) into **every VMA mapped from that inode**; the handler runs
  synchronously on the faulting task before the instruction executes (trap → handler →
  single-step/XOL). Independent confirmation of our §4.1 synchronous-ordering claim.
* Attach ergonomics: libbpf ≥ 0.8 supports attach-by-function-name and binary-name lookup
  (no hand-computed offsets) — matches our prototype-phase plan (WP-03).
* uretprobes patch return addresses (dispatcher trampoline). We deliberately avoid them:
  our exit path re-fires the same state-carrying probe instead. Their added fragility buys
  us nothing.

### A.2 USDT adoption decision

See §4.1.1. Summary: USDT is the userspace analogue of tracepoints — named probes with
declared arguments recorded in ELF `.note.stapsdt`, implemented atop uprobes. Adopted as the
production marker ABI because it converts our marker from an implementation detail into a
discoverable, verifiable, evolvable interface. Trade-offs accepted: `sys/sdt.h` build-time
dependency (prototype toolchain only), libbpf ≥ 0.8 for `attach_usdt` (prototype only),
custom `.note.stapsdt` parsing deferred to WP-14 with a documented plain-uprobe fallback
path. Semaphores rejected for v1 (no argument-prep cost today).

### A.3 Future async extension vocabulary (not v1 scope)

The synchronous tier keys context by task; asynchronous work (io_uring et al.) must key by
request identity. USDT gives us the emission side without new machinery:

```text
mazewall:async_submit(u64 operation_id, u32 context_id)
    → BPF: async_context[operation_id] = context_id
io_uring submit/complete tracepoints
    → recover request → context mapping at completion time
```

This preserves the invariant split: *synchronous execution → task storage; asynchronous
execution → request-keyed map*. Any such probe set is a separate future initiative gated on
the same fail-unknown rules; nothing in this document commits Tier E v1 to it.

## Appendix B. Gate Results (kernel 7.1.4-xanmod1 x86_64, rootful container, 2026-08-25)

| Gate | Result | Evidence |
|---|---|---|
| G0a | PASSED (3 runs) | Deterministic pairing via plain symbol uprobe: phases 42×5 → 7×3, silence after reset |
| G0b | PASSED | Identical event patterns through `usdt/…:mazewall:context_switch`; wrong-binary guard demonstrated (`stapsdt` notes present only in USDT artifact) |
| G1 | RECORDED | 10 M transitions per configuration, single-line results |

### B.1 G1 latency numbers

| Configuration | ns / transition |
|---|---|
| Plain uprobe, detached | 3.0 |
| Plain uprobe, attached | **1 324** |
| USDT, detached | 3.3 |
| USDT, attached | **596** |

* Attached USDT is ≈2.2× cheaper than attached plain uprobe on this kernel — empirical
  support for the §4.1.1 decision to make USDT the production ABI.
* Both variants confirm the µs-class prediction (§11 risk 1). Enter+exit costs
  ≈1.2–2.6 µs per scope: negligible for request-scale code with tens of scopes; material
  for tight inner loops ⇒ the skip-if-unchanged guard (WP-08) and "scope at boundaries,
  not inside loops" guidance are mandatory, not optional polish.
* Ring-buffer saturation under benchmark load was not exercised (the timed loop performs
  no syscalls; only 3 gate-wait events were observed, `dropped=0`). Drop-rate behavior
  under concurrent syscall pressure is measured separately in WP-06.
* Environment caveat: numbers are from one host/kernel; CI reruns on target kernels before
  any external claim (no public commitments per WP-12 policy).

### B.2 Harness notes

* Bench process holds on a gate file after dlopen+warmup so attachment precedes timing;
  first-run defect (attached rounds measuring detached cost due to loader flag rejection)
  was caught by the sanity expectation "attached ≫ detached" and fixed.
* The G0b silent-park failure (attach succeeding against a library the target had never
  mapped) motivated the harness rule: hard-fail unless `/proc/<pid>/maps` shows the marker
  library before attaching.

Reviewed 2026-08-25. Source: blogs.oracle.com/linux/from-kernel-to-user-space-tracing;
sample code: github.com/oracle-samples/linux-blog-sample-code (branch
`kernel-to-userspace-tracing`). License of any borrowed snippet must be verified before use
(reference-only by default).

---
