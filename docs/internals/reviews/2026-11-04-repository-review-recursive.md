# Repository-wide recursive follow-up — 2026-11-04

## Outcome

**Recursive delegation succeeded: 18 completed review executions, no delegation
failures. Six additional P2 residuals were reconciled into three existing issues.**
The earlier corrective priorities remain applicable. This follow-up also credits
newer owner-produced passing build/native evidence and the changed small-lockdown
release strategy. It does not establish whole-checkout release qualification.

Read with the [first review](2026-11-04-repository-review.md),
[finding register](2026-11-04-repository-review-findings.md),
[ownership register](2026-11-04-repository-review-tracking.md), and
[historical verification](2026-11-04-repository-review-evidence.md).
The first review's failed build is dated evidence: a later owner build succeeded,
as detailed below. Neither its initiating failure cause nor every current artifact's
qualification is resolved by that later success.

This retry changed review/backlog documentation only. Three active owners were
expanded; no new issue, closure, priority change, application fix or publication
was made. Inventory remains **325 active: 324 open and one deferred; 739 resolved**.

## Actual recursive coverage

Four leads delegated twelve focused reviewers; two of those reviewers delegated
one narrower specialist each. Thus **4 leads + 12 children + 2 specialists = 18**
executions, additional to the first pass's 23. Coordinator integration, hash checks
and imported owner receipts are not extra independent reviews. Every report was
read and integrated; the new findings were checked against connected source and
existing ownership.

All retry nodes used the configured `reviewer` role, with no model override.
Sessions identify `openai/gpt-6.1-sol`; the role is configured for max effort, but
effort was not independently exposed as runtime telemetry. No Astra or medium
override was used in this retry. Actual deepest chains were coordinator → A → A2
→ A2a and coordinator → C → C1 → C1a: four levels including the coordinator,
not four nested levels of subagents.

Report filenames below are local receipts under
`/tmp/opencode/mazewall-recursive-review-2026-11-04/`.

| Node / parent | Actual session | Scope / report |
|---|---|---|
| A / coordinator | `ses_e5768eb79ffeyMEtfbSR3VjKyz` | Core synthesis; `A-summary.md` |
| A1 / A | `ses_e5768c41cffe44WTa0Eth5hCKy` | Platform, policy/BPF, Landlock; `A1-platform-policy.md` |
| A2 / A | `ses_e5768a638ffe1LGp8dVqlanXGM` | Owned execution/retirement; `A2-owned-retirement.md` |
| A2a / A2 | `ses_e5768016dffeXxO2OQyHCnEhT4` | Observable supervisor retirement; `A2a-supervisor-retirement-contract.md` |
| A3 / A | `ses_e57688be2ffejjDqn5MB7xNaGL` | Supervisor routing/lifetime; `A3-supervisor.md` |
| B / coordinator | `ses_e5768eb61ffenxhwFS6odV3v9h` | Profiling synthesis; `B-summary.md` |
| B1 / B | `ses_e5768ba4dffeRP7fcrNHtxuyR2` | Tier E/JVMTI and reuse; `B1-tier-e-jvmti.md` |
| B2 / B | `ses_e5768a012ffeBcrcO98wIjs59N` | HotSpot current source/artifacts; `B2-hotspot-current.md` |
| B3 / B | `ses_e576883feffed5vZ5KDlqiBcMi` | USER_NOTIF/evidence/auxiliary routes; `B3-user-notif-evidence.md` |
| C / coordinator | `ses_e5768eb2dffeIZ34fRu9V6h0S6` | Portal/consumer synthesis; `C-summary.md` |
| C1 / C | `ses_e5768bdb6ffenNwjLZW110mmQE` | Broker/worker ownership/signals; `C1-portal-ownership.md` |
| C1a / C1 | `ses_e5767f9a3ffefipO5kgkzyy2Gb` | Prepared result/end/fatal ownership; `C1a-portal-result-end.md` |
| C2 / C | `ses_e57689f94ffezzmwx7g9rvC4Eo` | Codegen/schema/codec/packaging; `C2-codegen-contracts.md` |
| C3 / C | `ses_e57688258ffei22aTRiDU3q6N7` | Spring, Koog, demos; `C3-consumers.md` |
| D / coordinator | `ses_e5768ea7dffe17Ec4B6pNKA55l` | Delivery synthesis; `D-summary.md` |
| D1 / D | `ses_e5768c33effe3KOi60edBH3DdR` | Build/CI/tests/benchmarks; `D1-build-assurance.md` |
| D2 / D | `ses_e5768a93affe6qTHo62CsFquq8` | Architecture/API/docs/tooling; `D2-architecture-tooling.md` |
| D3 / D | `ses_e57689011ffedO0sBBb95MJFWR` | Retained recovery authority, static only; `D3-recovery-authority.md` |

All eighteen completed. Zero depth/capacity rejection, failed delegation, or
uncompleted child is counted. Corrected file-lookup/report-validation mistakes
were not product defects or extra reviews. This is focused repository-wide
coverage, not a claim that every inventoried line received semantic inspection.

## Additional findings and exact ownership

**All six residuals are P2.** Existing owners retain their high priority; residual
severity and owner priority are different fields. Source confidence is high.
Evidence is connected-source review, supplemented by the bounded existing-host-artifact
probe below. Native consequences were not executed in this retry.

Source prefixes used here: `F/` = `profiler/src/main/kotlin/io/mazewall/profiler/`;
`R/` = `portals/runtime/src/main/kotlin/io/mazewall/portal/`;
`W/` = `portals/worker/src/main/kotlin/io/mazewall/portal/worker/`.

### PN-DEDUP

**Distinct access evidence is silently dropped.** Child ID: B3-NEW-01.
Owner: [retention910](../backlog/issue-20260926-121456-910-bound-user-notif-session-evidence-retention.md).

`F/internal/ProfilerTraceListener.kt:458–480,502–519` suppresses repeated syscall-name
and ordered-path keys within 500 ms. With raw benchmark capture inactive, same-worker
OPENAT read (`flags=0`) then write (`flags=1`) on the same resolved path loses the
write before queueing. Both frames still receive ACK (`:436–455`), without a loss
counter. `F/api/TraceEvent.kt:551–560` retains those distinct flags;
`F/compiler/BobCompiler.kt:84–110` needs them to produce write capability.
`F/api/ProfilerSession.kt:159–179` and `F/api/ProfilingCoverage.kt:340–361` can then
report complete coverage over read-only evidence when other completion conditions
hold. Later legitimate writes can be denied; this is not an overpermissive grant.

**Correction/check:** remove policy-relevant early suppression, or preserve every
compiler/coverage distinction while compacting optional stack samples. Keep bounded
retention and ACK progress. Encoded read→write/reverse, O_PATH→read, incomplete-flags,
duplicate and shared-cache controls must preserve capability evidence and truthful
coverage. Existing tests cover failed operands/path order, not this collision.
Source-confirmed, not runtime-reproduced. Existing cache/loss-accounting scope
justifies expanding retention910 rather than filing a competing issue.

### PRT-08

**Ordinary driver failure masks later fatal transport cleanup.** Child ID: C1-NEW-01.
Owner: [readiness816, M1](../backlog/issue-20261008-003135-816-bind-portal-readiness-to-actual-installation-recei.md).

`W/PortalWorkerDriver.kt:47–52` always suppresses close failure under an existing
primary. Receive IOException followed by transport-close Error therefore throws
the IOException. Outer receipt cleanup does not promote the suppressed Error.
The worker stops, but its terminal outcome loses fatal priority, contrary to the
shared fatal-first contract in the [Portal design](../designs/enforcer/process-portal-design.md#architecture).

**Correction/check:** use existing `CleanupFailures.merge`; retain exact fatal
identity, ordinary context, once-only close and no extra reply. Native-free driver
tests should cover ordinary receive/send + fatal close, inverse order, two fatals
and identical objects. Source-confirmed; test unrun. Extend M1's execution-finalization
owner, preserving resolved transport/reply-loop repairs and the already-correct
receipt fold. This is separate from PCG-10's declared-exception reconstruction.

### PRT-09

**Pre-dispatch cancellation can wait forever for an unpublished capture result.**
Child ID: C1-NEW-02. Owner: [readiness816, I3](../backlog/issue-20261008-003135-816-bind-portal-readiness-to-actual-installation-recei.md).

`R/PortalGenerationCaptures.kt:26–29` registers before final authority check/start.
Pause there; concurrent cancellation observes NEW (`:128–132`). Physical-end accepts
NEW (`:95`), so retirement removes the owner and joins its result (`:47–49`). Resume
startup: `R/PortalMeasurementBudget.kt:18–20` rejects cancellation before the sole
result producer starts (`PortalGenerationCaptures.kt:77–82`). The future has no
producer. `R/ProcessBroker.kt:417–432` can reach retirement after startup-join timeout;
close ordering does not exclude this schedule.

**Correction/check:** serialize never-started cancellation with start authority;
publish or recognize its terminal outcome before forgetting it. Never join an
unpublished result or forget a still-startable owner. Barrier after registration:
require zero action entry and bounded retirement; contrast actual-start/held-work
and unobserved-fatal controls. This is a source-confirmed unbounded-wait schedule,
not an observed JVM deadlock. Preserve actual source/cancellation thread joins.

### PRT-10

**Already observed broker fatal can disappear from both invocation stages.** Child
ID: C1a-01. Owner: [prepared888](../backlog/issue-20261007-211004-888-own-prepared-portal-invocation-cancellation-throug.md).

`R/PortalInvocationRetirement.kt:75–82` retains the first failure only. Mapping that
catches ordinary then fatal broker failures and returns loses the fatal. Separately,
`R/OwnedPortalInvocation.kt:79–104` samples retained failure only on successful mapping:
caught broker fatal followed by ordinary mapping throw loses it too. Production
retain calls are at `R/ProcessBroker.kt:314–345`; the failed slot still retires.

C1a's Java probe exercised **actual existing host-owner bytecode**, source-cross-checked
with `javap`, using completed pure retirement futures. Both bad schedules produced
ordinary result/end outcomes; the single-retained-fatal/successful-return control
preserved exact fatal identity. No fresh Kotlin build or real broker/native schedule
was run. Probe source: local `C1a-FatalProbe.java`; class hashes remain in its report.

**Correction/check:** aggregate retained failures fatal-first and merge them on
exceptional mapping exit too. Assert exact identity for both schedules, pending
retirement retaining admission, cancellation delivery with fatal end, and a real
stateless-broker fault control. Preserve separate delivery and physical end.

### PRT-11

**Invocation end excludes a failed received-capability retirement.** Child ID:
C1a-02. Owner: [prepared888](../backlog/issue-20261007-211004-888-own-prepared-portal-invocation-cancellation-throug.md),
coordinated with [P08](../backlog/issue-20261103-090026-755-make-granted-portal-file-inputs-convenient-and-own.md).

`R/PortalChannel.kt:119–123` transfers received descriptors to the broker. A pre-native
close failure leaves one exact capability in global retained custody
(`R/ProcessBroker.kt:495–506`), while the failed call retains only the slot future
(`:314–345`). Its completion predicate checks seven slot resources (`:830–859`),
excluding the transferred capability. Invocation end/admission can complete while
that capability still awaits broker cleanup.

This is a **source-confirmed completion-scope mismatch**, with intended supported
scope/physical qualification still to resolve against
`enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/InvocationHandle.kt:8–9`.
Broker custody remains; no orphan/leak or premature child-reap was established.
**Correction/check:** join the exact transferred owner to invocation retirement,
or explicitly specify and qualify a separately required owner. Use a genuinely
received FD, one pre-native close failure, successful slot retirement and held
capability retry; preserve terminal native-close behavior. Physical fixture unrun.

### PRT-12

**Successful cleanup retry erases an earlier fatal cleanup outcome.** Child ID:
C1a-03. Owner: [prepared888](../backlog/issue-20261007-211004-888-own-prepared-portal-invocation-cancellation-throug.md).

After ordinary RPC failure, maintenance cleanup Error leaves a slot future pending
and is logged (`R/ProcessBroker.kt:738–755,767–838`). Only resource flags survive.
Later successful close creates a fresh collector and completes Unit (`:834–835`),
so `R/PortalInvocationRetirement.kt:29–55` receives no earlier fatal. End remains
pending until physical retirement, but the eventual outcome loses fatal evidence.

**Correction/check:** retain cumulative fatal-first slot cleanup outcomes across
attempts and publish them when physical retirement finishes. One-shot pre-native
Error must keep end pending before retry, then remain the exact terminal Error with
ordinary context after successful retry. Preserve independent cleanup, once-only
disposal and terminal native-close semantics. Source-confirmed; asynchronous/native
schedule unrun. Coordinate with PRT-04's retry fixtures and PRT-02/CG05 broker edits.

## Prior findings: reconciliation and stronger boundaries

| Area | Retry disposition |
|---|---|
| PLAT-01–08; BPF-01/03–09 | Known-live. Preserve **PLAT-08 → BPF-03 → BPF-01**. Default-on self-verification runs after actual install, so it does not rescue BPF-01's no-install success path. Structural BPF/x32/payload fixes retain credit. |
| LL-01 | Conditional scope/documentation/ABI8 qualification debt under existing BPF-05/envelope owners; no new supported-path bypass. Preserve baseline-before-local-filesystem-domain constraints. |
| LIF-01–04 | Known-live. In LIF-01 the outer worker catch runs after the handle has been removed; it cannot repair the lost public result. LIF-04 remains a public contradictory-value case, not proof ordinary producers emit it. |
| LIF-Q1 | Unresolved. Manager liveness prevents Complete while rollback is blocked, but completed pre-registration rollback failure is not retained in domain-only drain snapshots. Define caller/runtime retry ownership and use genuine-resource barriers; no leak established. |
| LIF-Q2 | Unresolved, independently narrowed by A2a. Registration close and worker join do not join watcher/reactor cleanup. Internal lifetime ownership supplies no application-observable bounded join/outcome. Joining that watcher from the still-live worker would be circular. |
| SUP-01–06 | Known-live; actual composed accept/openat2 paths corroborated. Captured backend and denied-exec cleanup remain source-fixed. BUILD-RUN cause remains unresolved historical evidence. |
| PN05-01–06; EVID-01/03–09 | Known-live; PN-DEDUP adds the specific pre-aggregation loss case. Evidence consistency still precedes behavior binding. Recorded-log incompleteness and triage self-only correction remain credited. |
| PN-E2 / PN05-07 | Conditional bootstrap-directory pre-ACK stall/session-failure schedule; no new runtime reproduction, permanent-deadlock conclusion or historical752 causality. |
| TE-01–08 | Known-live at checked hashes; native/live359 work remains separately owned. Preserve cleanup405 before shutdown/terminal qualification. Diagnostic capture metadata is not enforcement authority. |
| HS-01–04; HS09-01–05 | Known-live. Protocol source changed only its pin: substituting the old pin reproduces its earlier hash. Manual overlay acceptance does not repair required CI selection. HS-QUAL advancement is detailed below. |
| PRT-01–07; CG01–07; PCG-08–11 | Known-live, with aliases preserved. Portable packaging, receipt rooting, physical cancellation joins and accepted-FD handoff corrections retain credit. CG02 erased-record generic rejection is rejected expressiveness, not silent corruption. |
| SPR-01–03; KD-E1/E2/E3; KD-01/02 | Existing owners retained. Spring shutdown needs runtime **and dependencies** beyond phase timeout; callback-only repair is insufficient. CLI/Outline and supported S2 corrections remain source-fixed. Demo-loop fatal questions remain qualification questions, not additional confirmed leaks. |
| BUILD-01/02/04; TEST-01–05 | Known-live check-selection/oracle/evidence defects. BUILD-04 omits **three test methods in two Kotlin classes**, not three classes. BUILD-03 = TEST-01; TEST-02 = BPF-07; TEST-06 = BPF-09. |
| ARCH/DOCS/TOOL | Existing owners/decisions retained; changed first-release target below. TOOL-03/04 remain conditional on retaining ingestion. Removed-controller authority is not restored by review. |
| RET-01/02/03 | Known-live in unchanged retained source. Context428 remains source-fixed/unqualified and administratively open; persistence424/148/565 remain resolved. Recovery stays paused. Owner priority is not residual severity. |

No first-pass finding was closed merely by static agreement or an open/closed issue
label. Existing exact owners and qualification limits remain in the original register.
ARCH-06 native maintenance and DOCS-05/07 comprehensive inventories were not freshly
re-executed by D; their dated acceptance/gap records were carried with that limit.

## Newer HotSpot implementation and owner evidence

[Definition-accounting owner042](../backlog/issue-20261005-193140-042-prove-selected-hotspot-event-conservation-and-all-.md)
has advanced beyond the first review's checkpoint. Immutable lifetime/original-root
readout, original event-instance authority, native-Join window and guarded sampling
are now implemented. The earlier fresh-sysfs-under-owner-lock problem is source-fixed:
`profiler/hotspot-agentless/patches/profiler-additions/tracer/definition_accounting.go.txt:69–133`
acquires/warm-checks CPU cardinality before guarded readout. The original-root/window
checks are at
`profiler/hotspot-agentless/patches/gadget-additions/pkg/operators/ebpf/definition_readout.go.txt:43–114`.
These are real source changes; older “not implemented” wording is historical.

Later owner receipts were read, **not executed by this review**, under
`/tmp/opencode/definition-accounting-poststart-final-44q0oojx/`:

| Receipt | Recorded outcome / bounded credit |
|---|---|
| `poststart-jvm-gate-1hw0lo6r/result.json` | `JVM_FOCUSED_FULL_BUILD_INSTALLED_CLASSES_PASS`; full build exit 0, 616.514 s; stdout says 233 tasks, 63 executed/170 up-to-date. Reports 94 focused tests and nine installed JARs/1,041 class comparisons. |
| `fresh-normal-observation-fjlikhj9/attempt-once/run-result.json` | PASS on its selected host/kernel, pinned JVM, asset and one 5,000 ms window; no held resources, native ownership clear, removed grants verified in owner record. |
| Same attempt's `observation/outcome.json` | 401 received, 397 resolved, four unavailable; finalization, physical drain, terminal accounting and retention complete; close succeeded/no pending ownership. **Coverage, collection-loss completeness and policy eligibility remain false.** |

The successful build command explicitly used
`MW_HOTSPOT_ASSET_TEST_DIR=/tmp/opencode/definition-accounting-poststart-final-44q0oojx/source/build/asset`
with `./gradlew build`. At **22:17:11Z**, coordinator checks matched all **six named
source hashes, both build-log hashes and nine installed-JAR hashes** in that receipt.
The class-by-class comparison and complete native launch/provenance journal were
not independently replayed. Six source hashes do not bind every current module to
that build; empty receipt test-summary arrays do not certify zero skipped tests.

Receipt SHA-256 values, in the table's order:

```text
82d3b0697b0484dc655db07e6ac7bf3c4f6bc4099ba564c13812c13493006a93
fcc19098383e2bdac481bf9f7ea794fe96ca1f00f80da0be92efd85cb0b58722
d68fb1b671f920e3df1ef65fa4628d7f941db59ba46929103ca13ca36f676acd
```

The owner journal reviewed by B2 records nine qualified matrix cases, N4 accepted,
N1 deliberately qualification-ineligible, and direct-drain BLOCKED. Separate cleanup
success does not replace the original missing Wait/qualification evidence. Diagnostic
matrix overlays are not interchangeable with normal `22f66…5035` artifacts. Exact
producer-failure/final-sampling-stall causality remains unproven. This credits newer
bounded native evidence without claiming the wider matrix, persisted product,
whole-collection completeness or general release readiness.

## Changed first-release target and next work

The [first process-lockdown design](../../designs/first-process-lockdown-release.md)
supersedes the earlier suggestion to promote the full existing platform/enforcer
artifact pair. It requires a genuinely smaller implementation dependency set with
**one shared installer/state/native owner**, preserved existing callers, independently
consumed packaged artifacts and an exact HotSpot JDK25/Linux x86_64 environment.
It promises denial of external-program execution after trusted startup, not denial
of every fork/clone or JVM thread creation.

The extraction manifest, public API, environment tuple, artifact-to-gate matrix and
publication/retrieval evidence remain open under existing owners560/359 and the
design's preparation tasks. Optional Portal/profiler defects do not automatically
block an independently qualified lockdown artifact. Shared-path defects require
actual reachability analysis; current repository gates remain in force.

Practical work order:

1. Preserve the shared correction order **PLAT-08 → BPF-03 → BPF-01**, LIF-01 result
   settlement, evidence consistency before binding, and cleanup405 before Tier E
   terminal/shutdown qualification. Coordinate these shared files with active owners.
2. **Lowest-overlap implementation candidates remain PRT-05, then PCG-10.** The
   retry's pure-JDK proxy probe supports `method.declaringClass == Object.class`
   even for redeclared Object signatures; actual generated-runtime regression is
   still required. PRT-08's driver fold is another narrow candidate.
3. PRT-09 and PRT-10 have bounded host-owner regression scopes. PRT-11/12 need
   genuine broker/capability cleanup fixtures and coordination with PRT-02/04/CG05.
   PN-DEDUP belongs with retention910 and active listener work.
4. BUILD-02 required-host selection, BUILD-04 exhaustive bridge and TEST-03 evidence
   consistency can proceed as separate assigned work. PIT touches an active profiler
   build file. Clean Git status alone is not an exclusive reservation; serialize
   Gradle runs in the shared checkout.

## Source identity and verification boundary

Anchor remains dirty HEAD **`b2c9b335720fd0491a8d86917a3b0593b818a4ec`**.
Retry baseline at **22:02:31.507274Z** recorded 4,375 tracked/nonignored file hashes;
relative to the first inventory, 64 changed and 65 new non-review paths were already
present. These are point-in-time inventories, not an immutable candidate.

Coordinator reconciliation at **22:18:10.257810Z** checked **1,398 distinct paths**
from reviewer receipts: zero conflicting hashes and no unexpected mismatch. The
only mismatch was this review's expanded retention910 document. This union includes
source, context, external/ignored receipts and existing class files; it is not a
count of deeply reviewed source files. At **22:18:11.398621Z**, the Git inventory
still had 4,375 files; only the three review-owned backlog documents differed from
retry start, with no new/missing nonignored paths. The 22,409 retained-baseline paths
had no changes/missing files, and the staged raw-diff identity was unchanged.
Subsequent authored report/index changes are documentation work.

Fresh retry execution was limited to source/hash/reference checks, A1's explicitly
translated arithmetic checks, the JDK proxy-predicate probe, and C1a's bounded
existing-host-artifact fatal probe. No reviewer ran Gradle, CMake, native tests,
kernel effects, recovery code, commits or publication. Neither ordinary source
agreement nor translated expressions are current-source/compiler/native acceptance.

Local receipts include `initial-manifest.json`, `retained-baseline.json`, all
eighteen reports, child manifests/probe sources, `coordinator-owner-receipt-check.json`,
`coordinator-integrated-source-recheck.json`, `coordinator-source-comparison.json`,
and `coordinator-final-source-reconciliation.json`.
They remain temporary local evidence, not version-controlled attachments; actionable
findings and ownership are retained here and in the three expanded issues.

The final source checkpoint at **22:28:11.804283Z** counted **4,376 files**: the eight
edited documents and this one new report were the only differences from retry start.
Since the 22:18 checkpoint, only the four original reports, backlog index and new
follow-up changed. The 1,398-path receipt union had only those expected report
changes; the retained 22,409-path baseline and staged raw-diff identity still matched.
This remains timestamped reconciliation, not a freeze on concurrent work.

## Final documentation validation

- **PASS:** `./scripts/adkw check-backlog`, reused for the same backlog revision.
  Inventory is still **325 active / 739 resolved**; the three expanded owners retain
  their exact identities, high priorities, open statuses and empty dependency lists.
  Every declared target file exists and all three owners remain indexed.
- **PASS:** **643 local Markdown links/anchors** and **190 explicit inline source
  line references** across the nine retry-authored documents. These are local
  reference/range checks; historical source conclusions retain their dated evidence.
- **PASS:** review-scoped `git diff --check`, full-file final-newline/trailing-whitespace
  checks including untracked reports, and the unchanged staged `git diff --cached --check`.
- **Whole-checkout `git diff --check`: exit 2**, with **514 diagnostics across eight
  existing HotSpot patch files**: 412 space-before-tab, 100 trailing-whitespace and
  two blank-EOF diagnostics. This later result supersedes the first review's single
  diagnostic as the retry's observed state. The patch work was preserved; these
  diagnostics do not establish that nested patches are semantically invalid.

The affected files are `experiments/hotspot-agentless/patches/gadget-auxiliary-ownership.patch`
and, under `profiler/hotspot-agentless/patches/`, `gadget-auxiliary-ownership.patch`,
`gadget-epoch.patch`, `gadget-finalization.patch`, `gadget-shutdown.patch`,
`profiler-auxiliary-ownership.patch`, `profiler-definition-drain.patch` and
`profiler-metadata-retirement.patch`.

Final local receipt: `coordinator-document-validation.json`, with checked document
hashes, reference inventory, command outcomes and source-checkpoint custody. All
review changes remain **uncommitted**. The failed first-pass build, later bounded
owner successes, outstanding native/contracts and concurrent-source limits remain
separate evidence states.
