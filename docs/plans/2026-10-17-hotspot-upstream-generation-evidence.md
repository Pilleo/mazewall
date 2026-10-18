# Pinned upstream generation audit evidence

## Decision and scope

The reviewed source already captures and checks `nmethod._compile_id`. This audit
does **not** qualify compiled/GC lifetime or a loaded native object. Production
admission and policy eligibility remain refused for this expansion; the separately
qualified pinned interpreted/Serial profile is unchanged. See the
[mechanism decision](2026-10-17-hotspot-jitgc-native-mechanism-decision.md) and
[governing specification](2026-10-17-hotspot-upstream-alignment-spec.md).

Task1 is commit `4ba924a27f8af5e13be94acef2ee8ba20dfefc4e`; Task2 is
`f568d899572f094eb97665fe5f73e0f94de75b8f`, Task3's base. Canonical and experimental
`internal/qualification/jitgc/capture_audit.go` are byte-identical, SHA256
`e5417423cc14a5082c8ab90874502385103f1ccbd8606a354d12e21d866e6fd7`.
The audit accepts only capture ABI **2**, profiler revision
`ef6cf29492c8a980f889052903fa2dd0c0765cc0`, patch
`618c3d67d71e86dfb0befb13f73498402af6110f1f2fceb3784c53785b0da00c`.
Its scope is `reviewed_pinned_source_contract` / `compiled_or_concurrent_gc`;
`production_qualified` is always false. The candidate helper consumes that local
mirror, not a second list of missing requirements.

## Existing generation checks and actual host characterization

The [pinned BPF capture](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/blob/ef6cf29492c8a980f889052903fa2dd0c0765cc0/support/ebpf/hotspot_tracer.ebpf.c#L577-L589)
sets `ptr_check` from the compile ID; `calc_line` packs it into the low 32 bits.
The [pinned decoder](https://github.com/open-telemetry/opentelemetry-ebpf-profiler/blob/ef6cf29492c8a980f889052903fa2dd0c0765cc0/interpreter/hotspot/instance.go#L401-L437)
passes the captured check to `getJITInfo`. The prepared patched source retains the
matching cache guard and fresh-read mismatch rejection. Its actual initialized
LRU/`RemoteMemory` APIs were exercised with test-only Go overlays; dependency trees
and module cache were not edited.

| Task1 execution | Actual outcome |
| --- | --- |
| Baseline, three named compile-ID tests | 3 passed, 0 skipped; exit 0 |
| Virtual cache-guard mutant | Built; `TestPinnedCompileIDRejectsStaleCache` failed its assertion; exit 1 |
| Virtual fresh-read-guard mutant | Built; `TestPinnedCompileIDRejectsFreshMismatch` failed its eviction-error assertion; exit 1 |

The cache-hit test deliberately requests/caches generation **7** while current
remote bytes contain **9**: reuse of owned cached symbols is not retention of
current native nmethod memory. The read mutant reaches a downstream ConstMethod
EOF instead of the required generation-eviction error; it is not a panic/timeout
or proof that the minimal fixture owns valid native metadata.

Task2's genuine missing-symbol RED preceded its implementation. Uncached
canonical host/race tests passed **4/4 each**, mirror host/race **1/1 each**, no
skips. Its initial local wrapper wrongly expected the mirror's package to contain
canonical-only lifetime tests; original outputs and the accounting correction are
retained. No product assertion or gate was weakened.

## Candidate admission and Task3 RED/GREEN

`candidate.Run` now completes the existing full common asset verifier **before**
output creation or any compiler/target/collector acquisition. Only successful
fresh run-root creation precedes writing its single `capture-audit.json`; the
final successful summary links it as `capture_audit`. The helper reads at most
65,537 bytes, rejects manifests larger than 65,536 bytes, invalid UTF8/JSON and
unreviewed ABI/revision/patch keys, and joins read/close failures. A minimal source
manifest is only a unit fixture: it cannot admit an asset installation.

Actual Task3 RED first reported undefined `writeCaptureAudit` (package build
failure, exit 1). After adding only the helper, the source-audit test passed but
both missing-assets/source-contract-only cases failed with `output created before
common admission` (exit 1). After the runner correction, uncached host and race
checks each passed **30 top-level tests plus 16 subtests**, zero skips/failures.
Both logs prefix the `TestCommandStdoutRemainsSeparateFromDiagnostics` PASS marker
with diagnostic output; that top-level pass is included in these totals:

```bash
# cwd: experiments/hotspot-agentless; prepared modfile, existing offline dependencies
go test -buildvcs=false -modfile build/experiment.go.mod -count=1 -v -timeout=120s ./internal/candidate ./internal/driver ./internal/qualification/jitgc
go test -race -buildvcs=false -modfile build/experiment.go.mod -count=1 -v -timeout=120s ./internal/candidate ./internal/driver ./internal/qualification/jitgc
```

The tests include the exact 65,536/65,537-byte boundary, rejected-input no-sidecar
behavior, strict independent identity/whole-capture/token/terminal checks and
existing owner failure-handle tests. Compilation caching remained enabled; no
fresh recompilation of every dependency is claimed.

## Actual rootful six-case runs: original failure and receipted follow-up

The named CLI was built with `-buildvcs=false` and the prepared experimental
modfile after collecting the actual non-test/non-race selected package graph:
**203 package records, 1,376 selected path spellings and 1,375 serialized hashed
files**, covering source/module/toolchain/environment inputs, module replacements
and the full graph. The one filtered spelling, relative `build/experiment.go.mod`,
aliases the already-hashed absolute experimental modfile; it adds no unique input.
Output SHA256 was recorded **before** execution.
All commands used the authorized checkout; the CLI ran from the experimental
module directory because its real entrypoint uses `os.Getwd()` as root.
Self-review found that the Go input selection did not include the two Java fixture
sources compiled later by the runner. The first matrix and its failure are retained
unchanged. One supplementary fresh matrix recorded both Java sources, both lock
files, every canonical asset and the unchanged CLI **before** fixture compilation/
acquisition. This corrects the receipt gap for that new run, not the first receipt.

```bash
# W=/home/leanid/Documents/code/java/jseccomp/.worktrees/hotspot-review-remediation
# E=$W/profiler/hotspot-agentless/build/reports/upstream-generation-task-3
# cwd: $W/experiments/hotspot-agentless
go build -buildvcs=false -modfile build/experiment.go.mod -o "$E/mw-experiment-tools-task-3" ./cmd/mw-experiment-tools
# Hash/record the named output before invoking it.
"$E/mw-experiment-tools-task-3" candidates --assets "$W/profiler/hotspot-agentless/build/assets" --output "$W/profiler/build/reports/hotspot-upstream-generation-candidates" --docker-host unix:///var/run/docker.sock
# Supplementary run, after matrix-receipted-inputs-before.json was recorded:
"$E/mw-experiment-tools-task-3" candidates --assets "$W/profiler/hotspot-agentless/build/assets" --output "$W/profiler/build/reports/hotspot-upstream-generation-candidates-receipted" --docker-host unix:///var/run/docker.sock
```

The canonical read-only verifier and the runner's common verifier both admitted
the actual canonical bundle, including every asset byte and the full reachable
local OCI graph. Manifest SHA256 is
`0584b244540dbc615a126b690144b68eb774963f6c5c969a252445abb5669b04`;
collector SHA256 is `afb3fd5a0a994a06ed70e42689abe586b926b21fe2967db2c423c73a60c3907e`;
Gadget OCI digest is
`sha256:3ae6007bdf574e1c082b5f185f38d3f60bb678d9e964f05a658da4ac5670de68`.
The fresh output root did not exist. Existing fixture compilation, target and
collector paths use **`--pull=never`** and digest-pinned local JDK/Go images.
No native helper/BPF/asset was rebuilt or repinned.

| Tuple, original run | Actual verdict | Checked owner retirement |
| --- | --- | --- |
| C1 / Serial | 40/40 marked; 31 useful, 9 application-unavailable; lifetime false | true, no cleanup failures |
| C2 / Serial | 40/40 marked; 31 useful, 9 application-unavailable; lifetime false | true, no cleanup failures |
| `-Xint` / G1 | 40/40 marked; 34 useful, 6 application-unavailable; lifetime false | true, no cleanup failures |
| Virtual / 1 carrier | 256/256 marked; 1 carrier TID, 0 logical identities, parent unavailable | true, no cleanup failures |
| Virtual / 2 carriers | 256/256 marked; 2 carrier TIDs, 0 logical identities, parent unavailable | true, no cleanup failures |
| Virtual / 4 carriers | **Failed**: collector `stack definition task identity mismatch`; no admitted result | true, but retained nonzero collector exit in cleanup failures |

The matrix exited **1**. The failed tuple's native summary reports 261 received,
227 resolved, 34 unavailable and `terminal_accounting_complete:false`; the existing
offline whole-capture verifier independently refuses it as `incomplete capture`.
Equal row counts or a terminal-looking summary cannot override that failure.
Five successful result files remain; the final run-root `summary.json` is absent
because `Run` returned the failure. It was not synthesized. The single source
sidecar remains at the run root (SHA256
`e1c8017507e20a4855e8b93db42c2bbc8a0442b2c321c0b4deab97565e6576f7`).

All 13 acquired compiler/target/collector container receipts have checked
retirement; all 12 attach wrappers are joined and their `/proc` paths were absent
afterward. Pending container/acquisition/wrapper arrays are empty in all seven
cleanup reports. The failed tuple's exit error is preserved despite retirement.
Rootful before/after owner-labelled inventory is empty. The guard was traced to
the existing symbolizer's immutable task/definition identity boundary; the actual
log does not identify the mismatched field, so its deeper cause is **unverified**.
This is a negative experimental observation, not a native-prerequisite skip or a
successful six-case matrix. The follow-up does not erase it.

The **supplementary receipted run exited 0** on the same CLI/source/asset graph:

| Tuple | Actual verdict | Checked owner retirement |
| --- | --- | --- |
| C1 / Serial | 40/40 marked; 27 useful, 13 application-unavailable; lifetime false | true, no cleanup failures |
| C2 / Serial | 40/40 marked; 34 useful, 6 application-unavailable; lifetime false | true, no cleanup failures |
| `-Xint` / G1 | 40/40 marked; 27 useful, 13 application-unavailable; lifetime false | true, no cleanup failures |
| Virtual / 1 carrier | 256/256 marked; 1 carrier TID, 0 logical identities, parent unavailable | true, no cleanup failures |
| Virtual / 2 carriers | 256/256 marked; 2 carrier TIDs, 0 logical identities, parent unavailable | true, no cleanup failures |
| Virtual / 4 carriers | 256/256 marked; 4 carrier TIDs, 0 logical identities, parent unavailable | true, no cleanup failures |

Its six results pass the unchanged independent identity/token/terminal validators,
with `terminal_accounting_complete:true` in each native summary. All lifetime and
production qualification remains false, as does policy eligibility. This fresh
root has exactly one byte-identical source sidecar and a real successful summary
link `capture_audit:"capture-audit.json"`; summary SHA256 is
`40147588246467096b7dff2ce0456f92a7db0e93e34b9aad408944172bdbc5fb`.
Its 13 acquired containers and 12 attach wrappers also have checked retirement,
empty pending/failure arrays, absent wrapper `/proc` paths and an empty rootful
owner-labelled inventory afterward. This is one additional observation for a
specific receipt omission, not a reliability benchmark or native promotion.

## Local receipt index and provenance limits

These are **local, ignored evidence**, retained in the authorized checkout, not
published generated artifacts. Paths below are repository-relative; adjacent
`*.command.json` files record argv, absolute cwd, environment overrides, finite
timeouts, start/end times, exit status and log hashes. A1/A2 originals, including
baseline/mutant failures and inventories, remain byte-preserved.

| Local path under `profiler/hotspot-agentless/build/reports/` | SHA256 |
| --- | --- |
| `upstream-generation/artifacts-index.json` | `b0a08bedb6da77f6ba7eadecb07cdce7d461a756ceddb3e2060954def0954feb` |
| `upstream-generation/SHA256SUMS` | `746b3b0c7208053f60053b0a1f6dd669a05546bda91cb3fcaf761248269993be` |
| `upstream-generation/baseline.log` | `d8c0c645361a92a96032645380070b38fa1e9aced4707a6e95a59b966ab3d4f6` |
| `upstream-generation/cache-mutant.log` | `e5ab465d10bc6c6098a9702e313a4a331d6365af47ad45544b2d02e7100d1261` |
| `upstream-generation/read-mutant.log` | `5446e78821e811d76809b09c85c3781da7568ee79aa7fd28d5b8e06da3e7430c` |
| `upstream-generation-task-2/artifacts-index.json` | `c6abffbfc5ba11610fc7d149cd85984de3850bc624be6825bbe01763d1037a88` |
| `upstream-generation-task-3/cli-inputs-before.json` | `802c4fac5d4facdb5d58c7b970acc1d6353aca87474a83303116e353b748aba7` |
| `upstream-generation-task-3/mw-experiment-tools-task-3` | `8ae8c95187249a0b78c660f8557e80ad876c1b1753cc03de2cb3a6b7f3025223` |
| `upstream-generation-task-3/matrix.command.json` | `4bc559f3754f0b9f6c144f84460894e0895d31a361d05d04039a17d8bf5247d6` |
| `upstream-generation-task-3/matrix-observations.json` | `07abecd9adfa766fc2b21170118cfa3efaa27f62d4ce7d994a890b3867b881c0` |
| `upstream-generation-task-3/matrix-receipted-inputs-before.json` | `57c9ab18b8093d39a44fff5cca813249e536f06c9709465ec68c2d6479f8b137` |
| `upstream-generation-task-3/matrix-receipted.command.json` | `2e8ec826911dbd9864d2191668f6b34c25f941a9f4cbf8f663cf1e25822a07c8` |
| `upstream-generation-task-3/matrix-receipted-observations.json` | `fe9edc4ab3970f9f47a118ea2d71c46b7372d8e7d4c4a7630c15fff31c6e24d5` |

Task1's before-build input receipts are `baseline-inputs-before.json` SHA256
`a56db1516b2775744443c45fb8dd8b73b949ca62f3e9ead3df7739ba76b2d810`,
`cache-mutant-inputs-before.json`
`714c5c3ca9e912c098ed25e578011621646cd93aa5eff03e0243ab1d684862f5`,
and `read-mutant-inputs-before.json`
`f3224dd3c30780610a2f596f684546e790b29a4bacd537c96cd4bf5b6e96bf66`.
Same-plan local `task-1-report.md` / `task-2-report.md` retain binary receipts and
full-hook cache/skip accounting; Task3's local `task-3-report.md` records its final
scoped commit/full-hook result. The local matrix artifacts are under
`profiler/build/reports/hotspot-upstream-generation-candidates/` with per-case
identities, captures, original stderr, results and cleanup reports; their hashes
are in Task3's `matrix-artifacts.json`. The supplementary root is the same path
with `-receipted` appended; `matrix-receipted-artifacts.json` inventories it.

The actual host is Go **1.26.8**, GraalVM/Javac **25.0.4.1.1**, Linux
**7.2.10-x64v3-xanmod1**, rootful Docker **29.9.1**. Host Go commands used
`GOPROXY=off GOSUMDB=off GOTOOLCHAIN=local`. Receipts retain actual local prepared
profiler/Gadget replacements and notation-go replacement; no downloads occurred.
The candidate JVM is the pinned Temurin container, not the host GraalVM control.

Contemporaneous source receipts link only these new host builds to their selected
inputs. The old Go/native helper's historical exact-source linkage remains
**INFERRED/UNVERIFIED**; today's hashes do not repair it. Live loaded-BPF identity,
code/metadata retention and concurrent-reclamation exclusion were not proved by
asset verification, ABI names, useful prefixes, source tests or this matrix.
The [JIT/GC issue](../internals/backlog/issue-20261005-193139-440-qualify-pinned-hotspot-jit-and-gc-lifetime-candida.md)
stays open. Publication of the corrected
[accounting proposal](2026-10-17-hotspot-selected-accounting-contract.md) leaves
B's native map/schema/pin implementation explicitly **not authorized**.

## Corrective correlation-identity observation — 2026-10-18

Subsequent analysis of the **original unchanged failed bytes** establishes that
correlation ID **516936189570219** appears in nine events for PID **621929**,
TIDs **622909 / 622910**. The first affected enrichment retains the fatal task
identity mismatch; the original **261 received / 227 resolved / 34 unavailable /
zero recovery attempts / `terminal_accounting_complete:false`** remains refused.
The earlier statement that stderr alone did not identify the conflicting field
remains accurate; JSONL analysis now establishes the cross-task alias independently.

Offline characterization of the unchanged actual production C additionally shows
three distinct-key equal-clock uniqueness **RED exits 1** (task, frame, epoch),
passing same-key replays and one passing distinct-clock control. The initial
harness compiler failure is not the regression RED. Host-C/helper mocks establish
a sufficient allocator defect, not historical clock values, real kernel/JVM/live
transport behavior or a fixed production allocator. All original observations and
hashes above remain historical, including the later successful matrix control.

See the [source-backed correlation-identity evidence](2026-10-18-hotspot-correlation-identity-evidence.md)
for receipts and the [scoped allocator approval request](../internals/backlog/resolved/issue-20261018-035018-153-replace-clock-only-native-correlation-id-allocatio.md).
The proposed shared same-map-lifetime positive non-reusing atomic allocation and
its exact contract remain pending operator design review. No native/map/layout/
resolver namespace/pin change or broader lifetime/reliability qualification is
authorized by this corrective documentation.
