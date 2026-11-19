---
title: "HotSpot SBOB coverage and performance opportunities"
scope: "profiler"
date: "2026-10-26"
status: "analysis-only"
target_files:
  - "profiler/hotspot-agentless/gadgets/openat-stack/program.bpf.c"
  - "profiler/hotspot-agentless/internal/capture/collector.go"
  - "profiler/hotspot-agentless/dependencies.lock.json"
keywords: ["SBOB", "HotSpot", "OpenTelemetry", "coverage", "attribution", "performance"]
---

# HotSpot SBOB coverage and performance opportunities

The goal is an SBOB with **100% coverage, or as close as possible**: observe
distinct behaviors and attribute them correctly, rather than collect more CPU
samples. This 2026-10-26 analysis is **not an approved implementation plan**;
existing plans, collection limits and qualification claims remain unchanged.

## Evidence and comparison baseline

The comparison uses our pinned OTel/Gadget source, not a survey of latest upstream:
Gadget `7097db144ccd8dab15373f27ec98d2c3d22e4588` and its OTel profiler replacement
`ef6cf29492c8a980f889052903fa2dd0c0765cc0`. See the
[dependency lock](../../../profiler/hotspot-agentless/dependencies.lock.json) and
[local overlay inventory](../../../profiler/hotspot-agentless/patches/README.md).
Current-documentation lookup was quota-blocked. No comparative performance benchmark
was run; projected gains below are hypotheses.

Unlike periodic CPU sampling, our existing
[capture probe](../../../profiler/hotspot-agentless/gadgets/openat-stack/program.bpf.c)
already triggers stack capture at `__x64_sys_openat` entry, selecting PID and
process birth. CPU sampling is disabled in the pinned configuration. This is the
right foundation for rare behavior that CPU samples might miss; it is not a new
optimization to implement. Handler entry proves an attempt, not successful access.

The [independently reviewed native fixture](../reviews/2026-10-23-hotspot-public-native-attempt.md),
under **Independent original-evidence disposition — 2026-10-24**, establishes:

| Measure | Observed result | Claim boundary |
| --- | --- | --- |
| Marked operations observed | 400/400 unique operations | This fixture, not all application behavior |
| Useful route attribution | 380/400 = 95%; 190 per route; zero swapped routes | More than successful symbolization |
| Early attribution gaps | Tokens 0–3: `missing_reference`; 4–19: resolved without useful leaf/route frames | Cause not established |
| Selected stream | 401 records: 397 resolved, 4 unavailable | Includes one unmarked `done` operation |
| Compiled application evidence | 176 marked events | Not universal JIT correctness |

Global completeness and policy eligibility remain false. The
[observed-profile contract](../../plans/2026-10-18-hotspot-observed-profile-contract.md)
does not establish JIT metadata lifetime, class-loader identity or logical/virtual
thread ownership. Persisted-profile acceptance is a separate gate.

## Define coverage before reporting a percentage

Keep these dimensions separate; do not average them into an unsupported overall score:

1. **Behavior scope:** supported operations, resource types, processes and runtime
   configurations versus the explicitly intended scope. `openat` alone is narrow.
2. **Delivery:** retained occurrences versus selected attempts in a bound observation
   window. Use producer accounting and independent workload oracles where available;
   zero reported loss alone does not establish completeness.
3. **Attribution:** occurrences with useful, trustworthy application attribution
   versus all selected occurrences, retaining unavailable and incomplete outcomes.
4. **Workload coverage:** exercised scenarios and lifecycle phases versus a declared
   scenario inventory. Unexecuted paths remain unknown, not absent behavior.
5. **Persistence:** whether the final SBOB retains the accepted evidence, counts,
   identities, quality and observation boundaries through publication and reload.

Distinct behavior discovery and per-occurrence attribution are different measures.
Static inventory can reveal components or paths still unexercised, but is not proof
that those behaviors occurred. Finite observation cannot certify every possible
future execution of an arbitrary application.

## Candidate improvements

### 1. Investigate early discovery and readiness

Determine why the first 20 marked operations lack useful attribution. The
[collector's readiness path](../../../profiler/hotspot-agentless/internal/capture/collector.go)
checks attachment/lifecycle and identity; readiness need not mean every mapping or
unwind dependency is prepared. Earlier process discovery and metadata preparation
could improve coverage and move work out of the first operations.

Measure missing references and frame-quality failures separately before changing
readiness. Preparation cannot pre-populate JIT metadata that does not yet exist.
Do not warm up the application and exclude those operations from the denominator:
startup behavior belongs in the SBOB. Earlier observation is needed to claim
coverage of activity before the current attach window.

### 2. Broaden behavior semantics beyond one syscall entry

Evaluate filesystem, network, process execution and other selected behavior classes.
Distinguish attempted versus successful operations, supplied paths versus actual
resource identity, direct syscalls versus asynchronous operations such as `io_uring`,
and the target process versus relevant descendants.

Entry/exit correlation and resource-level hooks could improve meaning and breadth;
syscall names alone leave gaps. Additional hooks have real cost: use compact records
and scope-specific accounting. Broadening scope likely adds more SBOB value than
moving only this fixture from 95% to 99% attribution; that value is unmeasured.

### 3. Retain occurrences while sharing expensive enrichment

The existing path already separates events and stack definitions and uses bounded
enrichment. Improve those mechanisms rather than adding another cache by default:

**Compact event occurrence → shared stack definition → aggregated SBOB behavior.**

Repeated events can share symbolization and storage while preserving distinct
callers, resource/outcome differences, counts, identities, quality and time windows.
Attribution failure must remain visible alongside the behavior evidence.

The pinned [OTel dispatcher](https://github.com/inspektor-gadget/opentelemetry-ebpf-profiler/blob/ef6cf29492c8a980f889052903fa2dd0c0765cc0/support/ebpf/interpreter_dispatcher.ebpf.c)
looks up the stack-definition cache **after unwinding**. Better hit rates therefore
mainly save definition transport and downstream processing, not the stack walk.
Skipping walks for similar-looking events can miss a new caller. For the strongest
attribution mode, preserve event-triggered walks and first investigate redundant
enrichment, serialization and persistence. Any aggregation needs explicit semantics
and must not silently replace occurrence evidence or erase first-seen behavior.

### 4. Tune for event bursts, not inherited sampling assumptions

The dependency lock retains a 256-entry BPF stack cache, an 800 ms correlation
timeout and `otel_samples_per_second_buffer_sizing: 20`, with CPU sampling off.
The latter is a sizing input, **not a 20-event/second capture limit**. These values
deserve measurement against syscall bursts; they are not proof of a bottleneck.
The overlay inventory records ABI-v2 cache keys of `24 + 3072 * 8` bytes (about
24 KiB), so increasing capacity has meaningful memory cost.

Measure cache eviction/miss rates, definition latency, queue occupancy, memory and
loss under controlled bursts. Larger caches or buffers might reduce churn and
recovery work at a memory cost. More workers can increase contention; longer
timeouts can retain stale work rather than fix missing definitions. Preserve
identity checks, bounded ownership and final accounting when evaluating alternatives.

### 5. Expand observation and workload representativeness

Evaluate startup, steady state, shutdown, error paths, configuration variations and
rare operations. Track newly discovered behaviors by scenario, not just duration or
sample count. Longer runs with no new behavior do not prove completeness.
Covering more phases increases observation cost but need not increase per-event
overhead. Keep the original 400-operation/four-worker/20 ms fixture intact as a
comparison baseline; additional scenarios would be separately selected work.

### 6. Address JIT lifetime and asynchronous ownership explicitly

Larger buffers do not solve code churn, unloading, metadata reuse or logical task
ownership. A physical stack identifies what is executing; it need not identify
the logical request that initiated asynchronous work. Generation checks do not
establish a metadata lifetime guarantee.

If near-total causal attribution is required, evaluate an **optional cooperative
JVM mode** alongside agentless capture for lifecycle metadata and logical context.
This is a larger architectural option with integration and runtime costs, not an
automatic 100% solution or a selected dependency. Never substitute a later valid
stack for the original unavailable occurrence.

## Evidence needed before selecting changes

Suggested priority: early attribution gaps; broader behavior semantics; duplicate
enrichment/storage work; measured burst capacity; then JIT/async fidelity. Workload
representativeness applies throughout. This is advice, not a revised plan.

Future comparisons should retain the original baseline and separately measure
coverage by the dimensions above, collector CPU/RSS, application throughput and
tail latency, event/definition bytes, and pressure/loss indicators. Report cold
startup separately from steady state while retaining both in scope. Use independent
behavior/route oracles and preserve earlier failures and unknowns.

No speedup, whole-application coverage percentage or deployable persisted SBOB was
established by this analysis.
