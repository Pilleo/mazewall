# Native correlation-identity evidence and pending allocator review

Historical investigation and approval request retained below. The operator later
approved the [bounded contract](2026-10-18-hotspot-correlation-allocator-contract.md);
the [allocator evidence](2026-10-18-hotspot-correlation-allocator-evidence.md)
records completed Task1/R1 source/isolated checks, Task2 bounded full-asset/public-native
qualification and Task3 finite cache/publication/drain gates. Final review's minor
corrections, scoped re-review and integration are separate in the
[execution plan](../superpowers/plans/2026-10-18-hotspot-correlation-allocator.md).
None of that changes the failed capture or its unknown exact trigger.

## Decision and evidence boundary

The original four-carrier capture contains a real **nine-event/two-TID correlation
alias**. Independently, executable characterization of the **unchanged production
C** demonstrates three distinct-key uniqueness failures under equal miss-time
clocks. This establishes a sufficient allocator defect, **not the historical
trigger** and not a completed production correction. The original failed capture
remains refused; its later successful control does not repair it.

This is a documentation/backlog follow-up at base
`8cb57bad331a382a1bdd13be8be4d4294e7f7c56`, branch `feat/hotspot-followups`.
The [scoped MEDIUM issue](../internals/backlog/resolved/issue-20261018-035018-153-replace-clock-only-native-correlation-id-allocatio.md)
requests operator design review before any allocator/map/layout/failure-semantics
implementation or pin advancement. Existing fatal identity guards remain required.
Production remains pinned Temurin25.0.4.1+1-LTS, `-Xint -XX:+UseSerialGC`,
synchronous `openat_handler_entry`. JIT/GC/Loom lifetime, broader reliability and
complete policy coverage are not qualified by these observations.

## Retained evidence locations and provenance

All local paths below are relative to the authorized checkout
`/home/leanid/Documents/code/java/jseccomp/.worktrees/hotspot-review-remediation`.
They are retained, ignored investigation artifacts, not distributed build assets.

- `R` = `.superpowers/sdd/2026-10-17-hotspot-production-loss-binding/identity-investigation`.
- `N` = `R/native-characterization`; `A1` / `A2` = `N/attempt-1` / `N/attempt-2`.
- `H` = `profiler/hotspot-agentless`.
- `P618` / `P270` = `H/build/deps/profiler-` plus respectively
  `618c3d67d71e86dfb0befb13f73498402af6110f1f2fceb3784c53785b0da00c` /
  `270a89f6dafb03afe2131b219f98673e30be79223eb68a11a5703b66d8f6a127`.
  `G618` / `G270` are the corresponding `gadget-` prepared trees.
- `O` = `profiler/build/reports/hotspot-upstream-generation-candidates`;
  `S` = the same path with `-receipted` appended.

`R/report.md`, especially section 7, records the research and its completed offline
C continuation. `R/facts.json` retains original capture/source receipts;
`N/verification.json` independently audits sources, inputs, receipt order and raw
outputs. Task4 rehashed those receipts, including both attempts' selected compiler
inputs, binary and run outputs, and independently decoded the original JSONL and
native key/value/definition bytes. It did not rebuild or rerun characterization.

## Original capture: confirmed alias, correctly fatal refusal

Original files under `O/virtual-carriers-4/` remain byte-preserved:

| File | Bytes | SHA256 |
| --- | ---: | --- |
| `collector.log` | 3552 | `4421ec7981502bfd0ec618dfcf431c762577167e989088e1d72f3939365c02a5` |
| `capture.jsonl` | 652684 | `3ba14c697c7fad2585cdfc4629c99c46c3ea16b92faad1cac27034e190c27814` |
| `cleanup.json` | 641 | `281b6f85fcc5879116400bde34c84a17557a484b94637cd2ee5c5f1a0f271ed0` |
| `identity.json` | 229 | `b252a9a0a5e6bb4d534b0f6241ac78a55cdb32b4f89c8b8c84e89fbb63e6500c` |

The JSONL has 524 rows: 1 attached, 261 events, 261 enrichments, 1 summary.
All 261 event IDs are unique; 259 events have a nonzero reference, with nine distinct
nonzero IDs. Exactly one ID, **516936189570219**, spans two task pairs. Every
affected event has session `82b4e1f068e1c8c929115837087468ee`, PID **621929** and
birth ticks **51693282**:

| JSONL line | Event suffix after session | TID | CPU | `capture_boot_ns` |
| ---: | --- | ---: | ---: | ---: |
| 233 | `:5:1` | 622909 | 5 | 516936189537437 |
| 234 | `:4:1` | 622910 | 4 | 516936189538013 |
| 238 | `:4:2` | 622910 | 4 | 516936189730803 |
| 240 | `:5:2` | 622909 | 5 | 516936189752317 |
| 243 | `:4:3` | 622910 | 4 | 516936189872641 |
| 247 | `:11:1` | 622910 | 11 | 516936190198717 |
| 250 | `:5:3` | 622909 | 5 | 516936190364764 |
| 254 | `:10:1` | 622910 | 10 | 516936190711797 |
| 257 | `:7:1` | 622909 | 7 | 516936190759345 |

TIDs **622909 / 622910** reuse the ID four/five times, including after CPU changes.
The first affected enrichment is `unavailable / lookup_error` with
`stack definition task identity mismatch`; the other eight are cancelled. Original
`collector.log:8`, the terminal enrichment, summary and retained cleanup exit 1
agree. The summary remains **261 received / 227 resolved / 34 unavailable / zero
recovery attempts / `terminal_accounting_complete:false`**. Checked resource
retirement does not admit a failed capture.

The first two occurrence BOOTTIME values differ by **576 ns**. They are not
allocator-helper observations, and subtracting the numeric ID from them is not a
valid cross-clock measurement. Raw definition packets, contemporaneous LRU
keys/values, scratch contents, helper returns and a live loaded-program receipt
were not retained. Equal historical allocator clocks and historical transport
corruption cannot be distinguished from these bytes.

The supplemental four-carrier capture SHA256 is
`174c8ca9650af152be539d9b0b819c1c662cf838db2da6981c31461246499785`.
It has no cross-task alias, as do the other ten retained control captures.
`R/original-red.json` records the artifact invariant's expected exit **1**;
`R/supplemental-control.json` records exit **0** on separate unchanged bytes.
Neither is a newly passing allocator implementation or a reliability estimate.

## Source-backed allocation and consumer contract

The authored dispatcher overlay is
[`H/patches/profiler-epoch-capture.patch`](../../profiler/hotspot-agentless/patches/profiler-epoch-capture.patch)
(dispatcher hunks at lines 65–100). The actual included dispatcher in both `P618`
and `P270` is byte-identical, SHA256
`b45f60726a71b364da2183c7846a04ebd32a7fa80c167f8baed20ed3945cdbc3`.
Its relevant source lines are:

- **126–154:** shared per-CPU return/scratch maps and a 256-entry LRU hash.
- **338–358:** enabled stable-epoch check before publication.
- **364–388:** zero and populate full key: PID, TID, 16-byte HotSpot identity and
  3072 raw frame words (`types.h:804–809`, 24600-byte key, uint64 value).
- **392–396:** exact-key hit returns the existing ID without another definition.
- **399–407:** miss assigns `u64 correlation_id = bpf_ktime_get_ns()`, writes that
  same value to LRU/return/trace and sends the definition. There is no sequence,
  reverse-value uniqueness check or collision rejection. Distinct full keys can
  legitimately store the same value without a kernel hash-map malfunction.

The complete included `unwind_stop` is source lines **278–410**, byte interval
**[9643,14341)**, SHA256
`10e85253cc09c5cdc3e8e73a591287c66b37bac88fdb3559dda067478c273b1f`.
The before-build receipt's `end_line:409` counts completed newlines before the final
brace; the bytes/hash are correct. That metadata off-by-one is retained, not edited.
Included `types.h` SHA256 is
`b0f0d47f8187ab0620e96f97491d473142a766311745aa1f9c1ab1c0c4f67317`;
`tracemgmt.h` is `0824c374b1a6f196d927a77d1c06311f9de3ce96e6de88dbf24c23bee93f8cc4`.
All nine included headers/sources match original, current and local snapshots.

`G270/pkg/symbolizer/otel/otel.go:48–52,77–92` indexes definitions, waiters and prune
candidates by ID alone. The canonical
[`structured.go.txt`](../../profiler/hotspot-agentless/patches/gadget-additions/pkg/symbolizer/otel/structured.go.txt)
checks strict conflicting task/epoch/redelivery at lines 35–44 and lookup task/epoch
at 82–97. Legacy `storeTrace:61–68` can overwrite an ID entry; exact-task lookup
still refuses the other task. That separate immutability-hardening concern is not
an allocator solution or authorization to change resolver namespaces. The original
candidate used unknown identity/protocol 1, not strict safepoint-epoch mode; the
reviewed refusal path is therefore the exact-task lookup, subject to historical
binary/source linkage remaining **INFERRED/UNVERIFIED**.

IDs are opaque, not ages. `G270/pkg/symbolizer/otel/otel.go:128–201` prunes only
after two successful cycles absent from the producer LRU's ID membership; it
ignores `now/ttl` and skips an aborted scan. Lookup uses separate event BOOTTIME
(`otel.go:329–405`); canonical `internal/capture/enrichment.go:135–179` retains the
one-second occurrence deadline. The allocator uses MONOTONIC-like time excluding
suspend, while event age uses CLOCK_BOOTTIME including suspend. The official
[Linux v6.12 UAPI helper contracts](https://github.com/torvalds/linux/blob/v6.12/include/uapi/linux/bpf.h)
promise clock semantics, not unique allocation values. Optional legacy pdata export
casts IDs to `int64` (`P270/reporter/internal/pdata/generate.go:262–266`).

## Actual unchanged-C outcomes and receipts

`A2/characterization.c:8,38` includes the complete unchanged production types and
dispatcher. `invoke:166–200` initializes valid synthetic native-frame fixtures using
production helpers and directly calls `unwind_stop(&ctx)` at line 190. The real
`send_trace` emits the packet. Helper boundaries alone are mocked: explicit clocks,
exact-full-key map lookup/update, checked copies/epoch reads and perf output capture.
Two independent simulated CPU slots execute serially; no race, corruption or
scratch interleaving is needed. The capacity-256 map mock does not exercise LRU
eviction. Synthetic frames are not live JVM stacks.

| Scenario | Distinct key input | Miss IDs | Uniqueness result |
| --- | --- | --- | --- |
| `cross-task` | PID 621929; TIDs 622909 / 622910; same frames, unknown epoch; key byte 4 differs | 516936189570219 / 516936189570219 | **RED, exit 1** |
| `same-task-frames` | Same task, stable epoch 2; frame PC 21 / 22; key byte 24 differs | 516936189570219 / 516936189570219 | **RED, exit 1** |
| `same-task-epoch` | Same task/frames; stable epoch 2 / 4; key byte 8 differs | 516936189570219 / 516936189570219 | **RED, exit 1** |
| `distinct-clock-control` | Same task-difference fixture; second clock one greater | 516936189570219 / 516936189570220 | **PASS, exit 0** |

All four structural and same-key replay checks pass: four invocations produce two
misses, two hits, two clock calls, four return writes and exactly two definitions.
Replays return their own original ID without a further definition. Each emitted
packet is 760 bytes: 744-byte production prefix plus two frame words. Actual
compiled `Trace` size is 25320, correlation offset 720, epoch offset 728; Task4
decoded retained packets and compared task/epoch/frame/ID fields with their keys
and values. All four native-run stderr files are empty; no harness-error exit 2,
timeout, panic or skip is credited as a RED.

Attempt 1's compiler exit **1** was the harness declaration conflict
`static declaration of 'get_usermode_regs' follows non-static declaration`.
It is **not** the regression RED. Attempt 2 removed that unnecessary test binding;
production C was unchanged. It built successfully with Go **1.26.8 linux/amd64**
and GCC **15.2.0 (Ubuntu 15.2.0-16ubuntu1)**. Each attempt receipted 57 selected
packages, 643 selected source/header paths and 21 compiler/linker inputs before
building. The driver snapshots are `A1/run-receipted.py` and `A2/run-receipted.py`:
the original receipt's live `N/run.py` path advanced between attempts, so its
historical bytes are verified against those retained snapshots.

| Retained artifact | SHA256 |
| --- | --- |
| `A2/build-inputs-before.json` | `e79a9bf89f19b7f76cf2838d8b07806c9e59b58a600fef6aeeda9dd944365a22` |
| `A2/characterization.c` | `dbc2a6d7ef74f7ac4c0aa68e7b2c394a965c1013c80b468af7caedf5dcf21680` |
| `A2/characterization` | `5ca413d9242271881b35d360134859d170017929be14b6e99779613c2910ec9c` |
| `A2/binary-before-execution.json` | `9d86522331242c3d5c5735cf9ac5a7bd6f0670ba9e92beac9d45b99ca8d6304a` |
| `A2/results.json` | `c9c4d0593d6e188375561eea267675b83c780d4029cbe05cbb3484fd868a9f89` |
| `A2/cross-task.stdout.log` | `e84e9b096e346b214ccabed4300402bc75ad9cbcf16eb31e037796560085812c` |
| `A2/same-task-frames.stdout.log` | `5c15e62d8410642343057c7453ac28b7936b1692145599328b7c71da2572f10b` |
| `A2/same-task-epoch.stdout.log` | `0f00b699fe6b4e855cb3c1e521cc2a2a55534dcf686697577a15f172f81aca2b` |
| `A2/distinct-clock-control.stdout.log` | `4d7d691541ddba7d6ac08697bfafa9213b68d0e7e4fa2425007481cfe6098260` |

Adjacent `*.command.json` records retain actual argv/cwd/environment/timeouts/exits
and log hashes. `A2/<scenario>/execution-inputs-before.json` precedes each run;
each directory retains `key-{0,1}.bin`, `value-{0,1}.bin`, `definition-{0,1}.bin`.
The binary was hashed before every invocation. Original commands, **already
executed during research and not rerun for Task4**, were:

```bash
# cwd: authorized checkout; runner's native build cwd: profiler/hotspot-agentless
python3 -B .superpowers/sdd/2026-10-17-hotspot-production-loss-binding/identity-investigation/native-characterization/run.py
python3 -B .superpowers/sdd/2026-10-17-hotspot-production-loss-binding/identity-investigation/native-characterization/run.py 2
# Actual build argv; H and A2 are the path abbreviations defined above:
/usr/lib/go-1.26/bin/go build -x -work -buildvcs=false -mod=readonly -modfile "$H/build/experiment.go.mod" -o "$A2/characterization" "$A2/characterization.go"
# Each invocation's cwd: its A2/<scenario>/ directory
"$A2/characterization" cross-task
"$A2/characterization" same-task-frames
"$A2/characterization" same-task-epoch
"$A2/characterization" distinct-clock-control
```

Builds were bounded at 150 seconds and native invocations at 10 seconds, with no
timeout. Offline settings included `GOPROXY=off GOSUMDB=off GOTOOLCHAIN=local`;
isolated caches/work files remained within each attempt. These selected-input
receipts are not a hermetic snapshot of all implicit shared libraries/generated
CGO files. Host-C/helper mocks do not exercise the eBPF verifier/JIT, real kernel
maps/context concurrency, live perf/ring transport, JVM stack walking/metadata,
Gadget event copying, resolver execution, pruning or recovery. Source inspection
and prior explicit live proofs remain separate evidence categories.

## Proposed approval boundary and future gates

The exact contract and implementation remain **proposed, pending operator design
review**. The bounded direction is shared same-map-lifetime **positive non-reusing
atomic ID allocation**, with zero reserved for missing reference and IDs at most
`INT64_MAX` for signed legacy export compatibility. All variants sharing the LRU
must share allocator lifetime; no independent reset while LRU, definitions,
waiters, pending events or resolver session can retain an ID. Specify saturation
and an explicit bounded-contention failure: no wrap, unbounded spin, nested-context
deadlock, time/random fallback or reused positive ID. Map/layout and exact
failure/publication semantics are not selected by this documentation.

Keep the full key, uint64 value, wire/ABI and buffers unchanged; BOOTTIME deadlines
and membership pruning remain independent of ID magnitude. Native enrichment
workers4, pending events1024, pending payload16MiB, producer LRU256, frame cache16384,
frame data3072words and perf data524288bytes/possible CPU remain fixed. Preserve
fatal task/epoch/frame guards and EPERM/EACCES propagation. Resolver namespace
changes and silently accepting a newer definition for an old occurrence are not
authorized. An atomic number alone cannot protect a misbound trace/key/return field.

Before any approved correction is promoted, require:

1. Actual-C RED/GREEN plus clock-allocator mutant; same-key reuse, distinct
   task/frame/epoch keys, zero/positive boundary, saturation and bounded contention.
2. Real shared-map/context ownership and multi-CPU concurrency for supported entry
   variants, including whether kernel guards admit the tested nesting/interleaving.
   Check insertion/output failures, same-key concurrent publication, eviction and
   ordered drain; host mocks alone do not satisfy this gate.
3. Immutable resolver/exact-task/epoch/frame checks, reordered definitions, waiter
   lifetime, recovery and membership-pruning regression checks with non-time IDs;
   retain guard-removal negative controls and explicit native failure handling.
4. Renew current-source qualification **before all fourteen pin-consumer
   advancements**: canonical/mirror tooling assets/prepare tests (4), canonical/
   mirror JIT/GC audit source/tests (4), experimental candidate audit test (1),
   Kotlin assets/protocol (2), and Kotlin assets/protocol/child fixtures (3).
   Derive the actual ordered-input patch hash; do not invent a digest or reuse pins.
5. Reproducible reviewed assets, both embedded BPF architectures/generated layouts,
   full local OCI graph/byte receipts, legacy rendered/pdata and ABI compatibility,
   and actual public-native evidence under unchanged runtime/deadline/ownership
   bounds. Cached/skipped suites do not substitute for explicit live execution.

Current locked patch pin remains
`270a89f6dafb03afe2131b219f98673e30be79223eb68a11a5703b66d8f6a127`.
Task4 verified all current asset bytes (nine manifest entries) and the complete
reachable six-blob local OCI graph; current manifest SHA256 is
`4bc6918073cf5c403d931aeb98868cae4dc6b4e2e3eaf1db2d8a80e7a00b084f`.
This is a current-bundle preservation check, not the historical `618c…` bundle or
proof of its loaded program. Earlier hashes and failed observations in the
[upstream generation evidence](2026-10-17-hotspot-upstream-generation-evidence.md)
remain historical facts. No runtime fix or lifetime/reliability promotion follows.
