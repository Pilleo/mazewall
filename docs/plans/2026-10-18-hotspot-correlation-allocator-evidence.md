# Bounded native correlation allocator — source and qualified asset evidence

**Current R1-reviewed source graph:**
`8212ee25dc2fb58f070c235e30eb01666a492286763df318d90576b742372a4c`.
The [review-fix evidence below](#r1-review-correction-and-fresh-pin-chronology)
records the discriminating regression and complete current-graph pin chronology.
The initial implementation graph and its original native receipts remain below;
the new graph has byte-identical native source, layouts and embedded objects.

## Disposition

The [approved contract](2026-10-18-hotspot-correlation-allocator-contract.md) is
implemented in authored native source. Actual-C regression/negative controls,
isolated real-kernel multi-CPU allocation, acquired-map loader ownership, generated
architectures/layouts and renewed decoder/guard characterization have passed their
scoped checks. **Task2 now supplies full reproducible helper/OCI and actual
public-native qualification of this graph** in the unchanged supported profile;
see [current results](#task2-full-assets-and-public-native-qualification).
**Task3 closes the finite supported-context publication/eviction/drain gate** with
the complete production path and real native perf transport; see
[new native evidence](#task3-finite-native-cache-publication-and-drain). The scoped
allocator issue is resolved without qualifying active perf/NMI or exhaustive schedules.

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
inventory agreed on the initial implementation graph:

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

The initial graph's final fourteen literal pins and current-source controls are
verified. Its retained pre-advancement snapshots independently establish old bytes
for only four canonical Go consumers: **all-fourteen ordering is not proved**.
This is incomplete chronology evidence, not evidence that the other ten advanced
early. The fresh R1 chronology below covers all fourteen files: canonical/mirror
tooling assets and preparation tests(4), canonical/mirror JIT/GC audit source/tests(4),
experimental candidate fixture(1), Kotlin assets/protocol(2), and Kotlin
assets/protocol/child fixtures(3). The accepted pin check was not weakened for
preparation; the original old-bundle refusal remains retained (`unsupported asset
version/ABI/provenance`, exit1).

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

Task2 consumes the current reviewed graph and preserves baseline assets/results.
Its actual full locked helper/OCI, legacy rendered/pdata and public-native results
are appended below. The original failed capture, unknown exact trigger and actual
cleanup errors remain. Cached/default-skipped suites are not runtime evidence.

## R1 review correction and fresh pin chronology

Review finding ImportantR1 identified a real regression-test gap: all three
definitions had identical task, epoch and frames, and the reordered lookup checked
only success/frame count. It did not identify a production allocator defect.

The canonical/mirrored authored `correlation_ids_test.go.txt` now uses four fixtures:

| Reference | PID/TID | Stable epoch | Ordered frames |
|---|---|---|---|
| 1 | 123/456 | 2 | nativeLeaf/pathA |
| 2^53+1 | 123/456 | 2 | otherNativeLeaf/pathB |
| INT64_MAX−1 | 123/457 | 2 | nativeLeaf/pathA |
| INT64_MAX | 123/456 | 4 | nativeLeaf/pathA |

Insert order is indices3,1,2,0; resolve order is0,2,3,1. Each lookup asserts exact
PID, TID, complete HotSpot identity, validation and ordered frame sequence. Wrong
PID/TID/epoch lookup and redelivery are refused; conflicting-frame redelivery is
refused. The original exact definition is rechecked after every rejected
redelivery. Membership pruning and zero-unavailable assertions remain.

The test-only swap control exchanges the two first fixtures' published frame
definitions while keeping their references and identical task/epoch/validation.
The prepared resolver returns success (`err=<nil>`), but the exact binding oracle
fails for reference1. Correct source passes. This is a discriminating frame-binding
negative rather than another length or wrong-task-guard check.

Only this test input changes the 89-input recipe, deriving current graph
`8212ee25dc2fb58f070c235e30eb01666a492286763df318d90576b742372a4c`.
Both owners were prepared normally. All384 checked profiler source/layout/embedded
files per owner match the initial962b graph byte-for-byte, including the production
header/dispatcher, both architectures, Go bindings, loader and decoder. The80
original actual-C selected inputs and both original native binary/object receipts
were revalidated. Their isolated results are reused, not rerun as a new matrix.

Fresh receipts are under `E/fix-round1` (`F` below). Exact full contents and SHA256
of **every one of the fourteen** old962b consumers were captured before edits,
then again after current8212 preparation/qualification but before any advancement.
All three decoder controls and both named compile-ID negatives ran against the
actual newly prepared decoder. Corrected resolver GREEN, exact-definition swap
RED and task/epoch guard-removal REDs ran at the actual prepared resolver boundary.
Build/source-selection/overlay receipts precede compilation; retained test binaries
are hashed before execution. Every control verifies all fourteen old files remain
unchanged before and after it runs.

Sequence timestamps (`time_ns`, actual recorded values):

1. Initial all-fourteen snapshot completed1792302987564652333.
2. Current-graph qualification completed1792303266240469779.
3. Qualified-but-still-old all-fourteen snapshot completed1792303287636756245.
4. Advancement boundary recorded1792303336851864343.
5. All-fourteen new8212 snapshot completed1792303387590988945.

The after snapshot differs from the before snapshot by exactly one literal pin
replacement in each named file. `F/final-pin-and-ordering.json` independently
checks that relationship, sequence, actual89-input digest and exact mirrors.

| Receipt | SHA256 |
|---|---|
| `before-consumers/receipt.json` | `5aab57d30a86c6a647fd1df7c7804b20889958c922ae2758bbedbe9db83c94ec` |
| `qualification-complete.json` | `327d0da95b1d184282c438ef9735f4e92b1b49882b7b00af4a3ff3cd0266ef13` |
| `qualified-before-advancement/receipt.json` | `969fe45c95c67f26afc7081e3575f9b9c51cc260d44cdb1c3d3572cd375a7816` |
| `after-consumers/receipt.json` | `d141a92b967d14cc41d3ea4ebfce77fbab32c19c2e9b3af6d0cbdd0e5c33d4de` |
| `native-equality-and-reuse.json` | `7d5c13269e7f20f8d29a69ce02a8c5a77c32fad87ba3f4a6b4c2cc78222be6d9` |
| `final-pin-and-ordering.json` | `4db639cccbfd870cda80f40ec2b4d622c6675096d5b3572acf58c6073b30f9a1` |

Post-advancement focused Go race binaries pass for canonical tooling/audit, mirror
tooling/audit/candidate and the complete actual prepared OTel resolver suite.
JDK25 compile, `checkKotlinAbi`, ktlint and assets/protocol tests pass with raw
warnings/cached dispositions retained. The unchanged mandatory hook's actual
post-attempt outcome is appended to the same owning `task-1-report.md` with its
immediately preserved raw log. No separate full build is used.

The new receipts establish only this fresh graph's chronology. They do not repair
or invent missing historical evidence for962b. DeferredMinorR2 is unchanged for
final broad-review triage. Original failures and cleanup concerns remain; Task2's
subsequent helper/OCI/public-native qualification follows.

## Task2 full assets and public-native qualification

Task2 starts at reviewed commit `82bea3aa61619e0a323851e485f9ffda64eb43bb` in the
same authorized checkout. No runtime source, hashed preparation input, pin,
dependency, budget or public API changed. All89 ordered inputs,14 consumers and
the unchanged `e7b8b447…736d7c2` production header were checked. Reviewed Task1
actual-C/kernel/source/guard-negative receipts remain valid and were not rerun.

Current local evidence is `E/task2` (`T`), with exact source selections, module/
replacement graphs, compiler/image identities, argv/cwd/environment/exit and raw
outputs. The owning workspace's `task-2-report.md` records the scoped self-review
and mandatory hook result after its actual attempt. Hashes precede explicit use.

### Reproducible full assets

Fresh `T/build1` and `T/build2` each compile the helper with locked Go1.26.0,
independent empty build caches, read-only existing module cache, offline module
resolution, CGO0/Linux-amd64, actual `build/experiment.go.mod`,
`-trimpath -buildvcs=false -ldflags '-s -w -X …GadgetPatchSHA=8212…'`.
Host Go1.26.8 runs the existing gadget/pack tools and experimental/overlay tests;
the builder remains Clang/LLVM18.1.8 with distinct cgo Go1.26.6. The embedded
architectures/layouts are the unchanged Task1 generated bytes.

Each fresh gadget build uses the existing command, pinned builder,
`--builder-image-pull never`, `SOURCE_DATE_EPOCH=1790985600` and a fresh owned
output directory. Docker containers select `--pull=never`. Both endpoint variables
explicitly name `unix:///var/run/docker.sock`. The gadget CLI's root module graph
is receipted separately from the helper's prepared modfile graph. Normal Make's
`always` policy remains; capture/admission has no build or registry fallback.

| Identical output in both builds | SHA256 |
|---|---|
| Helper,68,280,482bytes | `d0cc2a1ddf91fecef49d80a5bb3fded0d416a7002664fbfcef6bf4c1d9f3f93c` |
| Bundle manifest | `b4a0ae29d39f2061a1afa100354c8b3b92ea9ac6f339b27b90e1a1ea29874831` |
| Gadget root OCI descriptor | `5c5791bb47beba9568d98216abf7af4a03abe9093d1d024d511a26e645e051e9` |
| Independent comparison receipt | `f75ee80021048d199caf911e9982e0e4576e482a9c3897309a6be74838fde87c` |

Independent verification hashes the exact file set and traverses every OCI
index/manifest/config/layer descriptor with digest/size checks; all six reachable
blobs are present, with no unlisted/unreachable blobs. All ten bundle files,
including manifest, compare byte-for-byte. This is actual equality, not metadata
caching or helper self-trust. Gadget bytes remain identical to270a because this
allocator is in the helper's embedded profiler. Actual ELF/build metadata confirm
static/stripped Linux-amd64, Go1.26.0 and the current local source replacements.

Old270a assets remain at `T/old270a-assets` and
`profiler/hotspot-agentless/build/assets-task2-preserved-270a`, with old helper/
modfiles/ref/manifest copies. Current verification actually refuses the old pin
(exit1, unsupported version/ABI/provenance); both new bundles pass. Existing
installation tooling publishes build1 to fresh canonical `build/assets`; all
installed bytes match. Old deps/captures are untouched. Retained setup errors are
an incorrect compiler path, a missing required gadget output directory, and a
wrong-cwd metadata query; none is a native allocator invariant failure.

### Actual compiled public-native observations

With native opt-in, both rootful endpoint variables and current asset path,
`./gradlew :profiler:installDist :profiler:integrationTest --tests '*HotSpotPublicIntegrationTest' --rerun`
passes: **8tests,0failures/errors/skips**, suite189.486s, command338s including
build/setup. Locked Temurin25.0.4.1+1-LTS uses `-Xint -XX:+UseSerialGC`; configured
readiness/session/stop/enrichment bounds remain10s/60s/2remaining seconds/1s.
No retries, extra workers or larger buffers are used.

| Observation | Received / resolved / unavailable | Actual result |
|---|---|---|
| `da3d99c8e638437a-public` | 802 / 709 / 93 | 400unique marked paths; attempted=submitted=received=terminals802, reserve_failed0; useful exact ordered application frames |
| Three matched public windows | 802 / 723,733,734 / 79,69,68 | Each400unique marked paths; selected/terminal/drain complete |
| Three matched standalone windows | 802 / 744,754,743 / 58,48,59 | Same corpus, independent native launches |
| `31882854b2d84d56-retention` | 502 / 460 / 42 | Exactly502discarded=502received, retained0/pending0, terminal complete |
| Current JVM20opens/100ms | 60 / 59 / 1 | Accepted; pending0, finish0.693488411s, no pending physical owner |
| Dense600/1ms | 593received / 588paired / 5pending | **Rejected**, finish2.817683980s, no pending physical owner |
| JFR dense600/1ms | 241received / 228paired / 13pending | **Rejected**, finish2.351902209s, actual JFR retained, no pending physical owner |

Accepted summaries independently satisfy selected equations/session/PID/birth/map
binding, writer quiescence, ring flush/callback join/final-counter/native retirement,
definition reader/consumer/drain/auxiliary joins and enrichment bounds. All retain
definitions/enrichment/wire/retention unknown and globalfalse. Policy construction
still refuses incomplete coverage. Actual native permission refusal preserves
EPERM/EACCES;25ms startup refuses a usable session; target exit/restart cannot
substitute stacks. Existing tests check collector/client absence. Finish wall time
includes parsing/retirement work and is not proof of a2s wall-clock return bound.
Dense partials lack complete drain/terminal proof and do not qualify600.

Completed fixture references are small non-time values1–8 (one matched session1–9),
reused only for the same exact PID/TID/process-birth tuple in that session namespace.
Cold zero-reference events remain unavailable. Observed live cardinality is
separate from Task1's isolated4096-ID concurrency proof. Raw timestamps/IDs,
unknowns, original historical capture/failure and source-linkage uncertainty remain.

### Experimental cross-task and legacy compatibility

One existing `mw-experiment-tools candidates --assets <current> --output <fresh>
--docker-host unix:///var/run/docker.sock` runs all six experimental cases once.
C1Serial/C2Serial/XintG1 retain40marked with31/32/30useful and9/8/10unavailable.
Carrier1/2/4 controls retain256marked each and observe1/2/4carrier TIDs,0logical
identity and no parent evidence. Four-carrier raw capture has261events/261terminals,
14positive refs1–14, four carrier TIDs plus main/background TID, with no reference
mapped to two exact tasks. Per-case cleanup receipts check absent containers/joined
wrappers with empty pending/failure lists. Every result is production_qualifiedfalse;
this is not JIT/GC/Loom lifetime or historical-trigger repair.

Two immutable overlays add only virtual prepared test files; dependency/module-cache
source is untouched. Selected race binaries are compiled, prehashed and run:

- Actual legacy rendered `Resolve` maps reordered distinct definitions at1,2^53+1,
  INT64_MAX to exact ordered names, omits kernel frames and keeps zero unavailable.
- Actual pdata `Generate` serializes and decodes OTLP, retaining exactly the original
  sample's signed-integer correlation_id at all three boundaries, without float64
  conversion, overflow or truncation.

Both pass. Existing task/epoch/frame refusal and negative guard controls remain
unchanged with their reviewed Task1 receipts; no guard weakening was used.

| Current receipt | SHA256 |
|---|---|
| `T/observations.json` independent live assertions | `dfda7f19504daa2cbd97a414551dda46813ad32182361e3c944563137adbd2cd` |
| Explicit native JUnit XML | `454d8f88e12bd67407d754446e7dd84c98da746dd489ec5d218278ef3e0e6ece` |
| `T/candidates/summary.json` | `e1be991bfe7ab32ed362b60992257911c93878e2f0b23e3555ca7d015a30a309` |
| Legacy rendered binary | `d2122f21794a74d98c2b45b4983772be081c3574cbfd72a3189fe9d57841c015` |
| Legacy pdata binary | `2a44cc874751c800e4fe48fb83a78abfcf518491a8ec00581a015aa9bf1a2258` |

### Remaining boundary and durable integration status

The handoff's read-only node-agent1c4fb954 source inspection remains authoritative:
`records.go` consumes completed agent-assisted Tier E schema1/type/MONOTONIC;
`aggregate.go` hashes scope plus ordered loader/class/method/descriptor/signed-BCI/
kind frames into content CallIDs, excluding source stack/invocation IDs. Positive
opaque allocator refs preserve those inspected identity needs but cannot repair
wrong frames or supply missing frame semantics. Agentless schema2/kind/BOOTTIME
is a separate input. Task2 does not access/mutate/build/test node-agent or claim a
deployed adapter, saved profile, end-to-end attribution or completed bridge.

At the Task2 checkpoint, scoped full-asset/public qualification was complete; the issue stayed open
for full admitted-context/publication/eviction-interleaving characterization;
synchronous live observations do not exhaust same-key races or eviction/recovery
schedules. Active perf/NMI remains unqualified. No new JIT/GC/TID-reuse/Loom,
dense/global/loss or policy authority follows. DeferredMinorR2 remains for final
broad review. Inherited USER_NOTIF shutdown ENOSYS/cleanup issue20261003-131111-752
is not fixed. The owning Task2 report records the actual mandatory full hook's
raw log/cache/skip/warning dispositions after the commit; no duplicate full gate.

## Task3 finite native cache publication and drain

BASE7b66a71f91b6305d3457b5615ed1b6c96c47e58b, same authorized checkout/branch.
Only optional `tests/correlation/cache.bpf.c`, `internal/qualification/correlation/cache_kernel_test.go`,
their exact experimental mirrors and documentation/status change. The test README
has explicit locked/offline compilation and native execution commands. There is
no runtime patch, public API, pin, helper, OCI, dependency, profile or budget change.

Let `N` be `.superpowers/sdd/2026-10-18-hotspot-correlation-allocator/evidence/task3`.
Before every build, `cache-N-actual-selected-inputs-before.json`, actual Clang image/
compiler receipts, and `cache-go-N-{selection,inputs-before}.json` plus actual locked
Go compiler/image receipts bind the chosen source graph. Before each execution,
`native-*-binary-object-before-execution.json` hashes the actual object/binary.
Final `native-final.log` SHA256 **69085f4b66ff8270cf7cabed289a23a46b3fb4ef5e68daa7160a32defac32022**.

### Actual path, helpers and source context

The wrapper includes the entire unmodified prepared8212 production dispatcher,
native allocator/header, `Trace`/`TraceCache`/`PerCPURecord`, pristine-record/frame
helpers and `send_trace`. It suppresses generated multi-use entry declarations,
supplies the actual extern-map declarations from HotSpot/Go-label units and adds
one real TGID-filtered `__x64_sys_getpgid` kprobe. No copied cache/allocator algorithm
or mocked helper is used. Actual map operations, CAS, kernel reads, CPU/PID helpers
and perf output load and execute on Linux-amd64 kernel7.2.10-x64v3-xanmod1.
The HotSpot/Go-label/APM maps are empty; seeded stable epoch/task/native frames
are synthetic valid entry fixtures, not a Java walker or live JVM metadata proof.

Production `tracemgmt.h:MULTI_USE_FUNC` generates perf/kprobe variants; the loader
rewrites their LRU/allocator/scratch/return references to shared acquired maps.
Gadget's actual `pkg/symbolizer/otel/otel.go:112–121,223–233,447–512` exports both
typed entries but never calls `AttachTracer` for active CPU sampling. The selected
`gadgets/openat-stack/program.bpf.c` uses synchronous `openat_handler_entry`; its
return-slot lifetime is that synchronous chain. `OffCPUThreshold:0` does not load
the off-CPU unwinder entries. The separately attached `sched_process_free` monitor
is process cleanup, not an unwind producer and does not use these scratch/return
maps (`tracer/tracepoints.go:31–41`, `support/ebpf/sched_monitor.ebpf.c`).
The fixture executes one admitted
kprobe chain per owned calling thread, pins/verifies distinct CPUs, and deliberately
uses equal synthetic keys across them. That probes cache races; a real task is not
running simultaneously on two CPUs. No claim of arbitrary mixed entries, active
perf/NMI nesting, exhaustive kernel schedules, scratch leases or JVM lifetime follows.

### Real measured cases

Final native invocation is `cache-go-5.test -test.v -test.count=1 -test.timeout=60s
-test.run=^TestKernelCorrelationCachePublication$`, locked Go image, rootful Docker,
`--privileged --pid=host --network=none --pull=never`, read-only checkout/output/
module-cache mounts and `MW_CORRELATION_CACHE_OBJECT=N/cache-2.o`. Both endpoint
variables equal unix:///var/run/docker.sock. The Go build uses actual
`build/experiment.go.mod`, offline dependencies and `-buildvcs=false`.

Builder **dba339d2…c77aa / Clang18.1.8**, Go image **fb612b78…47b84 / Go1.26.0**
are unchanged locked images, inspected before compilation. Final object SHA256
**6c8f6c01e3cf4f4d13f42a54cfa92f695a03395638144ea3a20758911d243cc6**;
test binary **67876a39cc395fad80a5665ac42bc1cbf07f940f986a369543d758deb755d3e0**.
No source/dependency download, compiler substitution, sysctl or permission fallback.

| Gate | Actual final observation |
|---|---|
| Layout/map ownership | Program29800 references all21 acquired map IDs. Allocator30203 ARRAY/u32/u64/1; LRU30193 key24600/value8/capacity256; record30208 perCPU/value26600; scratch30209 perCPU/value24600; return30198 perCPU/value8; perf30195/12CPUs. All actual specs/IDs checked. |
| Repeat key | Initial miss reference1; repeat returns1, trace miss-reference0, counter remains1; no extra packet. |
| Different keys | 16 paired calls on helper-verified CPUs0/1: task and frame distinctions, unique positive references, no cross-key alias. |
| Equal key | Two barrier-released calls actually miss, producing references35/34; both packets bind the same exact PID621929/TID622909/epoch4/frame21/file1337. Earlier setup control observed a hit/miss pair34/34; neither schedule is presumed universal. |
| Evict/republish | 320 distinct native fills pressure the actual LRU256. Select an actually absent key, not a predicted victim: old reference48, new356, state355→356 with no reset. Old retained packet remains exact; new reference is independently exact for that key. |
| Saturation | After all calls join, forward-only fixture seedINT64_MAX−1; actual miss issuesINT64_MAX. Eight hits reuse it without definitions; a new miss returns0, inserts nothing, emits nothing and leaves counter atINT64_MAX. |
| Raw packets/accounting | **376 admitted=376 observed;366 unique definitions;0 reported perf loss**. Every760-byte logical packet exactly matches task/epoch/reference/frame and all other seeded/zero fields using actual BTF offsets. Raw samples are764bytes, including4perf padding bytes; every full raw hex/hash is retained. Final251 LRU entries each match exact recorded bindings (kernel per-CPU LRU batching does not promise256 resident entries). |
| Ordered drain | Reader and callback held by separate channels;9 native output packets remain unread in perf rings. Producers quiesce and restore affinity, link detaches, Flush precedes reader release/join. Callback is proved still held, then released/joined before final counts/loss/cache sampling. |
| Retirement | Checked link/reader/program/all21map closes, worker/reader/callback joins. Actual program/map IDs becomeENOENT within one shared2s RCU-reclamation budget; no GC-dependent live Variable mmap owners. Final testPASS/0skips in0.18s. |

Test-specific limits:376calls,2workers, fixture input/result maps8entries each,
callback queue32,9queuedtail packets,2s explicit waits and shared2s kernel-ID
retirement budget/1ms polling,60s outer timeout. Production perf524288bytes/possible
CPU, LRU256 and all existing buffers/deadlines remain unchanged. This is finite
successful-path publication and drain evidence; original host insertion/output-fault
controls still characterize those boundary outcomes. No host OOM or native output/
permission failure is fabricated, and observed errors are retained.

### Reuse, failures and issue disposition

Before/after equality audits derive the same89ordered inputs/8212digest and14pins,
check547 prepared dependency files from Task2's helper receipt and every installed
bundle byte against the retained byte-equal Task2build2 bundle. The optional files
are outside the helper's selected production graph: actual locked Go `list -deps`
selects the identical7412paths, with6060locally readable selected files rehashed
against Task2 and standard sources bound by the same locked Go image. Reuse the reviewed Task1 source/
kernel/loader/guard proofs and Task2 actual8public-native+legacy+6candidate results;
do not repeat their full matrix or infer that default hook skips executed natively.

Retained fixture setup errors: first Go compilation used removed RewriteConstants
(selected cilium0.22 requires VariableSpec.Set); first two native attempts reject
ELF perf-size/data-map-flag normalization assertions before attachment, with initial
immediate kernel-ID retirement failures retained; third native attempt attaches but
admits0calls because a private PID namespace mismatched BPF host TGIDs. Corrected
runtime uses explicit host PID namespace. No verifier/helper/permission error was
observed or hidden. A passing intermediate run precedes the final explicit held-
reader tail improvement. All source/tool/object/binary/error receipts remain inN.

Criterion3's finite supported-context publication/eviction/drain gate is discharged.
Together with reviewed Task1/R1 and Task2, all five scoped issue acceptance criteria
are met; archive the allocator issue with its original failure/unknown trigger and
chronology limits intact. Unsupported active perf/NMI, exhaustive schedules,
JIT/GC/Loom/TID lifetime, dense/global/loss qualification and the future Kubescape
agentless bridge remain separate. Node-agent is untouched/read-only; content-hash
CallIDs are not native counters. DeferredMinorR2/M1 and inherited USER_NOTIF shutdown
issue20261003-131111-752 remain for their existing owners/final review.

The owning `task-3-report.md` records focused reference/backlog/mirror/format checks,
the single unchanged mandatory commit-hook outcome, raw log and exact cached/skipped/
cleanup dispositions after that attempt; no duplicate same-code full gate. Controller
review/final broad review/integration remain separate from this native task evidence.
