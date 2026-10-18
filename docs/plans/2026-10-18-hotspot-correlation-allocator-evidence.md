# Bounded native correlation allocator — Task1 evidence

## Disposition

The [approved contract](2026-10-18-hotspot-correlation-allocator-contract.md) is
implemented in authored native source. Actual-C regression/negative controls,
isolated real-kernel multi-CPU allocation, acquired-map loader ownership, generated
architectures/layouts and renewed decoder/guard characterization have passed their
scoped checks. **Full reproducible helper/OCI and actual public-native qualification
of this graph remain Task2.** The allocator issue remains open.

The original failed capture remains failed. The three unchanged-C equal-clock REDs
establish a sufficient allocator defect, not its historical trigger. Original
artifact bytes, failed result and unknown source/interleaving linkage are retained
in the [identity investigation](2026-10-18-hotspot-correlation-identity-evidence.md).

## Source and ownership

Canonical source is `profiler/hotspot-agentless`; the experiment contains explicit
file copies of the relevant additions, patch, tests and review guide. No generated
dependency/module-cache source was edited. Normal preparation applies zero-fuzz
patches to the checksum-verified locked modules and generates both embedded BPF
architectures and Go layouts. No dependencies, public API/reset accessor, profile,
resolver namespace or linear-scan design changed.

`patches/profiler-epoch-capture.patch` creates production
`support/ebpf/correlation_ids.h` and includes it from the actual dispatcher. The
header defines one ARRAY/u32/u64/1 and the actual bounded CAS helper. A miss calls
it once. Missing state, state at/above INT64_MAX or 32 lost comparisons returns
zero, clears the return slot and publishes neither cache entry nor definition.
The existing hit path precedes allocation and keeps returning its stored ID.

The full PID/TID/HotSpot-identity/raw-frame key, LRU256, uint64 value, trace layouts,
BOOTTIME deadlines, native buffers and fatal guards are preserved. Publication
failure does not reset/reuse an ID. Failed cache insertion may still deliver a
definition; failed definition output may leave a hit with a missing definition.
Those are existing availability/recovery outcomes, not a stronger delivery claim.

Prepared `tracer/tracer.go:398–408,559–598,638–691` acquires the named maps once
into `ebpfMaps`, then rewrites all program references to those acquired maps and
removes their creation specifications. `loadPerfUnwinders:734–780` selects the perf
unwinders/native entry; `loadProbeUnwinders:803–844` selects the kprobe variants and
only substitutes the tail-call table. Both unwind-stop variants therefore use the
same allocator and LRU. Tracer ownership retains the map with the existing producer
and resolver namespace; no reset/independent replacement is introduced.

The native loader check executes those actual acquisition/rewrite functions on the
allocator/LRU, checks ARRAY layout/map ID, then inspects both real embedded
unwinder specifications for the acquired map objects. It does **not** load/attach
the entire profiler. Codanna blast-radius returned exit2, INDEX_MISSING /
CODANNA_UNAVAILABLE: index evidence is incomplete, supplemented by exact callers.

Gadget `include/gadget/user_stack_map.h` keeps program-type-correct kprobe/perf
request/read wrappers; capture copies the uint64 reference without interpreting
its magnitude. The actual resolver's ID dictionary/waiters, strict task/epoch/frame
guards, recovery exact-key/value recheck and two-absent-membership-cycle pruning
remain. `internal/capture/enrichment.go:135–179` uses the original event BOOTTIME,
not the ID, and zero remains `missing_reference`; identity and permission errors
remain fatal. Legacy `storeTrace` overwrite hardening remains separate.

## Graph and toolchain

The ordered recipe in `internal/tooling/prepare.go:50–101` hashes 89 actual named
inputs, in order, as `name + NUL + bytes`. Actual preparation and independent
inventory agree on:

`962b27903e20422116ca8528b7edbc8f19eafc94f986907461bd8fb1ded9f976`

Gadget base remains `7097db144ccd8dab15373f27ec98d2c3d22e4588`, sum
`h1:HoWDSctBKujfwuYDS+leb3To6Nn06kx1Zt48rGdAp3s=`. Profiler replacement remains
`ef6cf29492c8a980f889052903fa2dd0c0765cc0`, sum
`h1:uAAxfhFJY2k26LvrHwdZdy7gTRo3ZFSTeSrWSPKjirk=`.

Locked images are unchanged:

- Builder: `ghcr.io/inspektor-gadget/gadget-builder@sha256:dba339d2a2f189ae5fcdc08730e6cac128d4ed30c3af42e5fb93a5c8cf4c77aa`.
- Go: `golang@sha256:fb612b7831d53a89cbc0aaa7855b69ad7b0caf603715860cf538df854d047b84`.
- Runtime reserved for Task2: `eclipse-temurin@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6`.

Actual builder is Clang/LLVM18.1.8, cgo Go1.26.6. Actual locked Go-image compiler
is **Go1.26.0**, not the host's Go1.26.8. Native Go test binaries use the locked
image, offline/read-only module cache, `-buildvcs=false` and the actual generated
`build/experiment.go.mod`. Host race/source controls use Go1.26.8. Kotlin uses
`/usr/lib/jvm/java-25-openjdk-amd64`. Preparation retains the existing BPF v2 target.

Generated canonical/mirror bytes match:

| Artifact | SHA256 |
|---|---|
| `support/types.go` (unchanged ABI) | `95ae3add323d8c98f25a1fec7ae27d4cd33411f57db49c253092ee4c9e510248` |
| `support/ebpf/tracer.ebpf.amd64` | `7c0211e987d823c5c8ad13173d38721d3eb4ef4786c00e700f0d7d93831ed3a0` |
| `support/ebpf/tracer.ebpf.arm64` | `df2189c0ef77ea0749258f44537fd25d6f15a497d03a2a1d98298bcaabd655cd` |
| Actual `correlation_ids.h` | `e7b8b4470a0f67bc7d8f0b52e4b0efaea9c1bf2cab3c871a0d126c675736d7c2` |
| Actual dispatcher | `c951e0019a9a76eb7510a3d3f9d4aaa68b0abdcf403b03d3133960a17bfda0ca` |
| Actual unchanged loader | `7148937d6be01b9683f76d726d1ee224d1989a3ff9c2299ba32603c8ed2d7cf7` |
| Actual unchanged HotSpot decoder | `dfe095a7b7aa4d35ed23f556e3c14096adfff74eb78e578ad9433be6f662d9ea` |

The actual prepared decoder still extracts captured `ptrCheck` from the low32
frame bits (`instance.go:902–906`), passes it to `getJITInfo:929`, checks cached
compile-ID equality at435–438 and freshly read compile-ID mismatch at451–453.
Current-source controls pass, and separately removing either guard fails the
expected invariant. These observations preserve source audit truth; they do not
prove metadata/code lifetime through concurrent reclamation.

Only after that current-graph inspection/characterization were all fourteen literal
consumers advanced: canonical/mirror tooling assets and preparation tests(4),
canonical/mirror JIT/GC audit source/tests(4), experimental candidate fixture(1),
Kotlin assets/protocol(2), and Kotlin assets/protocol/child fixtures(3). The accepted
pin check was not weakened for preparation; old-bundle verification still refuses
the preserved `270a…` bundle (`unsupported asset version/ABI/provenance`, exit1).

## Executed checks and retained receipts

Local retained evidence root is
`.superpowers/sdd/2026-10-18-hotspot-correlation-allocator/evidence` (`E` below),
inside the authorized worktree. These are retained investigation outputs, not
distributed assets. Exact argv/cwd/environment/time/exit and raw output hashes are
in adjacent JSON receipts; selected Go packages/files and overlay sources precede
builds. Materialized native sources/compiler/image inputs precede native builds.
C/kernel/source-control binaries are separately hashed before execution.

| Check | Actual disposition |
|---|---|
| Retained unchanged actual-C baseline | Rehashed original harness/binary/results; three genuine equal-clock invariant RED exits1 retained, not rerun wholesale |
| Complete actual dispatcher GREEN | Distinct task/frame/epoch keys get1,2 despite equal clocks; two hits reuse originals, exactly two definitions, no clock allocation calls |
| Actual-C edges | 1, 2^53+1, INT64_MAX; missing/invalid/saturated state; 32 failed CAS→zero/no publication; 31 failed CAS→success on32; insertion/output helper faults without reuse |
| Old-clock miss mutant | Builds, then fails positive-distinct-ID invariant with exit1 |
| Real wrapper | Actual production header/helper, acquired map28036 ARRAY/4/8/1; 4096 unique positives, zero unavailable, CPUs0–7 verified by BPF helper; high/saturated/invalid states checked |
| Real loader acquisition | Map28032; both actual embedded unwind-stop specifications reference that acquired allocator/LRU; checked map closes |
| Current decoder controls | Cache hit/stale-cache/fresh-read cases pass; cache-guard and read-guard mutants each fail their expected invariant, not compilation |
| Resolver guard negatives | Removing task or epoch lookup refusal each fails the non-time-ID test |
| Canonical Go race | Tooling/audit/capture, actual prepared OTel resolver, support and tracer suites pass; includes waiters/recovery/pruning/deadline/permission cases |
| Mirror Go race | Tooling/audit and retained candidate suites pass |
| JDK25 focused checks | Kotlin compile, actual `checkKotlinAbi`, ktlint and assets/protocol tests pass; raw warnings/cached tasks retained |

Representative receipt hashes (full inventory: `E/task1-artifacts-index.json`):

| Retained artifact | SHA256 |
|---|---|
| `final-pin-graph.json` | `a6fa8457aa2712c8f1fa49f8daa5b99890030495565584de6cfb598e12bcb609` |
| `actual-c-final/inputs-before-build.json` | `8edd2a201381dba10f8e9f5b042fb837a95ab92c3649db063004ab83ea8bc365` |
| Actual-C GREEN executable | `16a76bf77a0b9610843585f609b36b646c8af766ce571f6143d054c70edf9200` |
| Actual-C clock-mutant executable | `afe05ce0f0a6f12c05ae824d031765889e846a0df0b89f18d4f9de7068a08cb6` |
| Production-header BPF wrapper object | `c96713e1787b3392bd17ae0c93f106edf5d7df2e9638c88e137314c872ffb7c9` |
| Final kernel test executable | `1e77013b75078b7fa7f6357373e0d35b481eed67644563305dfe29e9369a3115` |
| Final allocator kernel result | `ca6f2015ff40ee99a95e0e4192d0930d1bfd243683bc94198cc63270a1694772` |
| Loader kernel result | `d67ab5746092d63f4eea0cc5825398e279bfc030d6bca501d934d1a7b59a322c` |

Maintained actual-C invocation, cwd `profiler/hotspot-agentless`:

```bash
python3 -B tests/correlation/run.py build/deps/profiler-962b27903e20422116ca8528b7edbc8f19eafc94f986907461bd8fb1ded9f976 ../../.superpowers/sdd/2026-10-18-hotspot-correlation-allocator/evidence/actual-c-final
```

`tests/correlation/README.md` documents the kernel build/run boundary. Exact locked
container builds and privileged executions are in `allocator-wrapper-build-2`,
`allocator-kernel-{build,run}` and `loader-kernel-{build,run}` receipts. Both Docker
endpoint variables were explicitly `unix:///var/run/docker.sock`. The wrapper was
compiled against the earlier55ea prepared header; final962b header bytes are
identical, verified by the generated-source inventory. The graph change was the
test-only loader binding assertion, not the allocator/helper/dispatcher.

Retained intermediate failures are not credited as allocator regressions:
preparation1 rejected a whitespace typo in patch context; its incomplete5c1f tree
remains. The first55ea generated tree was preserved before replaying preparation
with materialized-source boundary receipts. Wrapper build1 omitted `-c` and tried
to link through unavailable GCC in the builder. Initial XDP test-run CPU flags
returned native EINVAL; the fixture now temporarily pins/restores calling-thread
affinity and verifies the actual CPU. Initial loader test incorrectly searched
symbolic references after Cilium had cleared them on binding; the corrected test
checks acquired map objects. The first Kotlin invocation guessed absent `apiCheck`;
the project's actual `checkKotlinAbi` succeeds. All original outputs remain.

The mandatory unchanged commit hook owns the one full Gradle gate. Its actual
post-attempt outcome, immediately preserved raw log, warnings, cache/skip/native
dispositions and self-review are in the owning workspace's `task-1-report.md`.
Known real USER_NOTIF shutdown ENOSYS/cleanup noise remains the separate open
issue20261003-131111-752, not expected HotSpot success.

## Kubescape and remaining qualification

The read-only node-agent handoff at `1c4fb954cdd51edb98a2ad4100be392cd806e44e`
describes completed **agent-assisted Tier E** artifact ingestion. Its CallID is
a scoped frame-content hash, not a raw native reference. The positive non-time
allocator preserves that distinction; identical frames/scope must not acquire a
different content identity merely because this counter changes. The agentless
schema2/kind/BOOTTIME stream still lacks the separately reviewed bridge to Tier E's
schema1/type/MONOTONIC importer. No node-agent source/index/build/test, schema
adapter, deployed import or Kubernetes persistence is claimed here.

The synchronous kprobe profile remains pinned Temurin25.0.4.1+1-LTS with
`-Xint -XX:+UseSerialGC`. The runnable wrapper uses XDP test context; it proves
actual helper atomics/map concurrency, not full JVM walking/transport, actual
public-capture coupling, concurrent same-key producer cache publication, eviction
under capture, drain correctness or active perf/NMI nesting. No new lifetime,
global/dense, reliability or policy completeness qualification follows.

Task2 consumes the exact962b graph, preserves baseline assets/results and performs
full locked helper/OCI reproducibility, legacy rendered/pdata compatibility and
explicit public-native collection. It must retain the original failed capture,
unknown exact trigger and any actual cleanup errors. Cached/default-skipped
acceptance suites cannot supply that pending runtime evidence.
