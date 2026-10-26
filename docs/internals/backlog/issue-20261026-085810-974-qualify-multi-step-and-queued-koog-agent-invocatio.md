---
title: "Qualify multi step and queued Koog agent invocations"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
   - "tests/consumers/koog-portal/src/main/kotlin/io/mazewall/consumer/koog/ScriptedPromptExecutor.kt"
   - "tests/consumers/koog-portal/src/main/kotlin/io/mazewall/consumer/koog/AgentSequenceChecks.kt"
   - "tests/consumers/koog-portal/src/main/kotlin/io/mazewall/consumer/koog/KoogThreadAgentProbe.kt"
   - "tests/consumers/koog-portal/src/main/kotlin/io/mazewall/consumer/koog/KoogThreadProbe.kt"
   - "tests/consumers/koog-portal/src/main/kotlin/io/mazewall/consumer/koog/KoogPortalAgentProbe.kt"
   - "src/consumerVerification/kotlin/io/mazewall/verification/OptionalIntegrationAcceptanceTest.kt"
   - "src/consumerVerification/kotlin/io/mazewall/verification/KoogAgentSummaryTest.kt"
   - "tests/consumers/koog-portal/README.md"
   - "integrations/koog/README.md"
   - "docs/presentation/PRESENTER_GUIDE.md"
   - "docs/internals/backlog/issue-20261013-005319-256-rehearse-the-complete-feature-tour-with-an-unfamil.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Qualify multi step and queued Koog agent invocations

**Context:**
Approved inline Tasks1-4: preserve one-call real AIAgent gates; add exactly two explicit tool calls with three model requests/seven nodes, denied-then-allowed and repeated-argument sequences, terminal unknown Portal outcome sequence, two admitted agents with queued cancellation and third rejection, mandatory exact per-mode sequence evidence. No dependencies/core/protocol/policy changes, deadline increases or automatic retries. Human rehearsal remains separate and cannot be simulated.

**Needed:**
1. Task1 bounded fixture RED then GREEN, Task2 real thread/Portal sequences, Task3 queued-agent capacity/cancellation, Task4 mandatory parent evidence and guides. Preserve one final independent review and exact-source/full build gates; no worktrees/plan files/separate journals or push/PR/merge/publication.

## Inline execution

Base: `f3ad9e740cdae56546a9dd65629855a26c1d00a7` (merged PR560).

- [x] Task1: bounded fixture, old constructors/cases preserved, input snapshots and self-checks.
- [x] Task2: real denied/allowed and repeated sequences, terminal Portal state loss.
- [x] Task3: two admitted/one queued, cancelled queued body never enters, third rejected.
- [ ] Task4: four exact summaries mandatory; guides, exact-source/full gates and one review.

Pre-flight: Tasks2/3 consume Task1's actual Tool registry/executor, Task4 consumes
their post-retirement sequence summaries. Keep one-call two-request/five-node
cases, new two-call three-request/seven-node bounds; keep all dependencies/policies
fixed. New test-only one-worker/two-admission cases do not alter existing limits.
Portal overload remains NOT_STARTED, not the thread rejection type. Hold the
Portal host mapping before its RPC; preserve every config/profile/launch field.

Progress stays in this scoped backlog item, not a plan file or separate journal.
The existing unfamiliar-human item stays open: implementation cannot stand in for
an actual human or certify released SNAPSHOT bytes without resolved provenance.

Task1 RED: the real fresh-thread executable reaches the new three-turn self-check
and fails because bounded multi-step support is missing, not on compilation or
dependencies. GREEN: both existing fresh agent modes pass (13s), including the
three-response/extra-request and missing-history checks and mutation of caller-
owned collection inputs. Old single-call success/error/unknown-tool and retirement
checks remain intact. A missing-history exception-shape regression caught during
refactoring was corrected by restoring the original explicit one-result guard.
Focused iteration uses the existing pinned published `a4aac5dcc759` fixture only;
final committed-source qualification remains required.

Task2 RED: ordered-history self-check fails before the validator exists. GREEN:
both real agent modes pass in 17s with three model requests/seven graph nodes.
Actual call/result order, IDs, tool names, JSON arguments, error flags and decoded
success bytes are checked at every turn. Missing/reordered/duplicated/altered
history rejects. Native cases preserve the actual denied-file exception, then
allow the independent permitted read/transform; two distinct IDs with identical
arguments produce two explicit submissions, never an automatic retry. The Portal
crash sequence retains UNKNOWN_COMPLETION then STATE_LOST and original worker PID.

Ruling: share the existing strict bounded UTF-8 reader by widening its consumer-
local visibility to internal, instead of adding a second reader implementation.
Its existing direct-native size/encoding tests remain selected. Cost if wrong:
both callers fail the same existing boundary checks. A lost Portal service can
reject before or inside a host mapping; mapping entry is not child-body entry.
The sequence counts at most one mapping per explicit request, retains both real
failure events and requires terminal identity plus joined process retirement.

Task3 RED: the new queued-agent case reaches the missing coordination barrier and
fails. GREEN: both full fresh agent executables pass in 15s. One actual running
agent plus one admitted queued agent fill a new one-worker/two-admission fixture;
the third rejects, queued cancellation reaches the original handle, queued body/
mapping stays zero even after active release, and the original active agent still
returns its real result. Canonical drain/close plus captured worker joins remain
mandatory. Portal preserves every config/profile/launch field and only lowers
this isolated test's capacity from eight to two; its mapping is held before RPC.
Diagnostic reads/yield under five seconds coordinate stable held states, never
authorize work or substitute for retirement. No submissions are retried.

Task4 parent gate RED: four tests run, three fail because the old parser accepts
only single-call evidence and ignores required sequence evidence. GREEN: four
summary tests plus the complete host consumer harness, ktlint and SpotBugs pass.
Both native executables emit their original summary and one sequence summary only
after all assertions and captured worker retirement (23s focused run). The parser
requires all four exactly once and rejects missing/duplicate/altered/contradictory
evidence. Old-field rejection fixtures include valid new evidence so they cannot
pass merely because a new marker is absent. Guides retain the independent actual-
human gate; no participant, timing or released-byte resolution is invented.
Final exact-source acceptance, full build and scoped independent review are next.

---
<!-- id: issue-20261026-085810-974-qualify-multi-step-and-queued-koog-agent-invocatio  file: issue-20261026-085810-974-qualify-multi-step-and-queued-koog-agent-invocatio.md -->
