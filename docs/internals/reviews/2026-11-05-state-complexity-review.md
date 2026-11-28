# Recursive state and complexity review — 2026-11-05

## Purpose and scope

What this repo does: mazewall restricts Linux JVM processes and owned workers with
Seccomp and Landlock, profiles their behavior, and brokers calls to separate JVMs.
The assumed load is concurrent invocations, cancellation, helper/process failure,
and bounded shutdown—not a single-threaded happy path.

This review targets safer state and decision design, not technology adoption for
its own sake. It reviews the current checkout, including existing uncommitted
work, against HEAD `b2c9b335720fd0491a8d86917a3b0593b818a4ec`. It does not implement
the recommended changes or qualify this checkout for release.

## Outcome

**20 completed reviewer sessions across six lanes; four levels including the
coordinator. Fourteen retained changes: five new issues and nine existing-owner
extensions.** Twelve concern concrete correctness/ownership gaps; two are explicitly
design-only. No implementation, issue closure or public API change was made.

**Assessment:** this project already uses substantial type-state, sealed outcomes,
pure reducers and resource-ownership machinery. The biggest remaining gains are
at boundaries between those models: startup readiness versus current health,
collector termination versus work completion, copied metadata versus reusable
identity, and several independently authored views of the same evidence/policy.
Another framework would mostly add a second source of truth.

Backlog inventory after reconciliation: **330 active (329 open, one deferred),
739 resolved**, up from 325 active. Existing priorities, statuses and dependencies
were preserved. The optional JDBC improvement is not a new release blocker;
retained-ingestion work remains conditional on the existing retirement decision.

## Decision rules

1. **Make illegal combinations unavailable.** Use a payload-bearing sealed variant
   when a phase requires particular data: `Running(worker)` is stronger than an
   enum plus a nullable worker. Use an enum when variants differ only by name.
2. **One authoritative state.** Derive diagnostics, persistence, and compatibility
   views from the owner rather than maintaining independently mutable copies.
3. **Functional core, explicit effects.** A pure decision function receives all
   relevant inputs and returns a decision/next state and named effects. Clocks,
   native calls, cancellation, filesystem access, and effect execution stay at an
   owned boundary. Do not build an effect framework for a few local decisions.
4. **Validate once, then carry evidence.** Decode external input into a validated,
   defensively owned domain value before compilation, authorization, or emission.
   Keep rejecting defaults for open wire/kernel inputs; do not confuse them with
   missing exhaustiveness over a closed internal type.
5. **Ownership is more than a type parameter.** Native resources need actual
   acquisition identity, synchronization, and physical retirement. Kotlin/JVM
   types do not provide linear ownership or protect against all mutable aliases.
6. **Test the transition model, then its interpreter.** Small exhaustive matrices
   and bounded event histories can check invariants without native I/O. Follow
   with deterministic barrier/fault tests for effects, cancellation, races, and
   exactly-once ownership. Native restrictions still require kernel evidence.
7. **Pay for complexity only once.** Prefer existing helpers and local types.
   Reuse existing backlog owners; add a ticket only for an independently scoped,
   unowned change. A design opportunity is not reported as an observed defect.

## Non-goals and compatibility

- No repository-wide state-machine DSL, event-sourcing migration, optics library,
  generic effect system, new dependency, or speculative value-class proliferation.
- No public API break without explicit approval. Internal authoritative models
  can retain compatible public projections and validate legacy constructors.
- No weakening of fail-closed behavior, JVM coordination allowances, captured
  native engines, Landlock-before-Seccomp ordering, or native ABI widths.
- Delivery, body completion, cleanup, and physical retirement remain distinct.
  A timeout or cancellation request never proves execution has stopped.
- Irreversible kernel progress remains recorded even when overall installation
  fails. A cleaner outcome type must not erase applied restrictions or owners.

### How to enforce improvements

The project already requires sealed/enum exhaustiveness, pure lifecycle decisions,
immutable evidence, narrow visibility, native isolation, and strict domain types
in [its quality contract](../../../.agents/CODE_QUALITY.md). The useful next step is
closing specific construction/ownership gaps, not adding another policy document.

Use compiler exhaustiveness and the existing Detekt rule for closed `when`
branches; do not add a file-list or lexical source scan. Use existing API/ABI
checks for compatibility. For behavior, put a small transition matrix or bounded
event-history property test next to the actual owner, then test its effect
interpreter. Typical properties are terminal-state stability, at-most-once reply,
no admission release before physical end, and disposal of late acquired resources.
These are acceptance patterns, not claims that all current owners satisfy them.

## Review method and initial evidence

Six independent lane leads were assigned two focused reviewers each, with one
optional further specialist per lane. The maximum allowed depth is four levels
including the coordinator. Every node must inspect active and resolved backlog
owners and supply exact proposed acceptance additions. Shared backlog edits are
serialized by the coordinator; review agents write only uniquely owned reports.

Initial frontmatter inventory: **325 active (324 open, one deferred), 739 resolved**.
`./scripts/adkw check-backlog` passed before changes. Memory tools were not
available in the current tool catalog; local design/review history was used.
Semantic discovery through jbcontext succeeded. Source inspection and backlog
validation are not compiler, concurrency, or kernel qualification.

### Lead assignments

| Lane | Actual lead session | Assigned scope |
|---|---|---|
| A | `ses_e530efaabffe05f4XDwZ1t1Ku8` | Platform/native domain types, policy/BPF, installation outcomes |
| B | `ses_e530efa8affeIUOOCIrlWvAMHn` | Owned execution, containment state, supervisor/helpers |
| C | `ses_e530efa2bffeglPffesrmGFIUV` | Profiler sessions/ACK, coverage/conversion, auxiliary routes |
| D | `ses_e530ef9f2ffeahqSL9xFy9NQsM` | HotSpot Kotlin/Go, Tier E/JVMTI/native, HotSpot bridge |
| E | `ses_e530ef9c0ffebL7ym7ywi6EqS0` | Portal broker/worker, generated schemas/codecs and transport |
| F | `ses_e530ef9b3ffe0A03CmcOt5vaY2` | Spring/Koog/consumers, build/CI/benchmarks, retained tooling |

All leads completed, using the configured `reviewer` role without a coordinator
model override. Completed descendants and local report receipts are recorded below;
assignments alone are not counted as completed reviews.

The Git index and non-review/backlog file contents were fingerprinted before
documentation edits for a preservation check at completion. No worktree,
application edit, commit, push, merge, or issue closure is authorized by this review.

Baseline preservation receipts:

- Non-review/backlog contents: 3,309 tracked/nonignored paths, including missing
  tracked files; SHA-256
  `368521dc617500fa8ad9931b023b3fd279344b579dd826c9c53c3844959ddf1c`.
- Git index SHA-256:
  `712c517e18418eb1c3f4bacd748ed90383b26765097ec437e52e32fb14b3fa77`.
- Initial `git diff --cached --check`: passed.
- Initial whole-checkout `git diff --check`: failed on existing trailing blank
  lines in `experiments/hotspot-agentless/patches/gadget-auxiliary-ownership.patch:191`
  and `profiler/hotspot-agentless/patches/gadget-auxiliary-ownership.patch:191`.
  Those unrelated patches are not changed by this review.

## Must fix — correctness and ownership boundaries

These are source-confirmed gaps/schedules, **not fresh runtime reproductions**.
Conditional native consequences are stated rather than assumed. Priority below
describes the finding; an existing broader owner's priority may be higher.

### 1. Keep profiler collection alive through accepted work — C-01, P1, NEW

- **What this is:** the public executor wrapper owns one profiling session.
- **Problem:** `profiler/src/main/kotlin/io/mazewall/profiler/api/Profiler.kt:139–142`
  closes it on admission shutdown; `ProfilerSession.kt:168–174` can then certify
  collector drain while accepted running/queued work still executes unobserved.
- **Fix:** one private `Accepting → Draining → WorkEnded → CollectorRetired` model;
  pure decisions choose effects. Keep shutdown nonblocking and early cutoff sticky-incomplete.
- **Check:** hold an installed task and queued task, request shutdown, then release
  distinct late file operations. Require their evidence and actual end before completeness.
- **If skipped:** ordinary shutdown→await→snapshot can omit valid tail behavior.
  Native permanent-filter/pass-through qualification is still required.

### 2. Make effective policy actions one authoritative value — A-01, P1, EXTEND

- **What this is:** planning, registry merge and self-verification describe installed restrictions.
- **Problem:** `enforcer/src/main/kotlin/io/mazewall/enforcer/state/ContainmentStateRegistry.kt:100–108`
  merges explicit entries without the opposite default; map/default/ALLOW-set views
  also drift in `ContainerState.kt:68–99` and the installation planner.
- **Fix:** private `EffectiveActions(default, exceptions)` with one pure intersection;
  derive ALLOW membership. A closed `AlreadySatisfied | Install(fragment,next)` binds decisions.
- **Check:** evolve actual planner/publication histories, including restrictive
  default versus opposite LOG/ALLOW exceptions, against independent compiled verdicts.
- **If skipped:** optimization/state projection can disagree with enforced stacking.
  Preserve argument rules, supported installation order and immutable snapshot semantics.

### 3. Assemble measured results from one producer-owned snapshot — C-02, P1, EXTEND

- **What this is:** a result couples observations, compiled behavior and coverage.
- **Problem:** `profiler/src/main/kotlin/io/mazewall/profiler/api/ProfilerSession.kt:157–179`
  rereads history across assembly; `Profiler.kt:99–100` exposes its mutable owners.
  Independent thread-safe traversals are not one coherent measured history.
- **Fix:** immutable `EvidenceSnapshot(events,stacks,loss,terminalFacts)` and pure
  assembly with an explicit environment value. Isolate or invalidate compatibility edits.
- **Check:** replace A with B through the injected environment seam between reads,
  and also before assembly; complete A observations must not authorize B grants.
- **If skipped:** a new aggregate can merely wrap mismatched or externally authored
  evidence. One `toList()` fixes coherence, not provenance.

### 4. Separate a stopped reader from failed scan evidence — D-01, P1, EXTEND

- **What this is:** the Tier E recovery reader uses the source's native map.
- **Problem:** `profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierERecoveryReader.kt:38–42`
  combines liveness/health in a Boolean; `TierEProfilerSession.kt:693–705,1098–1110`
  forgets the reader and releases resources even when it may still run.
- **Fix:** `Retired(scanHealth) | StillRunning(owner)`; retain map/queue/source
  custody until actual exit. Join and native I/O stay outside pure release decisions.
- **Check:** an interrupt-resistant held scan must permit bounded incomplete
  delivery, retain resources, then join/release exactly once after the barrier opens.
- **If skipped:** a resumed reader can use a closed/recycled map FD. Actual FD reuse
  was not reproduced; failed-but-dead scanning must not leak resources unnecessarily.

### 5. Recheck canonical installation health at body entry — B-02, P2, EXTEND

- **What this is:** an owned generation retains a permanent supervised installation.
- **Problem:** `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedInvocation.kt:137–148`
  enters later bodies without health authorization; failure remains only in
  `internal/supervisor/SupervisorInstallationLifetime.kt:24–31`.
- **Fix:** feed the authoritative current-worker `Usable | Failed(cause)` observation
  into existing generation failure/admission, including inline entry. Do not reinstall.
- **Check:** publish channel failure while body 1 is held and body 2 queued; body 2
  must never enter. Test cancellation/accounting and retained incomplete retirement.
- **If skipped:** READY can outlive known failed supervision. Kernel filters remain;
  no syscall bypass is claimed, nor survival after a correctly linearized health check.

### 6. Do not hold the deadline-observation mutex through readout effects — D-02, P2, EXTEND

- **What this is:** the native definition owner authorizes final original-map readout.
- **Problem:** `profiler/hotspot-agentless/patches/gadget-additions/pkg/operators/ebpf/definition_readout.go.txt:43–65`
  holds the same mutex needed by stop's expiry result and snapshots across `action()`.
- **Fix:** short locked admission/publication around an unlocked effect, with an
  explicit in-flight original-root/map lease. Simply unlocking would lose lifetime protection.
- **Check:** hold the inner admitted action after native Join; stop and snapshot
  must return incomplete before release, with no retirement or late successful promotion.
- **If skipped:** bounded reporting depends on effect completion. This is not an
  established explanation for historical blocked native runs or ordinary map latency.

### 7. Require a validated original occurrence before strict terminal admission — D-03, P2, EXTEND

- **What this is:** Kotlin pairs original events with terminal stack evidence.
- **Problem:** `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotEvidenceAssembler.kt:110–145`
  enforces original-first only for observed3; strict2 also omits complete CPU/sequence-ID validation.
- **Fix:** immutable `OccurrenceBinding` plus `AwaitingTerminal(binding) | Paired(binding)`;
  preserve validity scalars independently of retained payloads. Keep schema1 replay separate.
- **Check:** strict interpreter/lifecycle negatives for terminal-first and single-field
  identity mutations at normal/zero retention; preserve CPU0, epoch0 and valid pre-ready traffic.
- **If skipped:** contradictory strict evidence can look correlated. Wire consistency
  remains distinct from native metadata lifetime; collection still cannot authorize policy.

### 8. Require lifetime evidence before reusable native-cache admission — D-04, P2, NEW

- **What this is:** the native profiler caches raw method identities across captures.
- **Problem:** `profiler-native/src/agent.cpp:694–711,1185–1201,1219–1230` can retain a
  canonical raw stack after metadata-budget refusal deleted a required class guard.
- **Fix:** distinguish `CopiedOnly(frame)` from `Cacheable(frame,ownedGuard)`, or
  retain the guard. Canonical insertion needs complete class-lifetime coverage and RAII cleanup.
- **Check:** selectively refuse metadata retention while permitting canonical
  retention; require full coverage or no insertion, including mixed-frame/cache-hit cases.
- **If skipped:** missing guards can permit stale identity reuse after unloading.
  Actual JVM ID reuse/wrong labels remain unproved; exhaustion already makes the run incomplete.

## Should fix — bounded construction, parsing and acquisition gaps

### 9. Do not mint descriptor ownership from arbitrary BPF success — A-02, P2, NEW

- **What this is:** an internal helper adopts descriptor-producing BPF results.
- **Problem:** `platform/src/main/kotlin/io/mazewall/platform/native/NativeBpf.kt:24–32`
  also accepts status-returning commands; successful lookup status zero can become owned FD zero.
- **Fix:** reject non-FD commands before dispatch with the existing pure classifier;
  mint acquisition evidence once at the native adapter, not again in the consumer.
- **Check:** host spy asserts rejection/zero dispatch/no generation change, plus
  MAP_CREATE/errno/consume-once controls. Never close real stdin in the regression.
- **If skipped:** internal misuse can close an unacquired resource. The sole current
  production caller correctly uses MAP_CREATE; no valid-caller failure was demonstrated.

### 10. Reject whole malformed eBPF records before publishing paths — C-03, P2, NEW

- **What this is:** recorded auxiliary logs become diagnostic observations/BoB paths.
- **Problem:** `profiler/src/main/kotlin/io/mazewall/profiler/collector/EbpfEventParser.kt:70–100`
  accepts unclosed quoted prefixes and silently retains fields before malformed trailing input.
- **Fix:** private pure `Parsed(fields) | Malformed` or nullable rejection; validate
  the entire record before observation construction, reusing current loss accounting.
- **Check:** valid-plus-malformed WRITE yields one observation/one drop and no
  prefix path; preserve valid spaces/escapes/comments and conversion refusal.
- **If skipped:** diagnostic paths can be fabricated from truncated text. Public
  recorded-log collection independently stays incomplete; no policy bypass is established.

### 11. Admit one complete ingestion snapshot before planning effects — F-02, P2, EXTEND

- **What this is:** a retained optional script synchronizes backlog identities/dependencies.
- **Problem:** `scripts/paperclip_backlog_sync.kts:81–90,140–143,449–476` drops parse
  failures as absence and separately misses quoted UUID bindings. A static corpus check found 12 misses.
- **Fix:** `Accepted(snapshot) | Rejected(diagnostics)`, frontmatter-only `Unbound | Bound(uuid)`,
  and pure dependency planning distinguishing active/resolved/invalid evidence.
- **Check:** quoted/unquoted/body-only bindings and malformed/unreadable/absent
  blockers; rejected admission must produce zero HTTP/writeback.
- **If skipped:** duplicate creates or missing blockers remain possible, not live-board
  reproduced. **Retire the route first if intended; do not revive deleted orchestration.**

### 12. Register each output pump immediately after acquisition — F-03, P2, EXTEND

- **What this is:** the shared consumer harness owns asynchronous evidence readers.
- **Problem:** `src/consumerVerification/kotlin/io/mazewall/verification/ConsumerProcessEvidence.kt:90–93,121–124`
  assigns both handles only after both startups succeed; second-start failure loses the first handle.
- **Fix:** append-on-acquire ledger using the existing seam and bounded cleanup.
  **No state machine is needed** for this ownership invariant.
- **Check:** first real pump held/second startup throws; cleanup must observe the
  first before channel close, retaining primary/suppressed failures and interruption.
- **If skipped:** evidence reads/closure can race live unowned work. Child cleanup
  remains owned; no orphaned process or false successful command was proven.

## Nice to have — genuine design opportunities, no new bug established

### 13. Put production notification payloads in their phase — B-01, P3, NEW

- **What this is:** production supervisor work polls snapshot/verdict/result/exec/reply stages.
- **Problem:** `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/TraceeAuthorityNotificationWork.kt:28–34,66–100`
  distributes phase across nullable fields and relies on reactor ordering. No production repoll defect was established.
- **Fix:** replace those fields with private payload-bearing phases, including
  `ReplyOffered(handoff)` and `Closed`; keep I/O/disposal outside local transition choice.
- **Check:** bounded real-interpreter traces across every phase, close/late results,
  fast/AskJvm routes and reactor deadline/lease; at-most-once offer, exactly-once disposition.
- **If skipped:** future edits must preserve implicit branch/payload correlations.
  Do not replace the already-correct sole reactor reply owner or helper-reap accounting.

### 14. Centralize JDBC effect precedence as one private transition — F-01, P3, EXTEND, OPTIONAL

- **What this is:** the Spring adapter records transaction/savepoint effects.
- **Problem:** `integrations/spring/mazewall-spring-boot/src/main/java/io/mazewall/spring/SpringTransactionEffects.java:105–125`
  shares outcome/intent/completion rules with `SpringTransactionGuard.java:169–193`.
  Reviewed callers correctly enforce them; this is not a new rollback defect.
- **Fix:** private payload-bearing progress and pure `advance(progress,evidence)`,
  preserving pending versus completed UNKNOWN and confirmed rollback precedence.
- **Check:** a small pure table plus existing real same-datasource/fault-wrapped H2
  tests; intent never proves rollback and independent REQUIRES_NEW remains independent.
- **If skipped:** future callers must remember the distributed precedence rules.
  Do this while touching S2, not ahead of its concrete fatal-identity repair.

## Exact backlog changes

All owners below are under `docs/internals/backlog/`. New IDs were generated by
`./scripts/adkw new-issue`; existing text/evidence was preserved with additive
sections and focused scope metadata. No resolved issue was reopened.

| Finding | Action | Canonical owner |
|---|---|---|
| 1 / C-01 | NEW, high | [Executor collection lifetime](../backlog/resolved/issue-20261105-182827-544-retain-profiler-collection-until-accepted-executor.md) |
| 2 / A-01 | EXTEND | [Effective installed actions](../backlog/resolved/issue-20261103-235912-106-install-effective-allow-list-narrowing-and-restric.md) |
| 3 / C-02 | EXTEND | [Measured behavior binding](../backlog/issue-20261028-232811-578-bind-measured-profile-coverage-to-its-observed-beh.md) |
| 4 / D-01 | EXTEND | [Tier E resource ownership](../backlog/issue-20260926-121446-405-preserve-tier-e-resource-ownership-until-cleanup-c.md) |
| 5 / B-02 | EXTEND | [Owned worker generations](../backlog/issue-20261007-092325-883-own-bounded-platform-worker-generations-and-physic.md) |
| 6 / D-02 | EXTEND | [Definition-source accounting](../backlog/issue-20261005-193140-042-prove-selected-hotspot-event-conservation-and-all-.md) |
| 7 / D-03 | EXTEND | [Strict terminal identity](../backlog/issue-20261103-235250-806-bind-strict-hotspot-terminal-evidence-to-negotiate.md) |
| 8 / D-04 | NEW, medium | [Native cache lifetime coverage](../backlog/issue-20261105-183230-840-require-complete-class-lifetime-coverage-before-na.md) |
| 9 / A-02 | Resolved, medium | [BPF command/descriptor ownership](../backlog/resolved/issue-20261105-182737-637-reject-non-descriptor-bpf-commands-before-creating.md) |
| 10 / C-03 | NEW, medium | [Malformed recorded eBPF](../backlog/resolved/issue-20261105-182827-530-reject-unterminated-quoted-values-in-recorded-ebpf.md) |
| 11 / F-02 | EXTEND, conditional | [Retained ingestion identities](../backlog/issue-20261103-235259-662-preserve-canonical-issue-identities-in-any-retaine.md) |
| 12 / F-03 | EXTEND | [Consumer evidence drain](../backlog/resolved/issue-20261020-083727-843-drain-consumer-command-evidence-before-normal-proc.md) |
| 13 / B-01 | NEW, low, design-only | [Production notification phase payloads](../backlog/issue-20261105-182947-263-encode-production-supervisor-authority-work-phase-.md) |
| 14 / F-01 | EXTEND, optional design-only | [Spring S2 effects](../backlog/issue-20261008-033416-558-bind-approved-synchronous-spring-singleton-operati.md) |

## High-value opportunities already precisely owned

These are current review conclusions, **not additional discoveries or ticket
requests**. Their owners already contain the needed design and regression criteria,
so another restatement was deliberately not appended.

| Surface | Technique worth implementing/preserving | Existing owner |
|---|---|---|
| Policy raw construction/copy | Owned immutable collections, stable equality/cache keys | [Policy snapshots](../backlog/resolved/issue-20261028-232811-132-freeze-policy-definition-collections-at-every-cons.md) |
| Process scope/filesystem intent | Validated construction, not a caller-selected phantom marker | [Validated process scope](../backlog/resolved/issue-20261028-232810-687-prevent-raw-policy-definitions-from-asserting-inva.md) |
| Installation result | Failure-first rejection; controlled outcome independent of irreversible progress | [Receipt outcomes](../backlog/resolved/issue-20261028-232811-964-reject-installation-receipts-containing-both-succe.md) |
| In-process and Portal invocation | `Running(worker)` payload; keep lock and result/physical-end separation | [Phase-owned worker](../backlog/resolved/issue-20261103-083030-086-bind-invocation-worker-identity-to-its-lifecycle-p.md) |
| Broker close preparation | `AlreadyClosed | Wait(operation) | Execute(operation,…)` | [Exclusive close outcomes](../backlog/resolved/issue-20261103-083030-123-represent-broker-close-preparation-as-exclusive-ou.md) |
| Portal codegen | One position-safe immutable schema drives declarations, codecs and fingerprint | [CG02 schema](../backlog/resolved/issue-20261103-095306-294-validate-complete-portal-dto-shapes-before-source-.md), [CG03 identity](../backlog/issue-20261103-095306-992-fingerprint-the-complete-versioned-portal-wire-sch.md) |
| Generated wire/reply validation | Parse/finish validation before invocation or checkout release | [CG04 parsing](../backlog/resolved/issue-20261103-095307-699-reject-noncanonical-and-incompletely-consumed-gene.md), [CG05 checkout](../backlog/resolved/issue-20261103-095308-453-keep-portal-worker-checkout-owned-through-generate.md) |
| Replacement worker custody | Registered provisional owner until published or physically retired | [Replacement ownership](../backlog/issue-20261103-235253-262-retain-replacement-worker-ownership-across-broker-.md) |
| Profiler ACK | `AwaitingAck(id,afterAck)` defers pass-through across socket reads | [ACK ordering](../backlog/resolved/issue-20261103-235247-255-keep-published-profiler-events-awaiting-ack-during.md) |
| Listener readiness | Typed one-shot `Ready | Failed(cause)` before waiter wakeup | [Startup outcome](../backlog/resolved/issue-20261103-235247-666-propagate-profiler-listener-handshake-failure-befo.md) |
| Spring shutdown | Framework callback does not replace physical runtime/dependency retention | [Framework retirement](../backlog/issue-20261103-235255-089-tie-spring-lifecycle-completion-to-physical-retire.md) |

Keep the existing successful exemplars: concrete BPF builder stages and immutable
verified programs; closed BPF instructions; shared UNIX listen reducers; consume-once
descriptor receipts and generation identities; segment lease ownership; exhaustive
Portal reply transitions; separate delivery/end stages; bridge deep copies and
required-field admission. Several issues remain open for qualification although
their implementation subparts are present. Open status alone is not a missing-feature test.

Keep `ContainerState` a snapshot: the [resolved decision](../backlog/resolved/issue-20260821-150006-container-state-is-snapshot-not-lifecycle.md)
explicitly rejects duplicating installation lifecycles there. Likewise, independent
resource-release flags or join facts are not automatically an invalid product state.

## Execution order and advanced techniques worth using

1. Prioritize truthful policy/evidence and native lifetime boundaries (1–4), then
   installation health, readout boundedness and identity admission (5–8). Existing
   corrective work such as action ordering and Portal CG prerequisites still applies.
2. Use **bounded model-based testing** locally: generate short event histories
   through actual reducers/interpreters and assert ownership/terminal invariants.
   Include transition effects, not only next-state names; do not test an unrelated
   reference model without connecting it to production decisions.
3. Use **refined values** at trust boundaries: a validated occurrence, accepted
   schema, effective-action snapshot or guard-complete cache receipt carries the
   invariant subsequent code needs. Raw FFI/wire values remain at their boundaries.
4. Use **capability/epoch ownership** where identity can be reused. Pair the type
   with consume-once/runtime identity and physical retirement; JVM phantom types
   alone cannot prevent alias reuse or establish native exclusion.
5. Keep **effect interpretation** local. JNI/JDBC/IPC/native calls, clocks, joins and
   callback execution are not pure merely because a reducer returns a closure.
   Pure decisions should consume observed facts, then effects must retain owners
   exception-safely across asynchronous completion.
6. Do low-priority phase/JDBC refactors (13–14) with their existing behavioral
   controls. Skip generalized effect libraries, event sourcing, actor rewrites,
   formal-tool adoption and speculative public wrappers without a demonstrated need.

This is an execution recommendation, not authorization to implement, activate
tooling, run paid/native qualification or publish anything.

## Completed recursive review receipts

Twenty actual executions: **6 leads + 12 focused children + 2 specialists**.
The coordinator read all twenty reports and checked decisive source/owner evidence
for the accepted changes. Same-session follow-ups are not additional executions.

Local receipts are under `/tmp/opencode/mazewall-state-review-2026-11-05/`; the
durable finding/acceptance record is this report and the linked canonical issues.
Temporary report paths are not portable repository links.

| Node / parent | Actual session | Local report |
|---|---|---|
| A / coordinator | `ses_e530efaabffe05f4XDwZ1t1Ku8` | `A-platform-policy.md` |
| A1 / A | `ses_e530eaffeffeFYEhSs99qxEHU2` | `A1-platform-native.md` |
| A2 / A | `ses_e530eafe2ffeAVeey7w7FfgRN2` | `A2-policy-bpf.md` |
| B / coordinator | `ses_e530efa8affeIUOOCIrlWvAMHn` | `B-execution-supervisor.md` |
| B1 / B | `ses_e530ea13affepbQyoNMvKimqCM` | `B1-owned-execution.md` |
| B2 / B | `ses_e530ea0a7ffe2q6FLL0aA0mn9k` | `B2-supervisor.md` |
| C / coordinator | `ses_e530efa2bffeglPffesrmGFIUV` | `C-profiler-sessions-evidence.md` |
| C1 / C | `ses_e530e9d97ffeUwxl0qioJZmIM3` | `C1-profiler-lifecycles.md` |
| C2 / C | `ses_e530e9d48ffe0Zj5RasQnFj2sU` | `C2-profiler-evidence.md` |
| D / coordinator | `ses_e530ef9f2ffeahqSL9xFy9NQsM` | `D-hotspot-native.md` |
| D1 / D | `ses_e530eab35ffev5a6bDF9DC4bnJ` | `D1-kotlin-go.md` |
| D1a / D1 | `ses_e530e14d8ffedrrcnv7n04QR40` | `D1a-go.md` |
| D2 / D | `ses_e530eab2cffe7ZMFNa61AnmOeR` | `D2-native-tiere.md` |
| D2a / D2 | `ses_e530d1adeffec7gp7xXr4qqOUB` | `D2a-bridge.md` |
| E / coordinator | `ses_e530ef9c0ffebL7ym7ywi6EqS0` | `E-portals.md` |
| E1 / E | `ses_e530eb6e6ffe3yl0FockhA63pg` | `E1-runtime-worker.md` |
| E2 / E | `ses_e530eb689ffesPJaoxN3en8qLL` | `E2-codegen-wire.md` |
| F / coordinator | `ses_e530ef9b3ffe0A03CmcOt5vaY2` | `F-adapters-delivery.md` |
| F1 / F | `ses_e530eb526ffeRm4MMQ7ZeOEjdH` | `F1-integrations.md` |
| F2 / F | `ses_e530eb51bffeDGhrMqbJO8mnyk` | `F2-delivery-tooling.md` |

Both deepest chains have four levels including the coordinator. Lane D used two
specialists rather than its allocated one; that internal allocation error is
recorded, not hidden. Their scopes were disjoint and neither delegated further.
The user's four-level limit was respected. No failed start or unfinished reviewer
is counted as coverage.

## Coverage and limits

| Lane | Connected surfaces actually reviewed | Not established |
|---|---|---|
| A | Native FD/memory/result/command boundaries; policy construction/builders/cache; BPF builder/program/verifier/packing; action planning/publication/merge and tests | Every syscall/layout/downcall; executed compiler/kernel verdicts |
| B | Owned runtime/domain/generation/invocation/context/drain; supervisor routes/helpers/reply leases/permanent installation health and tests | Every prepared-resource path; real failure/channel/reaping schedules |
| C | Session/service/executor/listener/daemon/ACK; coverage/BoB/conversion; auxiliary eBPF/strace parsing and connected caller/tests | Executed tail-evidence proof; deep strace process-tree re-review |
| D | Kotlin HotSpot protocol/lifecycle/evidence; canonical Go capture/authored lifetime patches; native JVMTI/cache/Tier E owners; all 14 bridge source and 13 test files | Whole prepared upstream/distribution graph; actual FD/method-ID reuse; new native qualification |
| E | Broker startup/checkout/replacement/close/retirement; prepared operations/capabilities; worker reply loop; schema/codecs/manifests and tests | All artifact launch/materialization paths; executed generated/native consumers |
| F | Spring lifecycle/JDBC and Koog adapters; meaningful demos/composed consumers; selected buildSrc/CI/test-health/benchmark/tooling; shared consumer acquisition | Every binder/Security/origin implementation, workflow or consumer; live model/board/hosted execution |

Every node searched active **and resolved** backlog for its actual candidates and
read the relevant owners; nobody claims to have semantically reviewed every line
or every one of the 1,000-plus issue files. Experimental mirrors were not counted
as independent implementations. Deleted orchestrator code was not restored or
reviewed as live implementation.

No application tests, build, native/kernel probes, control-plane writes or release
qualification ran. Static UUID matching and fixture-content inspections are the
only small reproduced data checks, not production-runtime reproductions. The
Spring documentation lookup retrieved Framework **7.0.5**, not the checkout's
recorded **7.0.9**; it is supporting context, not version-matched runtime evidence.

**Verdict:** close the concrete truth/ownership gaps first; use small closed models
and pure decisions at their boundaries. Do not turn the whole project into a new
state-machine framework.

## Documentation/backlog verification

- `./scripts/adkw check-backlog`: **passed** before and after changes.
- `git diff --check -- docs/internals/backlog/ docs/internals/reviews/`: **passed**.
- Focused read-only document validation: **16 files, 548 local link targets,
  104 declared source/test targets, zero missing**; all sixteen files passed
  trailing-whitespace/final-newline checks, including the untracked new files.
- Frontmatter recount: **329 open + one deferred = 330 active; 739 resolved**.
  Report checks confirmed fourteen numbered changes, fourteen distinct owners,
  twenty unique session receipts and all twenty local reports present.
- The 3,309-path non-review/backlog content fingerprint is **unchanged**, matching
  the baseline SHA-256 above. Application, tests, build/config and unrelated
  documentation contents were preserved, including existing deletions.
- The raw Git-index byte checksum changed during the review. Read-only status/diff
  operations can refresh index metadata, so that byte checksum is **not used as
  proof of staged-content preservation**. No staging operation ran; direct
  `git show :path` checks confirm no November 5 review additions in the nine
  existing owners' staged versions, and the five new issues/report are unstaged.
  The index was not reset or rewritten to force a checksum match.
- Whole-checkout whitespace checking has the two **pre-existing** native-patch
  trailing-blank-line failures recorded above; unrelated files were not repaired.

Not checked: compilation, application tests, builds, actual race schedules,
kernel/native execution or hosted/published qualification. These documentation
checks do not prove the proposed fixes; each owner records its implementation
regression and completion gates. No source implementation or issue closure follows.
