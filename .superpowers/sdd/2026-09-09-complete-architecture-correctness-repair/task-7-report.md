# Task 7 Report: Transactional portal lifecycles

## Outcome

Implemented the Task 7 broker and worker lifecycle repair without changing portal frame bytes,
method identifiers, descriptor ordering, or native layouts.

### Broker

- Added the explicit `New`, `Starting`, `Running`, `Closing`, `Closed`, and `Failed` lifecycle
  reducer with explicit protocol-violation outcomes.
- Kept all initial workers provisional until the complete pool is connected and ready, using one
  absolute 30-second default deadline across connection acceptance and readiness.
- Added rollback for every startup acquisition and bounded child destruction/reaping.
- Serialized pool publication, worker return, replacement publication, and shutdown.
- Retire/reap failed workers before replacement. Replacement runs on broker-owned maintenance so
  it cannot delay or replace the original RPC failure; shutdown interrupts and joins maintenance.
- Routed transport failures through `PortalBrokerCallMachine` and validate both request ID and
  response method.

### Worker

- Extracted `PortalWorkerDriver` behind injected transport and dispatcher interfaces.
- Added authoritative `SendingReply`, `ReplySucceeded`, and `ReplyFailed` transitions.
- Unified builtin and generated dispatch failure conversion at one boundary and deliberately
  unwrap reflective guest exceptions.
- Treat partial receive and response-write failure as terminal; a failed write performs exactly
  one send attempt and cannot append an error frame.
- Moved READY publication after containment, strict dispatcher publication/guest construction,
  channel construction, and driver construction.

### Evidence and documentation

- Added exhaustive broker lifecycle/event classification tests and worker transition tests.
- Added startup second-worker rollback, shared startup deadline, asynchronous replacement,
  checked-out shutdown, response-method mismatch, partial receive/write, and reflection failure
  regression coverage.
- Updated `process-portal-design.md`, added `portal_lifecycle.puml` and its generated SVG, and
  resolved the two scoped backlog records.

## TDD and verification

- RED: focused broker/worker tests initially failed compilation because lifecycle, driver,
  `SendingReply`, and reply result events did not exist.
- GREEN: `./gradlew --no-daemon :portal:test :portal:integrationTest :portal-worker:test` — passed.
- Static/module gates: `./gradlew --no-daemon :portal:check :portal-worker:check` — passed after the
  final concurrency fix (98 tasks; includes tests, integration tests, ktlint, detekt, coverage, and
  SpotBugs tasks).
- `git diff --check` — passed.
- Diagram regeneration: `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true plantuml -tsvg
  docs/diagrams/portal_lifecycle.puml` — passed.

## Known external gate state

`./scripts/adkw check-backlog` still reports nine pre-existing plan files without frontmatter,
including the overall repair plan. Neither new Task 7 backlog record is reported. An earlier ADK
guard traversal passed the portal/platform/codegen/worker portions and then encountered the
unrelated existing `SupervisorPostReplyFailureTest` failure in `enforcer`; receipt:
`.adk/verification-receipts/guard-1788991202855.log`.

## Review follow-up

Four P2 review findings were repaired in a separate follow-up commit:

- An `AcceptedSocketHandoff` now owns each accept result until claim or confirmed retirement. It
  also covers timeout/process-exit dummy-connection races and retains pre-native close failures.
- Startup publishes partial and ready-but-unpublished ownership in separate registries. Close
  cancels and joins startup before `Closed`; a cancelled startup cannot launch another worker.
- Partial startup rollback is generation-safe and retryable per resource. Later close attempts only
  unfinished channel/peer, process/reap, pump, listener, and endpoint releases.
- Reflective targets are unwrapped as `Throwable`. Ordinary `Exception` remains an error reply;
  fatal targets stop the machine, preserve identity, send no frame, and close transport ownership.

Follow-up RED evidence included an unresolved handoff type, a close/start latch assertion, retained
rollback assertion, and fatal identity being replaced by a cleanup `IOException`. Focused tests then
passed, followed by the full portal/worker module and integration/static gates recorded in the
follow-up commit output.

- `./gradlew --no-daemon :portal:test :portal:integrationTest :portal-worker:test` — passed.
- `./gradlew --no-daemon :portal:check :portal-worker:check` — passed (98 tasks; unit,
  integration, ktlint, detekt, coverage, and SpotBugs gates).
- `./scripts/adkw check-backlog` — only the same nine pre-existing plan-frontmatter errors remain;
  neither Task 7 backlog item is reported.

## Second review follow-up

Three additional P2 lifecycle findings were repaired in a second independent follow-up:

- Startup acquisition now serializes every resource publication with retirement and remains in the
  provisional registry until acquisition definitively finishes. Close timeout and interruption
  publish retryable `Failed`; a child returned later is still destroyed and reaped before retry can
  reach `Closed`.
- Cleanup collection includes fatal `Throwable`s, continues across independent owners, and selects
  fatal-first failure identity. Broker close publishes its terminal lifecycle and counts down the
  concurrent-close latch in `finally`; fatal startup rollback and fatal close retain unfinished
  owners for a later resource-by-resource retry. Process descendants are terminated before their
  parent so inherited stdout cannot pin the owned output pump.
- Worker capability cleanup no longer replaces a reflected fatal target. The exact target `Error`
  is rethrown with capability cleanup followed by transport cleanup as suppressed evidence and zero
  response attempts.

Second follow-up RED evidence covered join timeout, interrupted close, a wedged concurrent waiter,
fatal provisional rollback, fatal-first cleanup continuation, and capability cleanup replacing a
reflected fatal. GREEN verification:

- Focused lifecycle/cleanup/worker regressions — passed.
- `./gradlew --no-daemon :portal:test :portal:integrationTest :portal:check :portal-worker:test
  :portal-worker:check` — passed (98 tasks; unit, integration, ktlint, detekt, coverage, and SpotBugs
  gates).
