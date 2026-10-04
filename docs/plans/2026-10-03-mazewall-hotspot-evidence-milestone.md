# MazeWall HotSpot integration — milestone 1 evidence

Approved integration plan: milestone 1 is implemented as internal Kotlin replay
and evidence/coverage adapters in `:profiler`. Collector launching, public strategy
dispatch, packaged assets and workload/session ownership remain later milestones.
No existing public API descriptor or USER_NOTIF/Tier E path was changed here.

## Delivered contract

- Bounded schema-1 JSONL reader with strict UTF-8, newline/EOF, version, clock,
  record kind and identifier validation. JSON nesting is checked before parsing.
- Exact session/occurrence pairing independent of event/terminal delivery order.
  Shared stack references do not collapse separate events. Process PID/start-tick
  generation changes, duplicate events/terminals and mismatched summary counts fail.
- Immutable record/frame/error lists and typed unsigned positions, optional BCI/
  source positions, provider identity, quality and retained raw JSON diagnostics.
  Raw event arguments and mapping/identity extensions remain available in immutable
  JSON; they are not converted into fabricated resolved policy operands.
- Independent retained-occurrence/recovery payload caps. Exceeding retention
  continues bounded replay validation/accounting, explicitly marking retained
  evidence incomplete. Tracking/input caps abort explicitly rather than growing.
- Incomplete common coverage/policy projection. Known event-reserve observations
  remain a lower bound; absent native loss remains unknown. Physical join/drain
  evidence never sets semantic coverage complete, and worker-policy conversion fails.

Replay limits: line1MiB, JSON depth64, wire records65536, tracked occurrences32768,
frames4096, retained occurrences4096 and retained payload32MiB. Payload bytes are
wire-retention accounting, not a claim of exact JVM heap/RSS bounds. Recovery
diagnostics share the payload budget. No native/kernel installation is required.

The adapter uses existing EBPF source-mechanism coverage with an explicit replay-only
reason; `HOTSPOT_AGENTLESS` strategy exposure remains milestone 5. Models stay
internal until that public result/API compatibility review.

## Verification

14 focused tests pass: assembler10, coverage2 and reader2. The full profiler unit
suite, static analysis, warning ratchet and `./gradlew build` pass. Final static
analysis contains zero findings in the new HotSpot source directory.

The first compilation demonstrated the missing implementation. Subsequent behavioral
red regressions caught recovery payload-budget bypass, lost native-drop lower bound
and excessive JSON nesting; each passed after its targeted correction. A genuine
measured event/frame projection is retained with explicit fixture provenance and
a clearly synthetic one-occurrence summary, not a new native acceptance claim.

The merge gate also encountered the already tracked intermittent
`ProcessWideProfilerIntegrationTest` failure; an unchanged focused retry passed.
An intermediate merge run timed out, and the final full merge gate passed. Those
verification interruptions do not justify a JVM profiler behavior change in this
replay-only milestone.

Next milestone: promote the canonical Go collector and reproducible installable
assets, preserving the verified dependency graph and native ownership evidence.
