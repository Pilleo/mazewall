# Verification evidence

## Owned event-only finalization (2026-10-02)

The opt-in single-openat-ring path now retires the writer, flushes and joins the
reader/callbacks, samples quiescent reserve counters without a later event, and
retires owned resources. Native acceptance passes 8/8 queued tokens, 255 delivered
plus 745 genuine native failures out of 1000 pressure attempts, and held-callback
deadline retention. Three 1200-token burst repeats and the final guarded repeat
pass; a larger run still rejects 2894 missing operations despite successful drain.
Exact discard 42 remains distinct from native failure counts. See
[`event-only-finalization.md`](event-only-finalization.md). Full-mode definition
drain and complete loss remain open gates.

## Queued shutdown-tail proof (2026-10-02)

Eight genuine native records queued before stop yield only one delivered record
after ring/perf Close, versus all eight and an explicit boundary after Flush.
The actual Gadget close path completes a held callback and joins its receiver
while leaving seven queued records unconsumed. Race-enabled native tests pass
this characterization; capture finalization is still partial. See
[`queued-tail.md`](queued-tail.md) and the source-backed finalization design.

## Event-read loss correction (2026-10-02)

The pinned Gadget reader discarded a valid ring sample accompanying a positive
loss report. The local correction passes a real-datasource race regression and
an isolated kernel-ring pressure test: 745 genuine output failures plus all 255
valid payloads delivered out of 1000 attempts. Three identical ready-burst repeats
captured every measured token; a larger pressure diagnostic still rejected 4569
missing operations, with matching partial native observations and 735 explicit
enrichment skips. A separate admission-skip attribution bug is fixed too. See
[`event-read-loss.md`](event-read-loss.md) for provenance and residual gates.

## Shutdown/loss/measurement checkpoint (2026-10-02)

Owned definition-reader/consumer joins, events-only capture, exact token-42 loss
control and repeated three-mode measurements are implemented locally. The paced
eight-route readiness control delivered 1200 correct measured segments in every
full repeat. Cold burst useful coverage failed, and the ready burst lost 29 tokens
in its third full repeat; both rejected runs remain evidence. Worker joins do not
establish native buffers drained or complete loss accounting. See
[`loss-benchmark.md`](loss-benchmark.md) for actual windows, distributions,
provenance, controlled-drop results and residual gates.

## Scoped epoch unloading acceptance (2026-10-01)

The opt-in pinned interpreter/Serial profile now passes useful unloading
attribution in three independent normal-collector JVMs and a final audited-build
repeat: 1600 observed tokens, 1580 checked/correct segments, 20 cold unavailable,
zero wrong-generation labels, and all 80 classes confirmed unloaded per run.
Every branch in generations 1–79 resolves all ten operations. Same-epoch cache
reuse is retained; cross-generation/cross-epoch reference reuse is absent.

The non-epoch control remains red with 1560 wrong-generation labels. Real exec
barrier and LRU/pruning tests, legacy/strict genuine recovery, controlled-C2
regression, host race/upstream verification and pinned container build pass.
Exact artifacts, hashes and limits are in
[`identity-investigation.md`](identity-investigation.md#implemented-bounded-remedy-2026-10-01).
This is a bounded runtime-profile result, not universal unloading or loss proof.

Task 0 host checks on kernel `7.0.0-34-generic`, x86-64:

- The selected Gadget/profiler module graph compiled in the Go preflight CLI.
- `go test -race ./...` passed refusal and process-identity parsing tests.
- The pinned Temurin image reports `25.0.4.1+1-LTS` HotSpot Server VM.
- The pinned Gadget builder reports Debian clang 18.1.8.
- Privileged-container preflight loaded a temporary BPF program and verified
  kernel BTF, exact `__x64_sys_openat` availability and its wrapper ABI.
- PID 1 was explicitly refused as a privileged target; it was not attached.
- `COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock make preflight-test`
  passed against an ordinary-user JVM from the pinned Temurin image. It also
  verified refusal of a root-owned target and an unprivileged collector.
- `make container-build DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'`
  reproduced the build with the digest-pinned Go image.
- `make verify` passed build, race tests, graph emission and module checksums.

## First live capture gate (2026-09-30)

`COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock bash scripts/run-demo.sh`
passed the live acceptance test on the pinned Temurin JDK with no injected
agent. The gadget image digest was
`sha256:e1f27866427689256044e2c1744e6b9af5f71b7676183e43b51806d5d881cb0f`.

Observed output: 400 marked `openat` handler-entry records; 384 contained an
`InlineOpenFixture` application frame. Two records had zero OTel references;
other non-application-frame results remain visible in raw JSONL. Example
resolved sequences include `sharedLeaf`, `pathA` or `pathB`, and `main`.
These are first-demo observations, not a verified completeness/inline claim.

Raw artifacts for this run: `build/capture.lLMPNb/`. Re-run the command to obtain
fresh evidence; these ignored artifacts are not portable published fixtures.

At this first-capture checkpoint, first-definition recovery, inline fidelity,
overhead and Kubescape integration had not passed their gates. Recovery evidence
is recorded below.

Repeat with explicit syscall-register read-status validation:
`DOCKER_HOST=unix:///var/run/docker.sock COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock make demo`.
Artifacts: `build/capture.8IBivT/`; gadget digest:
`sha256:975aadff20165c87d63d54154c941b6a8ed84d42ed9f420a61fdd2a5ce385544`.
This repeat retained 400 marked events, 388 with correlated application frames,
12 without an application frame (including one zero reference), and zero
reported syscall-register read errors. The same correctness limitations apply.

Repository merge gate: `./gradlew build` passed on 2026-09-30.

## Structured enrichment gate (2026-09-30)

`make verify` passed original upstream lookup/pruning tests plus local typed
normalization, shared-waiter cancellation, pending count/byte limits, absolute
deadline, incarnation rejection and fatal output/lifecycle tests with `-race`.

`make demo` with the existing Docker endpoint passed genuine typed capture.
Artifacts: `build/capture.qB3PvO/`; 400 event records and 400 terminal outcomes,
380 resolved provider sequences (370 with fixture application frames), two
missing references and 18 target-exit cancellations. Maximum observed pending
count was 18 and serialized pending payloads 9,544 bytes. Original unavailable
results are immutable; no later definition rewrites them.

Low-rate correctness repeat with `--max-pending 1` passed; artifacts:
`build/capture.YOgnY4/`. All 400 events received terminal outcomes: 258 resolved,
one missing reference and 141 explicit pending-limit skips. Maximum pending
count was one and pending payloads 532 bytes. This is a pressure/refusal check,
not a high-rate or overhead result.

The structured patch is a local proposal, not an upstream release. BCI
availability and kernel loss remain unknown; neither is filled with zero.

Final checks for this milestone: `make verify`, the digest-pinned
`make container-build` and `./gradlew build` passed. A fresh live repeat using
the container-built binary passed in `build/capture.xLLh1Y/`.
Runtime preflight reports and generated module graphs belong in ignored
`build/`; publish acceptance/benchmark reports only when those gates run.

## Lost-first-definition recovery (2026-09-30)

The initial `make recovery` deliberately failed. A genuine `pathA` definition
was dropped while its BPF LRU reference remained resident. That reference was
reused by 194 observations: 156 stayed definition-unavailable and 38 ended as
target-exit cancellations. Artifact: `build/capture.Q2kQsI/`.

After adding targeted invalidation, the same gate passed in
`build/capture.HhSHln/`: one cache invalidation, 39 original unavailable events
left unchanged, and 153 subsequent `pathA` observations resolved through fresh
references. All 400 events retained terminal outcomes; recovery errors were zero.
The stricter chronology repeat passed in `build/capture.g74KC5/`.

One cold attach attempt exceeded the old ten-second harness limit without a
verifier error. The unchanged binary passed on repeat; the harness now permits
30-second bounded attachment and a 60-second collector cap. Target exit still
terminates the collector via the pidfd.

This evidence covers post-symbolization definition loss with a stable
interpreter-only fixture. It is not a high-rate/overhead measurement or proof
of the broader runtime/JIT/thread matrix and every transport-loss boundary.

Final chronology-checked repeat (`build/capture.EaldAd/`) passed: all 400
events accounted for, 38 dropped-reference events remained unavailable, one
LRU entry invalidated, and 152 pathA observations after the recovery clock
resolved. Recovery errors: zero. Normal non-fault capture also passed
(`build/capture.IX53CR/`). `make verify`, backlog validation and
`./gradlew build` passed for this milestone.

## Partial collection-health and native LRU characterization (2026-09-30)

`make verify` passed local race tests, Gadget datasource/lookup/pruning tests,
the owned OTel reader regression, collector compilation and checksum/provenance
checks. The reader regression first failed without the new capability, then
verified cumulative totals after original metrics drains and tracer isolation.

`make kernel-cache-test DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'`
passed actual kernel LRU eviction with the pinned key/value layout and 256-entry
capacity. All 300 userspace definitions survive the first absent cycle; the
second cycle keeps only native-resident IDs. Eviction order is approximate and
is not asserted. This tests the native configuration, not whole-provider RSS.

Normal live capture passed in `build/capture.y8KbEs/`: 400 events/terminals,
380 resolved and 20 unavailable; maximum pending count 18 and payload bytes
9,468. The summary contained Gadget forwarded reports (observed 0) and OTel
consumed-record observations (lost/empty/read-error 0 each), actual perf buffer
size 524,288 bytes per CPU, and `collection_health=partial`. Every observation
has `complete=false`; final `event_loss`/`definition_loss` remain absent.
These zeros do not certify zero kernel/transport/definition loss.

The provider patch preserves existing metric interval resets and buffer sizing;
the combined bundle hash for this evidence is
`961428fb918089c2d6d19de1c463c746424eebe3802883c72fa2464275342646`.

Recovery revalidation passed in `build/capture.JSgWon/`: 400 events/terminals,
358 resolved, 42 unavailable, one native-cache invalidation and zero recovery
errors. The fault-injected missing definition was recovered only for subsequent
operations; original terminals remained unchanged. Its summary retained the
same explicitly partial health semantics. Backlog validation and the repository
`./gradlew build` merge gate passed after these focused checks.

## HotSpot scope/BCI quality (2026-09-30)

Provider interning/decoder and model/classifier regressions first failed for
missing metadata, then passed. `make verify` runs the full libpf and HotSpot
packages and Gadget transport/cache tests with the race detector. Real decoder
fixtures cover exact/preceding/method-only selection, true BCI zero, encoded zero,
interpreter sentinel, empty bytecode, malformed/short/self/increasing scope links,
bad indexes/null/unreadable method pointers, captured subtypes and compiled-ID
mismatch. Interpreter cookie checking is absent upstream and remains a follow-up.

The live quality gate failed against retained pre-quality `capture.y8KbEs` with
`fixture frame missing provider quality`, then passed with genuine captures.
An initial live gate compile failed because the harness used the unpatched root
graph; it now explicitly selects the corresponding prepared modfile.

Final normal bundle hash:
`4e930f23d523d268b07fd76e4c3aa2c37042f0b21a0f4301b8e021373363a687`.
Digest-pinned container build passed, and its binary passed the live quality gate
in `build/capture.YIUz6W/`: 400 events/terminals, 398 resolved and two unavailable.
Frame observations included 7,357 interpreter, 1,269 exact-PC, 277 method-only,
390 stub and 51 unknown Java frames; 5,878 non-Java frames omit Java quality.
BCI states: 8,513 observed-in-range, 113 out-of-range, 667 unavailable, 51 unknown.
No observed BCI zero happened in this run; its availability distinction is proved
by the decoder/model fixtures, not inferred from this distribution.

Interpreter-only gate `build/capture.CPRtJI/` passed: 400 terminals, 398 resolved,
two unavailable; 8,970 interpreter and 390 stub observations, with no compiled
scope claims. There were 8,580 available and 390 out-of-range BCI observations.
This earlier run used bundle `0a99a7ecbae938866fe76b2d4429c48e4d3c28f1cd8d64770e785e96b54e2132`;
subsequent bundle changes added captured-subtype regression tests and formatting.

Recovery revalidation `build/capture.7astbv/` passed with 400 terminals, 359
resolved/41 unavailable, one invalidation and zero recovery errors. Original
missing-definition results stayed terminal. Native LRU/two-cycle pruning also
passed with the final bundle. All live health snapshots stayed explicitly partial,
with final whole-session loss unknown and perf buffer 524,288 bytes per CPU.

PC-selection fuzzing passed: a guided 10-second generated-module run completed
59,157 executions; `make quality-fuzz` used the exact experiment graph and passed
2,623,555 unguided executions in ten seconds. These are decoder-bound checks,
not live runtime or overhead benchmarks.

This milestone does not certify full inline chains, opcode-boundary-valid BCI,
interpreter incarnation identity, every decoder error's per-event transport,
class-unloading behavior or overhead. Raw errors are delivered for retained
partial frames; failures before emitting a frame have log/unknown-placeholder
evidence only. See the separate interpreted-identity backlog item.

Final repository checks: `./scripts/adkw check-backlog`, harness shell syntax,
`git diff --check` and `./gradlew build` passed. The quality issue remains open
for review; no source changes were staged, committed or submitted upstream.

## Checked interpreted method lookup (2026-09-30)

Red tests reproduced a cached old method being emitted despite a changed captured
ConstMethod identity and despite unreadable target memory. The checked lookup
then passed stale-cache, changed-address reload, before/after pointer replacement,
zero-token, null/overflow, remote-bias, short-read and EPERM/EACCES/EIO regressions.
The complete existing HotSpot/quality suite and `make verify` passed with `-race`.

Normal bundle hash:
`9e1564da79a1e3c4cac750968fcf0ddb323e396dbdb3b13fac99011f74df47eb`.
Live Xint quality gate `build/capture.Sqmafu/`: 400 terminals, 398 resolved and
two unavailable. Default tiered gate `build/capture.eFqJZ6/`: 400 terminals,
382 resolved and 18 unavailable. Recovery `build/capture.xzcjqm/`: 400 terminals,
358 resolved/42 unavailable, one invalidation and zero recovery errors. All three
retained explicitly partial health and whole-session loss unknown.

This evidence validates the interpreted symbolization/cache check, including
failure paths; the stable live fixture does not exercise class unloading or full
pointer recycling. The producer uses a truncated fingerprint, existing frame
cache can reuse already symbolized frames, and two reads cannot rule out ABA.
These checks do not certify generation identity or unchanged overhead.

Backlog validation, `git diff --check` and `./gradlew build` passed for this
bounded fix. The issue remains open for review and the class-unloading/reuse
matrix. Changes were not staged, committed or submitted upstream.

## Deterministic controlled-C2 inline corpus (2026-09-30)

New compiler/operation validator tests first failed for missing implementation;
negative cases now reject absent/failed/C1/deoptimized inline evidence, wrong
compiler PID, mismatched task/nmethod branches, wrong captured process, duplicate
records/tokens, missing operations, wrong branch/frame order, non-Java application
frames, unsupported BCI/completeness and terminal-accounting errors. The
task/nmethod association guard had its own reproduced red test before the fix.
`make verify` passed the new corpus race tests and existing profiler/quality gates.

The pinned JDK compiled `InlineCorpusFixture.class` with debug information under
the ordinary caller UID. Its controlled flags were C2-only, batch compilation,
threshold 100, `LogCompilation`, non-inlined path roots and forced sharedLeaf
inlining. Warmup was 3,000 unmarked opens per path before attachment; the manifest
then declared 1,000 marked operations per path. The source/JVM/target did not use
an injected agent or a simultaneous reference profiler.

Initial `make inline-corpus` passed in `build/capture.SYyG48/`: 2,000 intended and
observed operations, 1,970 reconstructed application segments (985 per path),
30 unavailable segments and zero wrong branch/order. Its compiler log showed
successful active C2 root compilations 104/105 with sharedLeaf inline success.

After stricter input typing/PID/association checks and provenance capture, the
final fresh run `build/capture.GSwcah/` passed:

| Measurement | Observed |
|---|---:|
| Intended operations | 2,000 (1,000 per path) |
| Observed manifest tokens | 2,000 |
| Unobserved tokens | 0 |
| Resolved application segments | 1,962 |
| Compiled segments A / B | 981 / 981 |
| Unavailable application segments | 38 |
| Wrong attribution or application-frame order | 0 |
| Delivered session events / terminals | 2,001 / 2,001 |
| Provider resolved / unavailable | 1,998 / 3 |
| Max pending events / payload bytes | 22 / 12,876 |

Compiler IDs 104/105 again proved sharedLeaf inline success in both root methods,
with matching successful compiler tasks and the target's own PID. The report's
coverage is 98.1% for the selected application segment; it does not turn the
provider's 1,998 successful returns into 1,998 complete application stacks.
The extra session event was outside the marked manifest and remains in raw
accounting. All intended corpus operations were observed, while whole-session
event/definition loss remains unknown and both counter snapshots explicitly
partial. The perf data buffer was 524,288 bytes per CPU.

The artifact directory retains the operation manifest, raw normalized records,
compiler XML, target completion log, machine report and environment/source/class
hashes. Provider patch bundle remained
`9e1564da79a1e3c4cac750968fcf0ddb323e396dbdb3b13fac99011f74df47eb`;
the new fixture/validator source hashes are recorded independently.
Original recovery revalidation also passed in `build/capture.7y75Vt/`.

The evidence is scoped to a single platform worker and controlled diagnostic C2
flags. Compiler IDs demonstrate inlining in the active target nmethods; they are
not a per-event exported nmethod-ID oracle. Full inline-chain fidelity, BCI opcode
validation, separate USER_NOTIF reference comparison, concurrent/runtime/unloading
matrix, transport-loss finality and overhead remain separate acceptance gates.

Final checks for this corpus milestone passed: `make verify`, the fresh controlled
corpus gate, original recovery gate, harness shell syntax, backlog validation,
`git diff --check` and `./gradlew build`. The corpus issue remains open for review;
no files were staged or committed and no upstream changes were submitted.

## Runtime/platform-worker matrix (2026-10-01)

The verified baseline was committed as `d965d2a6` before this extension. Unrelated
operator work was left unstaged. The fixture now uses gated platform workers and
an atomic completion checksum, preserving 1,000 operations per path overall.

The worker/TID rejection test first failed for missing implementation, then passed
alongside compiler/manifest tests. Runtime tests separate interpreter evidence
from C2 proof and reject compiled frames in interpreter-only application segments.
The nine-case live matrix passed in `build/matrix.4TpGEq/`:

| Mode | Workers | Observed | Resolved app segments | Unavailable app segments | Wrong attribution/order |
|---|---:|---:|---:|---:|---:|
| C2 | 1 | 2,000 | 1,988 | 12 | 0 |
| C2 | 4 | 2,000 | 1,936 | 64 | 0 |
| C2 | 16 | 2,000 | 1,722 | 278 | 0 |
| Tiered | 1 | 2,000 | 1,986 | 14 | 0 |
| Tiered | 4 | 2,000 | 1,926 | 74 | 0 |
| Tiered | 16 | 2,000 | 1,744 | 256 | 0 |
| Interpreter | 1 | 2,000 | 1,980 | 20 | 0 |
| Interpreter | 4 | 2,000 | 1,925 | 75 | 0 |
| Interpreter | 16 | 2,000 | 1,776 | 224 | 0 |

All 18,000 intended tokens were observed; every worker had both branch segments,
with distinct stable captured TIDs. C2 cases also required compiled segments per
worker and target compiler-inline evidence. Other modes do not claim that proof.
Per-case availability reduction is retained rather than hidden. The machine
matrix records each artifact path, compiler proof, worker mapping and partial
health summary. Repeated runs use new JVM/process/session/run identities.

This is not yet a class-unloading/reuse, deep-chain, cold-start, GraalVM, independent
reference-oracle, native transport-loss or overhead acceptance matrix.

Runtime extension final checks passed: `make verify`, backlog validation,
`git diff --check` and `./gradlew build`. The scoped matrix issue remains open for
review; no remote submission or merge was performed.

## Independent USER_NOTIF reference (2026-10-01)

The existing USER_NOTIF smoke prerequisite passed before adding the independent
fixture. A preliminary host-GraalVM probe also resolved 2,000 operations but is
not used as Temurin comparison evidence. The final wrapper exported the pinned
Temurin JDK for ordinary-user host execution and left profiler/session/native
implementation unchanged. The first temporary Gradle task wiring executed but
failed configuration-cache serialization; the reproducible wrapper explicitly
uses `--no-configuration-cache` and subsequent runs succeeded.

Reference regressions first failed for missing comparison implementation, then
passed while rejecting incomplete, wrong and non-USER_NOTIF reports. Raw reference
paths identify each marked open; Java stack arrays verify leaf/root/branch order.
The fixture also checks the actual strategy and reference coverage completeness.

One-command `make oracle` passed in `build/oracle.LvlLGg/`: 2,000 observed candidate
operations, 1,986 reconstructed application segments and 14 explicit unavailable
segments; reference 2,000 observed/ordered segments (1,000 per branch), zero wrong
or unavailable reference segments, complete reference coverage and checksum 3,000.
The machine differential report states `independent_runs=true` and
`bci_equality_verified=false`. Separate reference artifact paths are recorded in
`runs.txt`; each retains raw stacks, report/profile, log and environment provenance.

This verifies the agreed application segment and operation classes across runs;
it does not assert occurrence identity, semantic BCI equivalence, full inline
chain equality, overhead advantage or enforcement equivalence.

Reference milestone final checks passed: `make verify`, backlog validation,
`git diff --check` and `./gradlew build`. The reference issue remains open for
review; the changes do not modify the profiler's production implementation.

## Full interpreter-generation boundary (blocked, 2026-10-01)

After the runtime and independent-reference gates, an adversarial identity probe
reproduced the documented fingerprint limitation. ConstMethod addresses `0x200`
and `0x800000200` have the same captured low-32-bit `(pointer >> 3)` value. With
the replacement already cached and live, the delayed old captured token is
accepted by the bounded before/after checks. This is a failure of the stronger
generation assertion, not a failure of the previously verified distinct-token
check or stable-workload corpus.

Reproduce with `bash scripts/probe-identity-boundary.sh` (expected exit 1).
It copies the verified generated provider into a separate diagnostic directory,
adds `InterpretedIdentityCollisionProbe.go.txt` as a test, and leaves ordinary
prepared sources unchanged. Evidence: `build/identity-boundary.iAwoCp/probe.log`.
The ordinary test suite remains passing; the probe is not silently skipped or
converted into passing generation proof.

The full class-unloading/reuse identity gate is blocked on a broader captured
incarnation/generation evidence and cache/protocol design. The prior approval
covered public frame quality metadata and bounded cache validation, not a new
capture protocol. Full-address recycling also requires more than merely widening
the fingerprint. No upstream protocol or BPF capture layout was changed here.

## Live disposable-loader investigation (2026-10-01)

The formerly synthetic-only identity concern is now reproduced in the pinned
Temurin JVM. Eighty equal-shape, uniquely named generated classes each used a new
disposable loader and performed ten opens per branch. The target ran as an
ordinary user with `-Xint`, Serial GC, 64 MiB heap and 32 MiB metaspace cap.
JVM class-unloading logs confirmed all eighty generations; all loader weak
references cleared and every generation's byte-read checksum passed.

| Run | Collector | Tokens observed | Correct segments | Unavailable | Wrong generation |
|---|---|---:|---:|---:|---:|
| `capture.x8pMCm` | Raw diagnostics | 1,600 | 20 | 20 | 1,560 |
| `capture.ZlX9mH` | Full-pointer diagnostics | 1,600 | 20 | 20 | 1,560 |
| `capture.UueXxk` | Normal uninstrumented | 1,600 | 20 | 20 | 1,560 |

Generation zero was unavailable; generation one resolved correctly; generations
two through seventy-nine returned generation-one class/method labels. The normal
control's `mw-unload-g00002-s000-A` returned `UnloadG00001.sharedLeaf` and
`UnloadG00001.pathA` with apparently available interpreted BCIs. Thus logging
instrumentation is not necessary to reproduce the observed failure.

Four producer references first published in generation one were reused through
generation seventy-nine. Only three dynamic method checked-lookup logs appeared,
all in generation one. The producer's exact PID/TID/raw-frame cache therefore
reused old definitions without later symbolization/validation. The user-mode
frame-cache bypass is also present, but its observed hits in this run occurred
within the initial publication generation; it is not independently proven as the
cross-generation failure layer here. Full ConstMethod observations were available
only when definitions were published. This does not prove a 32-GiB collision.

Each failing gate saved a manifest, raw events/enrichment, target completion log,
unloading log and `unloading-report.json`; diagnostic variants also retain raw
identity logs. The ordinary unit/race suite is separate from this deliberately
failing lifetime acceptance test. See `identity-investigation.md` for exact
source paths, diagnostic hashes, controls and the resulting design requirements.
