# Exact benchmark application-owner validation

## Requested build/check comparison

Operator requested adding check alongside build if it gives fuller validation.
Actual project dry-run task graphs are compared on the integrated baseline
2c44a84b (`run-1b94eb1fe36a`): build selects389tasks; build check selects the same
389tasks, with no added or omitted task. The condition is not met, so the active
full-build hook is retained without redundant task arguments or lifecycle rewiring.
This is repository task-graph evidence, not only the generic Gradle relationship.

## Continued qualification review

The benchmark's applicationSegment helper is consumed by both preparation
readiness and final Analyze reporting. It still used substring owner matching,
although the candidate tools already had an exact qualification renderer parser.
This permits WrongBenchmarkFixture and other.BenchmarkFixture leaf/router names
to count as the expected fixture. A foreign route also falsely invalidates a
correct application segment. Anchoring method suffix alone does not anchor owner.

Red `run-ce806fabfdb0` reproduces ready=true for all8routes from the wrong owner,
false useful segments for wrong/qualified owners and malformed displays, and
false wrong-route classification from an unrelated owner.

Expose the existing exact owner/method extraction inside the internal candidate
package and consume it in both candidate matching and benchmark segment matching.
No second regex, dependency, native metadata lookup or new parser package is added.
The helper's export is only for the two actual internal Go package consumers.
Only exact BenchmarkFixture owner names participate in benchmark leaf/router
comparison; different owners, malformed/native-only and unknown names remain
unavailable. Same-owner wrong routes and leaf/router ordering remain unchanged.
The report still describes application segments, not complete Java stacks.

## Verification and connected-code review

- Candidate/benchmark host/race checks pass54tests (`run-54a5050e64a1`), including
  exact renderer displays, constructors, malformed names, route ordering, native-
  only outcomes and the new owner controls. Full experimental Go host/race suite
  passes170tests/18packages (`run-b07eb63108fe`).
- Read-only revalidation of actual full benchmark packets preserves ready=true,
  route0, checked epoch0 and32correct/0wrong/0missing measured operations;
  collection_loss_complete stays false. Both retained cold negative controls stay
  ready=false (`run-539936f47eed`). No original packet/report is rewritten.
- Actual retained C1/C2/interpreter-G1 captures retain40marked tokens and30/31/33
  useful prefixes; lifetime_qualified remains false (`run-570e771de47c`).
- Review traces both helper consumers (readiness and reporting), both candidate
  oracle callers and the exact parser's bounded ASCII fixture naming domain.
  No schema/identity/counter/lease, measured window, buffer, deadline, runtime
  whitelist or production mapping behavior is changed.

The now-mandatory full-build commit hook supplies the complete repository gate for
the committing revision; any failure blocks its commit. Existing unrelated
Codanna settings work in the source checkout is preserved during integration.
