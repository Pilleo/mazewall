# Repository review — backlog ownership and dependencies

2026-11-04. Read with the [main review and W01–W20 packages](2026-11-04-repository-review.md),
[finding register](2026-11-04-repository-review-findings.md) and
[verification/provenance](2026-11-04-repository-review-evidence.md).
This register records scoped review follow-ups, not implementation, qualification,
closure or operational authorization. The linked issues retain trigger/evidence,
smallest correction and required regression/qualification detail.

**Recursive follow-up:** [PN-DEDUP](2026-11-04-repository-review-recursive.md#pn-dedup)
extends [retention910](../backlog/issue-20260926-121456-910-bound-user-notif-session-evidence-retention.md);
[PRT-08/09](2026-11-04-repository-review-recursive.md#prt-08) extend
[readiness816](../backlog/issue-20261008-003135-816-bind-portal-readiness-to-actual-installation-recei.md);
[PRT-10/11/12](2026-11-04-repository-review-recursive.md#prt-10) extend
[prepared888](../backlog/issue-20261007-211004-888-own-prepared-portal-invocation-cancellation-throug.md).
All six residuals are P2; existing high-priority owners retain their priorities.
No new issue/dependency/closure was added by the retry. The first-pass counts below
remain historical accounting, with the same active/resolved inventory.

## Inventory and priority decisions

Frontmatter inventory, including nested active folders: **325 active (324 open,
1 deferred), 739 resolved**. The initial inventory was 266 active/739 resolved;
59 new issues account for the increase. No issue was archived or closed here.
All 51 distinct finding owners below received review addenda. Three additional
existing roadmap issues (P03/P09/P13) received acceptance cross-links: **54 existing
issues amended**, without creating competing implementation ownership.
P1/P2/P3 correspond to high/medium/low; artifact-specific impact, not issue count,
determines execution order. Existing-owner priorities below are their current
frontmatter values, not a priority sweep or an endorsement of every old ranking.

- **PN05-01: P2/medium**, consistent with both Astra reviews: fail-closed
  availability/correctness after RECV ENOENT, not a containment bypass.
- **PRT-05/PCG-11: P1/high**, consolidating the runtime lane's narrower P2 view
  with the generated-contract blocker: accepted legal operations never dispatch.
- **TEST-01/BUILD-03: P2/medium**, a check-selection gap with an omitted mutation
  denominator, not a demonstrated runtime bypass; repair alongside product fixes.
- **PN-E2/PN05-07: P2/medium**, conditional source-confirmed pre-ACK stall/session
  failure. No permanent hang or bootstrap-directory runtime reproduction is claimed.
  Independent reconciliation is `/tmp/opencode/mazewall-review-2026-11-04/21-profiler-liveness-escalation.md:373–485`.
- **SPR-02** requires retained application ownership of both runtime **and
  application dependencies** beyond Spring's phase timeout. Withholding the
  callback alone does not prevent framework timeout destruction.
- Empty `target_modules: []` for standalone Go/native/tooling or root build
  targets is intentional where the supplied metadata has no owning Gradle module;
  exact target files remain the scope. No fictitious Gradle project was introduced.

## New owners — 59 issues

Each owner link resolves to its exact path under `docs/internals/backlog/`.

| Finding | Priority | Package | Exact owner |
|---|---|---|---|
| PLAT-01 | P1 | W01 | [issue-20261103-234851-768](../backlog/issue-20261103-234851-768-correct-aarch64-renameat-metadata-before-policy-co.md) |
| PLAT-02 | P2 | W01 | [issue-20261103-235243-934](../backlog/issue-20261103-235243-934-use-consistent-wrap-safe-monotonic-deadline-arithm.md) |
| PLAT-03 | P1 | W01 | [issue-20261103-235244-414](../backlog/issue-20261103-235244-414-preserve-descriptor-generations-through-typed-scm-.md) |
| PLAT-04 | P2 | W01 | [issue-20261103-235244-769](../backlog/resolved/issue-20261103-235244-769-preserve-descriptor-close-errors-and-pre-dispatch-.md) |
| PLAT-05 | P2 | W01 | [issue-20261103-235245-104](../backlog/resolved/issue-20261103-235245-104-validate-managed-prctl-pointer-arguments-before-na.md) |
| PLAT-06 | P2 | W01 | [issue-20261103-235245-538](../backlog/issue-20261103-235245-538-roll-back-eventfd-ownership-when-notification-sess.md) |
| PLAT-07 | P2 | W01 | [issue-20261103-235246-003](../backlog/issue-20261103-235246-003-serialize-eventfd-completion-signaling-with-native.md) |
| PN05-01 | P2 | W05 | [issue-20261103-235246-361](../backlog/issue-20261103-235246-361-repoll-canceled-seccomp-receives-without-retiring-.md) |
| PN05-02 | P1 | W05 | [issue-20261103-235246-772](../backlog/issue-20261103-235246-772-route-every-profiler-executor-admission-through-co.md) |
| PN05-04 | P2 | W05 | [issue-20261103-235247-255](../backlog/issue-20261103-235247-255-keep-published-profiler-events-awaiting-ack-during.md) |
| PN05-06 | P2 | W05 | [issue-20261103-235247-666](../backlog/issue-20261103-235247-666-propagate-profiler-listener-handshake-failure-befo.md) |
| TE-01 | P1 | W08 | [issue-20261103-235248-095](../backlog/issue-20261103-235248-095-preserve-pending-application-exceptions-during-nat.md) |
| TE-02 | P1 | W08 | [issue-20261103-235248-477](../backlog/issue-20261103-235248-477-contain-allocation-failure-inside-native-profiler-.md) |
| TE-03 | P1 | W08 | [issue-20261103-235248-781](../backlog/issue-20261103-235248-781-keep-symbolization-closed-until-the-owning-replace.md) |
| TE-04 | P2 | W08 | [issue-20261103-235249-167](../backlog/issue-20261103-235249-167-publish-native-invocation-watermarks-independently.md) |
| HS-01 | P1 | W10 | [issue-20261103-235249-509](../backlog/issue-20261103-235249-509-bound-total-hotspot-line-table-decoding-and-refill.md) |
| HS-03 | P2 | W12 | [issue-20261103-235249-904](../backlog/issue-20261103-235249-904-bind-packaged-hotspot-executables-to-their-actual-.md) |
| HS-04 | P2 | W12 | [issue-20261103-235250-240](../backlog/issue-20261103-235250-240-verify-materialized-hotspot-dependency-sources-bef.md) |
| HS09-01 | P1 | W10 | [issue-20261103-235250-506](../backlog/issue-20261103-235250-506-accept-contract-permitted-pre-ready-hotspot-events.md) |
| HS09-02 | P2 | W10 | [issue-20261103-235250-806](../backlog/issue-20261103-235250-806-bind-strict-hotspot-terminal-evidence-to-negotiate.md) |
| HS09-03 | P2 | W10 | [issue-20261103-235251-281](../backlog/issue-20261103-235251-281-validate-strict-hotspot-finalization-and-selected-.md) |
| HS09-04 | P2 | W10 | [issue-20261103-235251-822](../backlog/issue-20261103-235251-822-keep-autonomous-hotspot-deadline-enforcement-activ.md) |
| HS09-05 | P2 | W10 | [issue-20261103-235252-265](../backlog/issue-20261103-235252-265-reject-duplicate-decoded-keys-at-the-hotspot-kotli.md) |
| PRT-01 | P1 | W06 | [issue-20261103-235252-762](../backlog/issue-20261103-235252-762-isolate-cross-process-signals-in-the-required-port.md) |
| PRT-02 | P1 | W06 | [issue-20261103-235253-262](../backlog/issue-20261103-235253-262-retain-replacement-worker-ownership-across-broker-.md) |
| PRT-03 | P2 | W06 | [issue-20261103-235253-722](../backlog/resolved/issue-20261103-235253-722-reject-fifo-portal-grants-without-blocking-before-.md) |
| PRT-05 | P1 | W07 | [issue-20261103-235254-202](../backlog/resolved/issue-20261103-235254-202-dispatch-legal-object-name-portal-service-overload.md) |
| PCG-10 | P2 | W07 | [issue-20261103-235254-619](../backlog/resolved/issue-20261103-235254-619-preserve-fatal-failures-during-portal-declared-exc.md) |
| SPR-02 | P2 | W13 | [issue-20261103-235255-089](../backlog/issue-20261103-235255-089-tie-spring-lifecycle-completion-to-physical-retire.md) |
| BUILD-01 | P1 | W12 | [issue-20261103-235255-561](../backlog/issue-20261103-235255-561-complete-kotlin-upgrade-locks-and-independent-opti.md) |
| BUILD-02 | P2 | W11 | [issue-20261103-235256-009](../backlog/resolved/issue-20261103-235256-009-select-existing-api-abi-and-package-topology-check.md) |
| TEST-01 | P2 | W11 | [issue-20261103-235256-471](../backlog/issue-20261103-235256-471-restore-intended-enforcer-and-profiler-mutation-ta.md) |
| TEST-02 | P2 | W11 | [issue-20261103-235256-949](../backlog/resolved/issue-20261103-235256-949-make-the-kill-thread-kernel-oracle-reject-survival.md) |
| TEST-03 | P2 | W19 | [issue-20261103-235257-446](../backlog/issue-20261103-235257-446-reject-benchmark-throughput-inconsistent-with-its-.md) |
| TEST-04 | P2 | W16 | [issue-20261103-235257-865](../backlog/issue-20261103-235257-865-align-packaged-consumer-matrix-deadline-with-owned.md) |
| TEST-05 | P2 | W11 | [issue-20261103-235258-396](../backlog/issue-20261103-235258-396-invalidate-the-c-abi-oracle-when-effective-native-.md) |
| ARCH-02 | P2 | W16 | [issue-20261103-235258-789](../backlog/issue-20261103-235258-789-guard-supported-portal-binary-compatibility-with-t.md) |
| TOOL-02 | P2 | W18 | [issue-20261103-235259-123](../backlog/issue-20261103-235259-123-retire-removed-orchestrator-entrypoints-before-con.md) |
| TOOL-03 | P2 | W18 | [issue-20261103-235259-662](../backlog/issue-20261103-235259-662-preserve-canonical-issue-identities-in-any-retaine.md) |
| TOOL-04 | P2 | W18 | [issue-20261103-235300-192](../backlog/issue-20261103-235300-192-return-failure-after-unsuccessful-or-ambiguous-ret.md) |
| SUP-01 | P1 | W04 | [issue-20261103-235357-491](../backlog/issue-20261103-235357-491-keep-healthy-supervisor-listeners-alive-across-idl.md) |
| SUP-02 | P1 | W04 | [issue-20261103-235357-847](../backlog/issue-20261103-235357-847-align-approved-accept-routing-with-tracee-authorit.md) |
| SUP-03 | P1 | W04 | [issue-20261103-235358-296](../backlog/issue-20261103-235358-296-preserve-and-validate-complete-openat2-arguments-b.md) |
| SUP-04 | P2 | W04 | [issue-20261103-235358-819](../backlog/issue-20261103-235358-819-retain-supervisor-helper-capacity-across-fatal-wor.md) |
| SUP-05 | P2 | W04 | [issue-20261103-235359-257](../backlog/issue-20261103-235359-257-bound-supervisor-validation-and-daemon-diagnostic-.md) |
| SUP-06 | P3 | W04 | [issue-20261103-235359-647](../backlog/issue-20261103-235359-647-preserve-typed-addfd-notification-gone-and-native-.md) |
| EVID-04 | P2 | W14 | [issue-20261103-235400-034](../backlog/issue-20261103-235400-034-escape-observed-paths-as-kotlin-literals-in-genera.md) |
| EVID-05 | P2 | W14 | [issue-20261103-235400-350](../backlog/issue-20261103-235400-350-hide-incomplete-conversion-test-seams-from-ordinar.md) |
| EVID-06 | P2 | W14 | [issue-20261103-235400-690](../backlog/issue-20261103-235400-690-own-late-created-strace-descendants-through-cancel.md) |
| EVID-07 | P2 | W14 | [issue-20261103-235400-980](../backlog/issue-20261103-235400-980-reject-unknown-io-uring-operations-as-complete-rea.md) |
| EVID-08 | P2 | W14 | [issue-20261103-235401-320](../backlog/issue-20261103-235401-320-report-iterative-profiling-exhaustion-separately-f.md) |
| EVID-09 | P2 | W14 | [issue-20261103-235401-641](../backlog/issue-20261103-235401-641-distinguish-failed-dmesg-probes-from-successful-em.md) |
| KD-E2 | P2 | W15 | [issue-20261103-235402-001](../backlog/issue-20261103-235402-001-roll-back-acquired-demo-processes-when-output-pump.md) |
| PLAT-08 | P1 | W01 | [issue-20261103-235911-795](../backlog/resolved/issue-20261103-235911-795-preserve-linux-user-notif-precedence-over-trace-in.md) |
| BPF-01 | P1 | W02 | [issue-20261103-235912-106](../backlog/resolved/issue-20261103-235912-106-install-effective-allow-list-narrowing-and-restric.md) |
| BPF-03 | P1 | W02 | [issue-20261103-235912-401](../backlog/resolved/issue-20261103-235912-401-use-canonical-action-ordering-throughout-installed.md) |
| BPF-06 | P2 | W02 | [issue-20261103-235912-625](../backlog/issue-20261103-235912-625-preserve-required-manual-landlock-file-grants-and-.md) |
| PN-E2 | P2 | W05 | [issue-20261103-235912-820](../backlog/issue-20261103-235912-820-qualify-bootstrap-directory-loading-before-synchro.md) |
| BUILD-RUN | P1 | W04 | [issue-20261103-235434-538](../backlog/issue-20261103-235434-538-diagnose-supervisor-helper-prewarm-failure-in-the-.md) |

## Existing owners — no parallel tickets

These 54 finding/related-owner mappings resolve to 51 distinct existing issues.
Several findings deliberately share one owner. Status and qualification caveats
remain those in the detailed finding register; a mapping is not a closure claim.

| Finding | Current owner priority | Package | Exact owner |
|---|---|---|---|
| BPF-04 | P1 | W02 | [issue-20261028-232811-132](../backlog/resolved/issue-20261028-232811-132-freeze-policy-definition-collections-at-every-cons.md) |
| BPF-05 | P1 | W02 | [issue-20261028-232810-687](../backlog/issue-20261028-232810-687-prevent-raw-policy-definitions-from-asserting-inva.md) |
| BPF-08 | P2 | W02 | [issue-20261103-123347-795](../backlog/resolved/issue-20261103-123347-795-validate-bpf-inspection-fragment-exits-before-acce.md) |
| BPF-09 / TEST-06 | P2 | W11 | [issue-20261103-123359-606](../backlog/resolved/issue-20261103-123359-606-fail-differential-seccomp-checks-when-simulator-ha.md) |
| LIF-01 | P1 | W03 | [issue-20261007-092325-883](../backlog/issue-20261007-092325-883-own-bounded-platform-worker-generations-and-physic.md) |
| LIF-02 | P1 | W03 | [issue-20261007-111005-986](../backlog/issue-20261007-111005-986-enforce-exact-nested-domain-transitions-and-retain.md) |
| LIF-03 | P1 | W03 | [issue-20261007-092325-883](../backlog/issue-20261007-092325-883-own-bounded-platform-worker-generations-and-physic.md) |
| LIF-04 | P1 | W02 | [issue-20261028-232811-964](../backlog/resolved/issue-20261028-232811-964-reject-installation-receipts-containing-both-succe.md) |
| LIF-Q1 | P1 | W03 | [issue-20261008-034648-678](../backlog/issue-20261008-034648-678-bind-prepared-core-resources-and-reviewed-operatio.md) |
| LIF-Q2 | P1 | W03 | [issue-20261028-230730-555](../backlog/issue-20261028-230730-555-define-the-supported-operation-contract-for-owned-.md) |
| PN05-03 | P1 | W05 | [issue-20260926-121505-981](../backlog/issue-20260926-121505-981-cancel-profiling-workloads-when-the-calling-thread.md) |
| PN05-05 | P2 | W05 | [issue-20260926-121514-401](../backlog/issue-20260926-121514-401-make-profiler-listener-startup-and-native-cleanup-.md) |
| PN-RETENTION | P1 | W05 | [issue-20260926-121456-910](../backlog/issue-20260926-121456-910-bound-user-notif-session-evidence-retention.md) |
| EVID-01 | P1 | W02 | [issue-20261028-232809-819](../backlog/issue-20261028-232809-819-reject-contradictory-completeness-in-imported-prof.md) |
| EVID-03 | P1 | W02 | [issue-20261028-232811-578](../backlog/issue-20261028-232811-578-bind-measured-profile-coverage-to-its-observed-beh.md) |
| TE-05 | P1 | W09 | [issue-20260926-121446-405](../backlog/issue-20260926-121446-405-preserve-tier-e-resource-ownership-until-cleanup-c.md) |
| TE-06 | P1 | W09 | [issue-20260926-121446-405](../backlog/issue-20260926-121446-405-preserve-tier-e-resource-ownership-until-cleanup-c.md) |
| TE-07 | P2 | W09 | [issue-20260926-122439-817](../backlog/issue-20260926-122439-817-make-tier-e-shutdown-terminal-across-service-and-c.md) |
| TE-08 | P2 | W09 | [issue-20260926-122447-716](../backlog/issue-20260926-122447-716-bind-cached-tier-e-terminal-results-to-admitted-sn.md) |
| HS-02 | P1 | W11 | [issue-20261021-115502-293](../backlog/issue-20261021-115502-293-require-path-gated-agentless-go-tests-and-vulnerab.md) |
| HS-QUAL | P1 | W10 | [issue-20261005-193140-042](../backlog/issue-20261005-193140-042-prove-selected-hotspot-event-conservation-and-all-.md) |
| PRT-04 | P1 | W06 | [issue-20261007-201438-646](../backlog/issue-20261007-201438-646-expose-finite-portal-worker-admission-deadlines-an.md) |
| CG01 | P1 | W07 | [issue-20261103-095305-625](../backlog/resolved/issue-20261103-095305-625-make-accepted-portal-byte-array-and-list-contracts.md) |
| CG02 | P1 | W07 | [issue-20261103-095306-294](../backlog/resolved/issue-20261103-095306-294-validate-complete-portal-dto-shapes-before-source-.md) |
| CG03 | P1 | W07 | [issue-20261103-095306-992](../backlog/issue-20261103-095306-992-fingerprint-the-complete-versioned-portal-wire-sch.md) |
| CG04 | P1 | W07 | [issue-20261103-095307-699](../backlog/resolved/issue-20261103-095307-699-reject-noncanonical-and-incompletely-consumed-gene.md) |
| CG05 | P1 | W07 | [issue-20261103-095308-453](../backlog/resolved/issue-20261103-095308-453-keep-portal-worker-checkout-owned-through-generate.md) |
| CG06 | P1 | W07 | [issue-20261103-095309-136](../backlog/resolved/issue-20261103-095309-136-bound-generated-portal-encoding-before-allocation-.md) |
| CG07 | P1 | W07 | [issue-20261103-095309-827](../backlog/resolved/issue-20261103-095309-827-reject-overlapping-portal-generated-output-destina.md) |
| PCG-08 | P1 | W12 | [issue-20261007-213121-162](../backlog/issue-20261007-213121-162-publish-portable-verified-worker-resources-with-in.md) |
| PCG-09 | P1 | W12 | [issue-20261103-090025-581](../backlog/issue-20261103-090025-581-simplify-portal-registration-and-single-project-wo.md) |
| SPR-01 | P1 | W13 | [issue-20261008-033416-558](../backlog/issue-20261008-033416-558-bind-approved-synchronous-spring-singleton-operati.md) |
| SPR-03 | P1 | W13 | [issue-20261008-011322-838](../backlog/issue-20261008-011322-838-prove-actual-spring-advisor-placement-on-owned-inv.md) |
| KD-01 | P1 | W15 | [issue-20261013-005317-053](../backlog/issue-20261013-005317-053-make-supervised-agent-ssrf-controls-deterministic-.md) |
| KD-02 | P1 | W15 | [issue-20261013-005318-944](../backlog/issue-20261013-005318-944-align-presentation-deck-and-demo-guidance-with-cur.md) |
| KD-E1 | P1 | W15 | [issue-20261030-144908-235](../backlog/issue-20261030-144908-235-remove-outline-caches-after-confirmed-retirement-o.md) |
| KD-E3 | P1 | W13 | [issue-20261103-090025-919](../backlog/issue-20261103-090025-919-expose-cancellation-aware-coroutine-awaits-for-own.md) |
| BUILD-04 | P1 | W11 | [issue-20261014-010000-140](../backlog/issue-20261014-010000-140-keep-executable-buildsrc-testkit-storage-checkout-.md) |
| BUILD-04-RELATED | P1 | W11 | [issue-20261020-202040-181](../backlog/issue-20261020-202040-181-run-build-tool-regression-checks-once-while-preser.md) |
| DOCS-01 | P1 | W18 | [issue-20261028-230623-718](../backlog/issue-20261028-230623-718-organize-adoption-guidance-around-explicit-securit.md) |
| DOCS-02 | P1 | W18 | [issue-20261028-230623-718](../backlog/issue-20261028-230623-718-organize-adoption-guidance-around-explicit-securit.md) |
| DOCS-03 | P1 | W18 | [issue-20261008-001530-668](../backlog/issue-20261008-001530-668-stable-packaged-consumer-compatibility-gates.md) |
| DOCS-04 | P1 | W18 | [issue-20261016-015032-003](../backlog/issue-20261016-015032-003-repair-first-time-library-installation-guidance.md) |
| DOCS-05-CI | P1 | W18 | [issue-20261019-220939-759](../backlog/issue-20261019-220939-759-bound-mutation-worker-memory-before-isolating-ci-m.md) |
| DOCS-05-STORAGE | P1 | W18 | [issue-20261020-145645-363](../backlog/issue-20261020-145645-363-persist-completed-security-data-before-analysis-in.md) |
| TOOL-01 | P1 | W18 | [issue-20260925-141818-005](../backlog/issue-20260925-141818-005-prevent-root-owned-outputs-from-containerized-test.md) |
| ARCH-01 | P1 | W16 | [issue-20261006-152422-003](../backlog/issue-20261006-152422-003-establish-packaged-java-and-kotlin-integration-con.md) |
| ARCH-03 | P1 | W17 | [issue-20261028-230731-061](../backlog/issue-20261028-230731-061-separate-production-portal-worker-packaging-from-p.md) |
| ARCH-04 | P1 | W16 | [issue-20261028-230731-560](../backlog/issue-20261028-230731-560-decouple-core-release-qualification-from-optional-.md) |
| ARCH-05 | P1 | W16 | [issue-20261028-230730-359](../backlog/issue-20261028-230730-359-publish-qualified-platform-envelopes-for-each-cont.md) |
| ARCH-06 | P1 | W17 | [issue-20261028-230730-812](../backlog/issue-20261028-230730-812-define-independent-profiler-outcomes-and-upstream-.md) |
| RET-01 | P1 | W20 | [issue-20261102-193152-029](../backlog/issue-20261102-193152-029-validate-provider-registry-keys-before-keyed-looku.md) |
| RET-02 | P1 | W20 | [issue-20261102-193152-877](../backlog/issue-20261102-193152-877-post-validate-owned-binding-state-after-provider-i.md) |
| RET-03 | P1 | W20 | [issue-20261102-232658-695](../backlog/issue-20261102-232658-695-keep-checkpoint-provider-installation-in-its-origi.md) |

RET029/877/695 are expanded existing ownership, not three new tickets. The
[context428 owner](../backlog/issue-20261102-193152-428-authenticate-installer-context-before-resolver-ins.md)
has a scoped source correction only and remains open; foundation/native acceptance
and permission to resume recovery do not follow. Historical resolved Tier E and
profiler fixes remain credited; new residual issues do not undo their closures.

## Canonical deduplication

These aliases are evidence for the same owner, never independent new issues:

| Alias | Canonical finding/owner |
|---|---|
| BPF-02 | PLAT-01 |
| EVID-02 | PN05-02 |
| PN-E1 | PN05-01 |
| PN05-07 | PN-E2 |
| BPF-07 | TEST-02 |
| TEST-06 | BPF-09 (existing owner606 above) |
| BUILD-03 | TEST-01 |
| PCG-01–07 | CG01–07, respectively |
| PRT-06 | CG05 |
| PRT-07 | CG06 |
| PCG-11 | PRT-05 |
| DOCS-06 | TOOL-02 |

DOCS-07 is the dated index correction for528 resolved on November 3 while912
remains open, not a new implementation task. Package references such as P01/P10/P11,
adoption805 and LL-01 retain their existing plan/finding context in the main review;
they are not additional newly scaffolded findings in this register.

### Shared acceptance and roadmap cross-links

- **LL-01** shares BPF-05/issue687's raw-scope correction and
  [ARCH-05/issue359](../backlog/issue-20261028-230730-359-publish-qualified-platform-envelopes-for-each-cont.md)'s
  Landlock ordering/native-envelope qualification. **BUILD-PROVENANCE** is included
  in that exact-input contract and [ARCH-04/issue560](../backlog/issue-20261028-230731-560-decouple-core-release-qualification-from-optional-.md)'s
  promotion acceptance.
- [P03 generated operations](../backlog/issue-20261103-090025-757-generate-typed-portal-operations-with-bounded-subm.md)
  consumes PRT-05/PCG-10 helper corrections and ARCH-02 compatibility evidence alongside CG04–06.
- [P09 replacement budgets](../backlog/issue-20261103-090026-932-bound-stateless-portal-worker-replacement-with-an-.md)
  consumes PRT-02's continuous replacement ownership before lifecycle qualification.
- [P13 process-boundary consumers](../backlog/issue-20261103-101536-562-qualify-portal-process-boundary-usage-safeguards-w.md)
  joins PRT-01 signal and PRT-02/04 retirement evidence; abrupt-host-death acceptance remains separate.
- [Portal implementation plan](../../superpowers/plans/2026-11-03-portal-developer-experience.md)
  owns P01/P10/P11; [adoption805](../backlog/issue-20261028-230731-805-measure-realistic-packaged-application-adoption-an.md)
  consumes corrected TEST-03 measurement validity. No new performance result is claimed.

## Hard dependencies versus delivery order

Six explicit prerequisite edges were added on new issues, plus three on existing
owners. Other existing dependencies were retained:

| Dependent | Prerequisite | Reason |
|---|---|---|
| BPF-03 | PLAT-08 | Planner/state must consume the corrected shared Linux action order. |
| BPF-01 | BPF-03 | Effective-state/dedup qualification needs canonical cumulative comparisons. |
| HS-03 | HS-04 | Local replacement/binary-source binding needs content-verified prepared sources. |
| TOOL-03 | TOOL-02 | Identity repair is conditional on the route's retention decision. |
| TOOL-04 | TOOL-02 | Failure-status repair is conditional on the same retention decision. |
| ARCH-02 | ARCH-01 (existing owner003) | Supported-surface classification precedes the accepted Portal ABI baseline. |
| EVID-03 (existing owner578) | EVID-01 (existing owner819) | Evidence consistency precedes binding that evidence to an exact behavior snapshot. |
| TE-07 (existing owner817) | TE-05/06 (existing owner405) | Absorbing shutdown must preserve cleanup ownership. |
| TE-08 (existing owner716) | TE-05/06 (existing owner405) | Terminal retry identity shares the retained finalization owner. |

The main report's W01–W20 sequence and qualification prerequisites are not a blanket
dependency chain. Independent local fixes can proceed concurrently; shared-owner
and native qualification still follow their applicable correction. Roadmap cross-links
above record acceptance order without creating a blanket frontmatter chain. Retiring unused ingestion may
eliminate the need for TOOL-03/04 implementation, but no such decision or closure is
claimed here.

## Validation boundary

Focused checks and coordinator reconciliation cover new frontmatter/severity,
canonical dependencies, 59 unique issue/index/owner registrations, all 51 existing
finding owners, three roadmap acceptance addenda, package membership and local
review/backlog links. `./scripts/adkw check-backlog`, review-scoped whitespace and
staged whitespace checks pass. The whole-checkout diff check flags a concurrent
HotSpot patch line; see the [final validation receipt and source-drift limits](2026-11-04-repository-review-evidence.md#final-documentation-validation).
These documentation checks do not supply source fixes or release qualification.
