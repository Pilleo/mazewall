---
title: "Tier E Initiative: eBPF Semantic Enrichment (uprobe + task storage)"
severity: "ENHANCEMENT"
status: "open"
priority: high
component: "profiler"
target_modules:
  - ":platform"
  - ":profiler"
  - "ebpf-prototype"
target_files:
  - "docs/internals/designs/profiler/tier-e-design.md"
  - "ebpf-prototype/"
effort: "xlarge"
autonomy: "supervised"
open_questions: false
dependencies: []
---

# 🟢 [Severity: ENHANCEMENT]: Tier E Initiative — eBPF Semantic Enrichment

**Context:** mazewall profiles syscalls (Tiers P/S/A) but cannot answer *"which application-level
operation caused this syscall?"*. Tier E adds a semantic attribution plane: Java code declares a
scope (`MazewallContext.withContext(PDF_PARSE) { ... }`); an eBPF uprobe on a tiny native marker
function records the scope into BPF task-local storage; a `sys_enter` tracepoint reads it back per
syscall and emits attributed events. Target shape is deliberately simple so it can later be
recreated inside Kubescape's node-agent as optional event enrichment.

The architecture is **locked** — uprobe + task storage is the sole correctness path. The mmap-slot
design exists only as a recorded deferred optimization ("Tier E Fast", see design doc §6). Do not
re-litigate either decision in work packages; escalate instead.

**Authoritative design document:**
[docs/internals/designs/profiler/tier-e-design.md](../../designs/profiler/tier-e-design.md)

## The nine invariants (every WP must re-read before starting)

1. Profiling/enrichment/detection hints only — never an enforcement input.
2. Context metadata is tracee-controlled and forgeable — `UNTRUSTED ATTRIBUTION METADATA`.
3. Fail UNKNOWN, never guess.
4. Platform threads only; virtual threads rejected fail-closed.
5. One BPF map set per session epoch; never recycled.
6. No bpffs pinning in v1; daemon owns all FDs.
7. Session death is terminal (`RUNNING → DEAD`); no reconnect-and-splice.
8. No policy generation from Tier E; `toPolicy()` must not exist.
9. Ring-buffer loss marks observations incomplete (`drainComplete = false`, drop counters).

## Gates

| Gate | Criterion | WP |
|---|---|---|
| G0 | Deterministic pairing, single thread: marker(42) → context==42 | WP-03 |
| G1 | Marker latency measured at realistic transition rates | WP-03 |
| G2 | Zero wrong pairings under churn/nesting/reuse/window stress | WP-05 |
| G3 | Oracle parity matrix: incorrect attribution == 0 | WP-10 |

## Work packages (execution order)

| WP | Scope | Issue |
|---|---|---|
| WP-01 | In-memory `MazewallContext` API + guards | issue-20260825-023931-tier-e-wp-01-mazewall-context-api.md |
| WP-02 | Standalone C eBPF syscall collector | issue-20260825-023932-tier-e-wp-02-collector-prototype.md |
| WP-03 | Marker `.so` + uprobe + task-storage PoC (G0/G1) | issue-20260825-023933-tier-e-wp-03-marker-uprobe-poc.md |
| WP-04 | Session lifecycle & trust protocol | issue-20260825-023934-tier-e-wp-04-lifecycle-trust.md |
| WP-05 | Concurrency stress suite (G2) | issue-20260825-023935-tier-e-wp-05-concurrency-stress.md |
| WP-06 | Noise budget & UNKNOWN counters | issue-20260825-023936-tier-e-wp-06-noise-budget.md |
| WP-07 | Container metadata association | issue-20260825-023937-tier-e-wp-07-container-metadata.md |
| WP-08 | FFM bridge client | issue-20260825-023938-tier-e-wp-08-ffm-bridge-client.md |
| WP-09 | `LiveEbpfCollector` integration | issue-20260825-023939-tier-e-wp-09-live-collector.md |
| WP-10 | Oracle comparison suite (G3) | issue-20260825-023940-tier-e-wp-10-oracle-comparison.md |
| WP-11 | Limited Java agent | issue-20260825-023941-tier-e-wp-11-java-agent.md |
| WP-12 | Performance harness | issue-20260825-023942-tier-e-wp-12-perf-harness.md |
| WP-13 | Sampling enrichment | issue-20260825-023943-tier-e-wp-13-sampling-enrichment.md |
| WP-14 | FFM loader/control-plane migration | issue-20260825-023944-tier-e-wp-14-ffm-migration.md |
| WP-15 | Kubescape integration PoC | issue-20260825-023945-tier-e-wp-15-kubescape-poc.md |

Dependency chain: WP-01 → WP-02 → WP-03 → WP-04 → WP-05 → {WP-06, WP-07} → WP-08 → WP-09 →
WP-10 → {WP-11 → WP-12}, WP-13; WP-14 after WP-10; WP-15 last.

## Hard process rules

* `ebpf-prototype/` stays **outside Gradle wiring** until Gate G2 passes.
* New external dependencies require explicit operator approval **per PR** (clang/llvm/bpftool +
  libbpf for the prototype; Byte Buddy and Spring Boot are *not* pre-approved).
* Kernel work runs via rootful Podman scripts; host-side `./gradlew build` must stay green at
  every step.
* Any kernel-behavior discovery gets its own backlog issue immediately (house rule).

## ❓ Open Questions

Tracked in the design doc §12 (public surface placement, Kubescape kernel baseline, UNKNOWN
sampling policy). Do not block WPs on them unless listed as a dependency of that WP.
