# HotSpot release and runtime qualification — 2026-10-23

**PREPARED_BLOCKED: host-source corrections are reviewed; release, public-native,
persisted-profile live acceptance and full quality are not complete.** Task8 adds
only this handoff, the two existing backlog updates/index and two storage guidance
corrections. No runtime attempt or release action follows from these documents.

The [previous host-source handoff](2026-10-23-hotspot-host-source-handoff.md) records
the **completed previous plan**, including the reviewed26-path node contribution.
It is unchanged dated history, not work to reopen. Task3 below supersedes only its
then-missing native-fixture retention/proof source gap, not its blocked native
outcome. The [observed-profile contract](../../plans/2026-10-18-hotspot-observed-profile-contract.md)
and existing limits remain binding: immutable occurrence/BOOTTIME/process identity,
explicit policy-ineligibility and globally incomplete collection; observed samples
do not establish strict lifetime, logical-thread ancestry or universal JIT/GC safety.

## Four independent outcomes

| Lane | Current result | Exact remaining gate |
|---|---|---|
| Source / remote graph | Task1 Important1–3 and Task3 Important1 **source fixes accepted**; Task8 docs prepared for controller review | Publication selection, actual remote storage resolution and Task2 standalone graph/builds absent |
| MazeWall public native | **BLOCKED_NOT_RUN**, exit code **null** | Independently selected native inputs/custody/permissions and one exact invocation grant |
| Node-agent persisted live | Tasks4 cluster lane,5 server API and7 cases **BLOCKED_NOT_RUN**, exit codes **null** | Released graph, matching server/images/node/trust originals and separate case grants |
| Historical strict / full quality / custody | Strict-eight **FAILED/INCOMPLETE**, unchanged; new full gate **BLOCKED_NOT_RUN**, exit code **null** | Separate maintenance/environment authority and unresolved historical ownership/custody obligations |

Task2 pin/download/build is **NOT RUN**, not an attempted resolution failure.
Tasks4–7 are gated and not invoked; there is no new runtime success, rejection,
skip or cleanup result. Nothing was selected or probed from the ambient environment
or protected historical resources. A null exit code means no invocation, not exit0.

## Source fixes, checks and snapshot identity

Operator-custody identifiers below are **nonpublic references, not publication
hyperlinks**. `P` denotes
`.superpowers/sdd/2026-10-23-hotspot-release-and-runtime-qualification/` in MazeWall;
`A` is its fresh owned0700 `private/task-8-artifacts/`. Reports/packages from earlier
tasks remain unchanged; their old hashes still identify their original snapshots.

### Storage: accepted executable corrections, corrected publication guidance

S is `/home/leanid/Documents/code/java/stack-attribution/storage`, with base
`d963c7941f5bd493751109324e6eba41f7d2b5af` and live HEAD
`4c2e1cf1b64b6169b02b7a9b0ceacafd985aad5f` **plus five uncommitted code/test
corrections and two Task8 docs corrections**. HEAD alone is not the release identity.

Actual review `P/task-1-review.md` found three Important defects. Their five-path
correction and permanent regressions were accepted by
`P/task-1-fix-1-review.md` (3 addressed,0 new Critical/Important):

- Persisted Create now notifies metadata/full watchers before its fallible response
  read; original EACCES still fails the response. No rollback or UID is invented.
- Private tagged, byte-length-prefixed path components prevent typed/legacy and
  field/path-boundary collisions, including invalid-UTF8 legacy byte identities;
  legacy equality and actual owned frame/evidence values remain intact.
- The tagged API recorder requires canonical explicit-false keys in original JSON
  Create/Get bytes, beyond typed decoding. Missing/null/alias/decoy values reject
  without blocking actual acquired-UID cleanup; protobuf negotiation is unchanged.

`P/task-1-report.md:170+` preserves the full RED/GREEN and failed intermediate
attempts, including the lossy JSON-key attempt and corrected test expectations.
Task8 corrects only Minor4 in S `docs/agentless-stack-evidence{,-live-api}.md`:
the pass is historical/local-only **2026-10-19**, the server is retired, and excluded
receipt paths are nonpublic operator-custody references. Original dates, failures,
retirement history and non-release limitations remain; no protected receipt was
opened, copied, resolved or revalidated. Controller review of this new delta is
still required; the original Minor is not relabeled another Important fix loop.

Reused terminal host evidence in `P/private/task-1-fix-1-artifacts/`:

| Receipt | Actual command / result |
|---|---|
| `11-owning-race.terminal.json` | Cached Go, `GOWORK=off go test -mod=readonly -race ./pkg/apis/softwarecomposition/... ./pkg/generated/openapi ./pkg/registry/file/callstack ./pkg/registry/file -run 'TestStackEvidence\|TestFrameSymbol\|TestContainerProfile(Create\|LegacyStackEvidence\|StackEvidence)\|TestUnify\|TestRealWorldCallStackEncoding\|TestGobCallStackEncoding\|TestIgnoreRule' -count=3 -timeout=10m`; exit0 |
| `14-tagged-helpers-race-green.terminal.json` | `go test -mod=readonly -race -tags=agentless_hotspot_integration ./tests -run '^TestStackEvidence(Helpers\|WireExamples)' -count=1 -v -timeout=5m`; exit0,1.300s, including15 original-JSON/cleanup cases |
| `13-tagged-gogo.terminal.json` | `go test -mod=readonly -tags=kubernetes_protomessage_one_more_release ./pkg/apis/softwarecomposition/v1beta1 -run '^TestStackEvidenceGogoProtobuf$' -count=1 -v -timeout=5m`; exit0,0.046s |
| `15-make-test.terminal.json` | `GOWORK=off make test` expanded only to `go test ./...`; exit0,36.665s, terminal2026-10-23T16:51:17.273409Z |

Those runs used cached Go1.27.0 linux/amd64, offline resolver settings and readonly
modules. All31 nondocument contribution paths remain byte-identical to source fix1,
so docs changes invalidate no executable check. No suite was repeated; no full
repository race, actual API, generation or tested-intermediate claim is made.

The **new** review package is in A, not substituted into either old package:

| Current artifact | SHA-256 |
|---|---|
| `storage-review-full.diff` — exact33 paths, binary/full-index/80-line context | `b7e1420fcb2035dc493118f94024ff7ab32dc3038faa7631c5763bca2af0aecd` |
| `storage-source-inventory.json` — base/candidate/fix1/current identities | `4f00c6bbd2248dd1f6c8a0447b475e29db154ed5aa487709873316362adcfb3d` |
| `stack-after-ignore-rule.diff` — updated32-path second scope | `1f20b7643ed7ae6eabbebbc70033f9af3f387945b17cf41b68c7fd2c00bba51f` |
| `ignore-rule-complete-separation.diff` — unchanged four-path first scope | `5966fc07d08557f8cbae5d944001bd6e833b2669cd2db21dce07bca9d6319bbd` |

`storage-source-sha256.txt` and `publication-scope-selection.json` retain every
current path and the updated proposal. Four IgnoreRule paths followed by32 stack
paths overlap in **three generated paths:33 unique paths**, not36. That semantic
separation is reasonable source scope, **not approved publication**, and the
unchanged synthetic intermediate is neither a tested commit nor a release.
Excluded build receipts/raw candidate history must not be published by association.

Still unselected: publication owner/repository, target branch/base, actual
submission/merge/publication action, explicit IgnoreRule topic acceptance and exact
package grant; then an actual reviewed remotely resolvable `STORAGE_REVISION` with
resolver Version/Sum/GoModSum/Origin. Matching server source/image is also absent.
Bridge v0.2.0 remains published/unchanged; it does not select storage or unify the
independent node/storage/collector module/compiler graphs.
[Issue912](../backlog/issue-20261003-180846-912-align-kubescape-hotspot-contribution-dependency-an.md)
owns these storage findings and the remaining integration contract, and stays open.

### MazeWall Task3: host-source complete after the bounded-retention fix

M HEAD remains `a28cbe293289f2549858aa232d9e6ebd7c4756dd` plus the frozen
snapshot-relative dirty changes, not a new source commit. Task3 adds test-only
maps byte retention/identity checks, sampled compiled-frame proof and cleanup-safe
compound failure diagnostics, using the existing fixture/API and fixed workload.
`P/task-3-review.md` found a three-identity cause/suppressed counterexample:64 copies
of one ordinary suppressed reference hid a following native exception below the
64-identity cap. The small loop now deduplicates before capacity is exhausted;
actual errno/diagnostic/discard/partial fields survive once, with generic-root
ownership still unknown. No API, budget or failure framework changed.

`P/task-3-fix-1-review.md` accepts that correction (Important1 addressed,0 new
Critical/Important/Minor). `P/task-3-report.md:178+` and
`P/private/task-3-fix-1-artifacts/{file-manifest.json,updated-task3-snapshot.diff}`
bind the current four files; the diff SHA-256 is
`d3a045fcc18a7a9e13d5cf138ae04d2a354184224dae1a104afb1d885370553c`.

| Current file (under `profiler/`) | SHA-256 |
|---|---|
| `src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotObservedAcceptanceEvidence.kt` | `8bb55ef6dd9424e7577b60dfdc601fbe053344214843fc2bc897e769d60dd363` |
| `src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotObservedAcceptanceEvidenceTest.kt` | `e0fdbc1102aa66db5c8484febcdb63e103a43439de65dc2cd795b61197beac38` |
| `src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotObservedIntegrationTest.kt` | `d2c330b946fdf6a605eacc4fb055b109406aefd4405ff381a2477ddb208bd06f` |
| `HOTSPOT_AGENTLESS.md` | `1baf3265e212fa58cdc8e758c593fd4c7e7b374e0748b66b885bd9e2af455253` |

Actual `./gradlew --offline --no-build-cache :profiler:test --tests
io.mazewall.profiler.hotspot.HotSpotObservedAcceptanceEvidenceTest` was behavioral
RED (15 tests,1 missing-native-artifact failure), then GREEN (15/15,0 skips/errors)
in `duplicate-prefix-{red,green}/receipt.json` under that fix package.
`./gradlew --offline :profiler:compileTestKotlin :profiler:ktlintTestSourceSetCheck`
also exited0 (`compile-and-test-format/receipt.json`). Native opt-ins were disabled.
The earlier four-class `final-focused-green/receipt.json` in the original Task3
package retains **27 executed/2 native-opt-in skips/0 failures/errors** at its
pre-fix hashes; it is reused only for unchanged consumer/backend/identity classes,
not asserted as a rerun of the amended helper. All222 protected main hashes were
unchanged; production ABI evidence is reused, not freshly executed by Task8.

Original Minor2 remains **DEFERRED to controller final triage**: native coordinator
complexity21/limit15 and length111/limit100. Review found no cleanup bypass.
No suppression, arbitrary split, lifecycle framework or cleanup refactor is added.

## MazeWall native gate — independent, unexecuted

No current assets/manifest path/hash or independent trust provenance, collector
source/build/compiler identity, JVM executable/mapped libjvm identity, host/boot/
procfs/permission selection, evidence/owner custody or **one-invocation authority**
has been supplied. None was recovered from compatibility constants, old admission
receipts or historical resources. This lane does not wait for storage publication.

Before any invocation, the operator must bind those actual inputs, full-byte/OCI
verification, target identity, original maps/diagnostic retention and permitted
collector/target cleanup/hold ownership. Public evidence is bounded original
record/summary JSON plus diagnostics, **not a complete raw stdout transcript**;
any required full-transport arrangement needs separate review before execution.
Preserve original non-skipped XML and retained scratch exclusively **before**
asserting pass or running a later Gradle gate.400/4/20,400 unique tokens,360 useful
routes and actual marked nmethod PC-provenance checks remain unchanged. No route
counts, maps, compiled samples or retirement observations exist for a new attempt.

## Node persisted live gate — exact blocked lane

The previous26-path source/review and terminal runner885.429s/verifier946.683s/CLI
race and vet evidence are reused **only through the named previous handoff**, with
its source/graph limits; no old workspace audit or rerun occurred. Task2 still
needs actual remote storage resolution, `GOWORK=off` standalone node/run/verify
builds and affected host checks. No local replace, guessed version or downloads.

Tasks4 cluster lane/5/7 additionally require separately selected immutable node,
storage and plain-JVM workload images, source/module/compiler identities and actual
manifest/import custody; real kubeconfig/context, node name/UID/boot/kernel,
initial procfs/PID namespace/cgroup origin and local CRI; UID1000 evidence-parent
ownership and actual `/root` traversal/`/root/tracers.tar` access; effective exec
capabilities, LSM/Yama and BPF/perf/uprobe/process-memory permissions. A UID or
requested capability list proves none of this. No probe/provisioning was performed.

Independent original `NodeOrigin`/`BuildReceipt` bytes and custody are missing,
as are the matching selected server's original JSON/protobuf full Create/Get,
explicit false fields, actual UIDs and checked Delete evidence. Task5 must precede
feature enablement. Historical server success is not current-server selection.
Task7 positive, recreation, optional new-route and denial/exit/pressure/hung-owner/
churn/unload controls each need their exact authority and evidence classification;
host fault tests are not live controls. Positive/recreation/new-route invocations
are separate grants, each with two15s windows, not retries hidden in one grant.
Default-disabled configuration and baseline sensors remain unchanged. No N
acceptance document is created because Task7 has not started.

## Historical strict, full quality and custody

Strict-eight remains **FAILED/INCOMPLETE**. Consumed HOST/N3 grants and unknown
resource owners/cleanup obligations remain unchanged, not retired by this handoff.
The S pages preserve the distinct dated2026-10-19 local API pass/failed first gate,
setup/permission failures and recorded later owned-cluster retirement. Neither
those historical claims nor protected receipts/resources were revalidated here.

Full quality remains blocked. Actual `./gradlew --offline :profiler:detektTest`
started2026-10-23T17:30:06.117592Z and exited1 after44.683s at M HEAD above **plus
the pre-fix Task3 snapshot**, whose helper/test hashes were `c1a8b701…`/`df22ca70…`
(fixture/guidance hashes unchanged). `P/private/task-3-artifacts/final-detekt/receipt.json`
SHA-256 `c6d7a0d0790c34141533e00f5bc8eb02755fa701f17231f6206723d3c6c8d790`
binds all four exact hashes; its `stdout-stderr.log` SHA-256 is
`e6fc6a767aa1e7d3ab9ee27b8e472e63c513d7ce787b4ddc235f3354a07dc987`.
That run reproduced16 out-of-scope errors:11 locale,3 swallowed,1 unreachable,
1 generic throw. It is **not a current-count assertion after fix1**. Existing
[issue528](../backlog/issue-20261022-221346-528-restore-profiler-test-detekt-gate-without-weakenin.md)
retains the owners/receipt; maintenance fixes were not selected, no rules weakened.

No `./gradlew build` or N CI-equivalent privileged gate ran. Full M build includes
integration suites and needs its own approved environment; any future full build
must disable observed acceptance and preserve earlier native XML first. N's
`.github/workflows/go-basic-tests.yaml` gate includes gadget preparation/privileged
tests; no downloads, `make gadgets`, sudo or CI runner selection is inferred.
Native CI remains unconfigured, not a speculative workflow label.

Task8 validation is limited to exact-package local links/anchors, snapshot/scope/
hash preservation, read-only patch checks, scoped M/N/S whitespace checks and
`./scripts/adkw check-backlog`. `P/task-8-report.md` and A retain terminal receipts,
six-path before/after snapshots and the new delta/package hashes. No issue is
archived, no count invented, no staged `CLAUDE.md` or unrelated dirty work discarded.
Controller owns the combined Task8 review and final Minor triage. No commits,
staging, publication, downloads, native/API/CRI/cluster/image/fullgate action or
background process is part of this handoff.

## Subsequent native-first execution checkpoint

The [native-first blocked result](2026-10-23-hotspot-public-native-attempt.md)
records source verification and prepared exact inspection/run recipes after the
operator requested implementation. All four accepted Task3 files and222 protected
production paths remain unchanged. Actual host/JVM/assets/trust/output/custody
selections and the separate inspection/native grants are still absent. No probe,
Gradle/native invocation, new XML or target/collector owner exists for this plan;
exit codes are null. This additive checkpoint changes none of the earlier
release/live/fullgate/strict or historical custody outcomes.

## Subsequent passive input discovery

Following operator direction to find/create available inputs, the
[native-first result](2026-10-23-hotspot-public-native-attempt.md) now records fresh
host/tooling discovery and a read-only matching Temurin JVM extracted from the
source-pinned local image cache, with verified blobs/layers and libjvm fingerprint.
No image/container was started or pulled and no JVM/collector/Gradle/BPF test ran.
Normal asset installations are stale strict2 inputs; narrow reuse of a retained
observed3 release candidate and an approved native privilege/run route remain
separate decisions. This supersedes the earlier all-inputs-absent checkpoint,
not any historical native/live/fullgate/retirement outcome.

The operator later approved narrow retained-bundle verification/copy. Matching
observed3 assets now join the prepared JVM as concrete read-only inputs; full
original A/B and copied byte/OCI verification passed through released bridgev0.2.0.
The updated native-first result records manifest453ff8c2…/collector19bdcfb2… and
actual Go1.26.0/compiler/module provenance without rebuilding or consuming old
runtime results/grants. Only the explicit native privilege/provenance and one-run
choice remains. No sudo/setcap, collector/JVM/Gradle test or BPF/perf probe ran.

The operator then selected the exact current-host privilege/read/one-test/removal
scope. Its first sudo-n namespace-read precondition exited1 because interactive
authentication is required, before readlink executed. No capabilities were assigned
and no native attempt began; the invocation grant is unused. Original failure
receipts and the approved request are retained in private/native-permission-1/ of
the native-first workspace. This is an authentication-blocked precondition, not a
failed capture or permission to use Docker/root-shell fallback.

## Subsequent Docker-assisted public-native attempt — rejected

After the operator requested the Docker plan amendment and then said “ok,
implement”, the single native invocation ran21:54:48–21:56:20 UTC on2026-10-23.
The [original-result review](2026-10-23-hotspot-public-native-attempt.md) records
Gradle exit1, fresh XML1test/0skips/1failure and the primary rejection
`mapped JVM file identity changed` before Ready; collector137 is also retained.
The partial snapshot has no records and is unfinalized/policy-ineligible.

Rootful Docker only handled exact private-collector capability setup/removal.
Both acquired helpers exited0 and were removed without force; host capability
absence was checked. The unchanged host-side/non-root fixture reported no pending
ownership and target PID1698207 exited0. Original XML/maps/failure/scratch and
helper CIDs/statuses remain in the native-first private attempt/permission outputs.
The one-attempt grant is consumed; no automatic correction/rebuild/retry follows.
[Issue185](../backlog/resolved/issue-20261023-215710-185-investigate-hotspot-mapped-jvm-device-mismatch-on-.md)
owns the observed maps-versus-stat device mismatch investigation; the exact
filesystem mechanism remains unproven. Independent outcome review remains pending.
Storage release/live, issue528/fullgate, historical strict-eight and unrelated
owners/grants retain their earlier separate status.

The operator subsequently selected source correction of that failure. Canonical
and experimental identity discovery now compare the opened JVM descriptor with
the kernel's selected map_files stat identity rather than comparing incompatible
Btrfs maps/stat device representations. Focused RED/GREEN/race checks retain
replacement/missing/inode/permission rejection. The old collector bundle and
rejected native result remain unchanged; corrected-source verification is not a
new public-native or Kubescape acceptance claim.

The2026-10-24 correction checkpoint resolves issue185 with focused RED/GREEN/race,
prepared Go vet and race-tested package evidence in both native source owners.
The configured dirty checkout's `./gradlew --offline build` also completed exit0
(228tasks,77executed/151up-to-date; existing warnings/skips retained). Native opt-ins
were disabled. This replaces neither the failed native result nor independent
release/live/strict/default-CI and maintenance decisions; no issue528 change or
new collector-bundle/native acceptance is inferred from this build.

## Subsequent corrected-artifact public-native attempt2 — rejected

The operator selected the corrected collector rebuild/verification and one fresh
native invocation. The [latest native result](2026-10-23-hotspot-public-native-attempt.md)
records pinned Go1.26.0 build, fresh full-byte/OCI verification and the terminal
22:41:33–22:41:49 UTC2026-10-23 run: Gradle1/XML1test/0skips/1failure/0errors.
Corrected Btrfs identity discovery passed, but pinned Gadget local.Runtime.Init
unconditionally rejected UID1000 before Ready (“must be run as root”). No native
permission errno was observed; later BPF permissions remain unqualified.

Target1823767 exited0/pending ownership false; both permission helpers and compiler
containers were removed and file capabilities cleared. Original attempt1 and
attempt2 evidence and both consumed grants are retained independently. No active
check or attempt3 authority remains. Issue810 and the native-first workspace's
capability-init-proposal.md describe an unselected correction preserving actual
preflight/host checks without UID spoofing or kernel-denial fallback; root collector
execution is a separate scope. Independent native outcome review remains pending.
Storage publication/remote STORAGE_REVISION, released consumers and persisted
Kubescape live acceptance remain separately blocked/unselected; strict-eight and
historical ownership are unchanged. The configured build did not close issue528.

## Capability-aware source continuation — native attempt3 still unused

The operator subsequently selected the proposed capability-aware adapter,
regressions/completion gate, fresh verified artifact and one attempt3. Mirrored
source changes preserve real preflight/identity/host checks and errors without
the upstream UID-only wrapper. Both UID1000 RED/GREEN suites, owning race and
prepared vet pass; pinned Go1.26.0 build and released full-byte/OCI/negative checks
pass. The [latest result](2026-10-23-hotspot-public-native-attempt.md) binds hashes.

Two completion builds failed the separate existing USER_NOTIF cold-network test
(issue752), with a five-second default-proxy deadline exhausted before direct TCP
connect. Unchanged focused diagnostic passed, not a repair; original failures and
real shutdown ENOSYS remain preserved. No active check, gate waiver or further
unchanged retry. An explicit direct-loopback fixture correction is proposed but
unselected. Attempt3 remains unused; no new permission helper/target started,
capabilities absent. Issue810 stays open pending completion. Storage/released
graph/live/Kubescape, strict-eight, issue528 and historical owners remain separate.

## Direct-loopback gate passes; attempt3 rejected at kernel-symbol permission

The separately selected fixture correction uses explicit Proxy.NO_PROXY for its
real loopback CONNECT. All15owning cases pass, then the configured changed-revision
fullgate completes0 in6m12s (228tasks:33executed/1cache/194up-to-date), with native
opt-ins disabled and existing warnings/skips. Earlier failed gates and issue752's
underlying timing/ENOSYS retirement questions remain preserved, not fixed by fiat.

The one selected attempt3 ran23:22:06–23:22:18 UTC2026-10-23: Gradle1/XML1failure,
collector1. Corrected identity and capability-aware initialization passed; OTel
then rejected zero kallsyms addresses before Ready. Native errnos[] and partial
0records/unfinalized/policy-ineligible remain rejection evidence. Target1886276
exited0/pending ownership false; both exact-file Docker helpers were removed and
capabilities cleared. The [current outcome](2026-10-23-hotspot-public-native-attempt.md)
binds originals,81new+68+81old evidence hashes and independent-review limits.

Issue810 resolves only the source defect. Issue761 records kptr_restrict1 and the
missing CAP_SYSLOG authority; the proposed addition and fresh attempt4 are **not
selected**. All three grants are consumed, no active check or silent privilege/
sysctl fallback. Source/fullgate success does not qualify native or persisted
Kubescape behavior. Other release/live/strict/maintenance/ownership lanes stay separate.

## CAP_SYSLOG continuation — bounded public-native fixture accepted

The operator directed applying the identified capability and trying the test,
without another routine-step prompt. Fresh unchanged asset4 integrity/OCI checks
passed; exactly one attempt4 ran23:38:21–23:38:31 UTC2026-10-23, Gradle0 and
XML1test/0skips/failures/errors. The [current native result](2026-10-23-hotspot-public-native-attempt.md)
records400unique marked operations,380useful correct routes and176marked compiled
application events (88per route), plus selected401event accounting and complete
terminal/physical drain with pending0. Global collection-loss completeness and
policy eligibility remain false; sampled compiled proof is not universal JIT/GC.

Only CAP_SYSLOG was added to the private collector's existing grant. Host-side
test/collector/JVM remained UID1000, workload/budgets unchanged, kptr_restrict1.
Target1896794 exited0/pending false; both helpers exited0/stopped/removed and file
capabilities cleared.484new and230prior native evidence hashes are preserved;
issue761 resolves the selected kernel-symbol blocker. Independent outcome review
is pending. All four grants consumed, no active check or fifth attempt. No source
gate was repeated on unchanged bytes, and no root/sysctl/publication fallback.
Storage/released consumer graph and persisted Kubescape live lanes remain separate
and unexecuted; strict-eight, issue528/752 and historical custody remain unchanged.
