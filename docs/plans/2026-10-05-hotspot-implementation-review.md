# HotSpot implementation review — recent execution changes

Scope: `eacd0081..6e0b34d6`, seven implementation/evidence commits, excluding
unrelated existing operator edits. Review covers production deferred diagnostics/
UTF8 reuse, optional digest probe, candidate harness/verifiers and accounting
qualification. This is not an approval of the entire repository or of gated
production runtime/provider/loss promotion.

## Verdict

**Changes requested before using qualification results as promotion evidence.**
Four concrete qualification-layer defects reproduced. No production sandbox escape
or removed public ABI was established in the reviewed readout diff. Existing
production RawJson/reader/result compatibility and Kotlin/legacy ABI checks pass
(run-f494e8a00986). Experimental tools still return production_qualified/lifetime
qualified false; that limits current impact but does not make their validators sound.

### R1 — P2: identity, schema and terminal proof can be bypassed

Locations: `experiments/hotspot-agentless/scripts/verify-runtime-candidate.py:9–21`
and `verify-loom-candidate.py:16–27,39–42`.
Runtime verifier accepts schema99, foreign occurrence prefix and inconsistent nested
event identity. Loom accepts another process/birth/full path with the same token
basename; its carrier-only early continue accepts missing terminal records. Logical
ID7.0 also compares equal to integer7, and duplicate oracle tokens overwrite in
dictionary comprehensions. These prevent the validators from proving event-own
identity/pairing even though their outputs cannot yet activate production support.

Reproduced: runtime useful1 for schema99/foreign ID; Loom marked1 for wrong PID/
birth with no terminal, and logical_observed1 for float ID/wrong process.
[Issue](../internals/backlog/issue-20261005-212825-219-validate-complete-candidate-evidence-identity-and-.md).

### R2 — P2: substring names count wrong routes as useful

Locations: `verify-runtime-candidate.py:35–40`, `verify-loom-candidate.py:32–34`.
Owner and expected method are substring matches. `WrongFixture.leafCorrupt()` and
`WrongFixture.routeACorrupt()` satisfy expected `Fixture.leaf`/`Fixture.routeA`,
yielding useful/logical counts for different classes and methods. The current
native-only/unavailable distinction must remain, but useful application methods
need exact owner/name extraction and ordered comparison.
[Issue](../internals/backlog/issue-20261005-212825-569-match-exact-candidate-application-methods-instead-.md).

### R3 — P2: cleanup loses remote failure/ownership evidence

Location: `run-capability-candidates.py:81–88`.
The `docker rm -f` return code is ignored. A failing removal plus successful local
wrapper waits completes cleanup without establishing remote retirement. A removal
timeout also raises before wrapper joins and can replace the original failure.
Killing a Docker client does not establish container retirement. Fault injection
of the existing finally block confirmed returncode1 is ignored. No actual privileged
container was leaked by this review; the defect is the unhandled failure path.
[Issue](../internals/backlog/issue-20261005-212825-858-retain-remote-candidate-cleanup-failures-and-pendi.md).

### R4 — P2: per-CPU contradictions disappear in aggregate totals

Location: `profiler/hotspot-agentless/internal/accounting/conservation.go:37–51`.
Aggregate checks cardinality/generation/overflow but not each CPU's attempted/
submitted/reserve equation. CPU A(1attempt,2submitted,0reserve) and CPU B(1attempt,
0submitted,0reserve) sum to(2,2,0). With received/terminals2 and finality asserted,
Evaluate returns complete/lost0. The first CPU is impossible and the second's drop
is unexplained. Validate each tuple before summing; do not rely on totals to expose
contradictory final snapshots.
[Issue](../internals/backlog/issue-20261005-212826-184-reject-contradictory-per-cpu-conservation-snapshot.md).

## Reproduction evidence

Read-only Python repro of current verifier functions and AST-extracted existing
cleanup block: `profiler/build/reports/hotspot-review/reproduce.py`, run-4e2692bea8ad.
No Docker call is made; failed cleanup uses an injected CompletedProcess.
Go read-only proof repro: `profiler/hotspot-agentless/build/review-repro/main.go`,
run-ca8cce5bba54, outputs err=nil/proof Complete=true Lost0 for inconsistent CPU rows.
Both are review artifacts, not production fixes or failing tests left in the suite.

## Positive findings and limits

Deferred canonical JSON uses parser-owned trees and PUBLICATION; getters/canonical
value equality preserve the reviewed public mapping/report contract. Strict decoder
reuse stays local to a read invocation and convenience decode resets per complete
row. Limits/permission rules/physical ownership/policy refusal are not relaxed.
Digest prototype matches actual hashes and propagates permissions without fallback;
it is correctly not activated in production. Production runtime whitelist and loss
completeness remain conservative. Qualification models are offline proof evaluators,
not runtime authorities; boolean fixtures cannot justify native promotion.

The native counter pressure experiment is isolated and does not bind the production
Gadget owner. Its terminal count is modeled from synchronous delivery, not a real
enrichment pipeline; documentation already describes that boundary. No proof of
global collection completeness or new compiled/mount leases is implemented.

## Recommended order

Harden identity/pairing and exact names, check remote cleanup/pending owners, reject
per-CPU contradictions, then revalidate retained evidence and rerun the affected
virtual corpus with independent expected birth identity. Only after these gates
design production digest opt-in or default loss-owner binding. Compiled/virtual
promotion remains blocked on actual event-time lifetime mechanisms, not faster
qualification tools. See [remediation plan](../superpowers/plans/2026-10-05-hotspot-review-remediation.md).
