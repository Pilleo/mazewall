---
title: "Qualify owned Koog tools inside deterministic real agent loops"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
   - "tests/consumers/koog-portal/build.gradle.kts"
   - "tests/consumers/koog-portal/src/main/kotlin/io/mazewall/consumer/koog/ScriptedPromptExecutor.kt"
   - "tests/consumers/koog-portal/src/main/kotlin/io/mazewall/consumer/koog/KoogThreadAgentProbe.kt"
   - "tests/consumers/koog-portal/src/main/kotlin/io/mazewall/consumer/koog/KoogPortalAgentProbe.kt"
   - "tests/consumers/koog-portal/src/main/kotlin/io/mazewall/consumer/koog/KoogThreadProbe.kt"
   - "tests/consumers/koog-portal/src/main/kotlin/io/mazewall/consumer/koog/KoogPortalProbe.kt"
   - "tests/consumers/koog-portal/gradle/verification-metadata.xml"
   - "tests/consumers/koog-portal/locks/0.1.0-integration.a4aac5dcc759/gradle.lockfile"
   - "tests/consumers/koog-portal/README.md"
   - "integrations/koog/README.md"
   - "src/consumerVerification/kotlin/io/mazewall/verification/OptionalIntegrationAcceptanceTest.kt"
   - "src/consumerVerification/kotlin/io/mazewall/verification/KoogAgentSummaryTest.kt"
   - "src/consumerVerification/kotlin/io/mazewall/verification/KoogSpringPublicationTest.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Qualify owned Koog tools inside deterministic real agent loops

**Context:**
Approved inline Plan B: qualify actual Koog1.3.0 AIAgent loops using a bounded scripted PromptExecutor, real published adapters, separate fresh thread/Portal JVMs. Operator approved consumer-only agents-core1.3.0 dependency. No live providers, adapter dependency changes, fallback/replay, raised budgets or core changes.

**Needed:**
1. B1 add consumer-only dependency and bounded success/validation agent probes; B2 qualify denial/overload/cancellation/deadline/terminal unknown outcomes and require exact per-mode evidence in existing optional acceptance. Native inline with one final independent review.

## Inline execution

- [x] B1 scripted actual-agent consumer probes and reviewed dependency state.
- [x] B2 failure/lifetime qualification and mandatory parent evidence gate.
- [ ] Exact-source acceptance, full build and one independent final review.

Pre-flight: B2 consumes B1's two fresh-JVM tasks and assertion-bound summaries;
neither plan changes the published Koog adapter or shares Spring interfaces.
Consumer-only `ai.koog:agents-core:1.3.0` is explicitly approved by the operator.
No plan files, separate journals or worktrees.

Ruling: the planned `maxIterations=4` counts graph nodes, not model turns, in Koog
1.3.0. Actual one-tool success reaches its result but then fails the finish node's
iteration guard. Use the minimum five nodes (start/model/tool/model/finish), with
the independent executor still rejecting a third model request. No execution,
drain, test or CI timeout increases. Cost if wrong: qualification rejects rather
than replaying. Upstream ordinary failures become error-marked observations;
assert both that marker and the original failure event, never agent text as
permission authority or a successful tool receipt.

B1/B2 focused evidence: both staged-JAR native agent tasks pass against pinned
producer `a4aac5dcc759080228458a382e7dc7d580ac78f0`. Actual successes verify decoded
JSON results and one completion event; malformed/missing/invalid arguments and
unknown tools never admit native work; permission/result failures retain their
upstream original error events. Thread overload retains one worker/admission;
cancellation/deadline hold real lexical cleanup and observe incomplete drain.
Portal crash is UNKNOWN_COMPLETION and subsequent explicit calls reject with the
original stateful identity; both actual child process sets physically retire.
Cancellation/deadline retain original mapping cleanup with unfinished ownership.

Ruling: Portal cancellation uses an observable real host-mapping entry, not sleeps
or a claim of authenticated child-body entry. Both protected invocation and actual
child retirement are real; exact remote start is deliberately not inferred. Cost
if wrong: no broader running-child cancellation claim is made. Existing broker
maintenance may log interrupted retirement during concurrent close; canonical
close and captured process death remain mandatory and the warning is not suppressed.

The new parent summary test was observed RED before the gate existed, then GREEN
with all consumer host tests and static checks. Missing/duplicate/partial/altered/
contradictory evidence rejects. The scripted executor's own missing-history and
extra-request self-check grants no containment authority. Consumer dependency
locks/checksums were generated explicitly; historical locks are unchanged, and
the existing exact-publication checksum helper now also handles the seven Koog
fixture modules without broad trust exceptions. Exact-source/full gates and final
independent review are still pending.

---
<!-- id: issue-20261025-223944-611-qualify-owned-koog-tools-inside-deterministic-real  file: issue-20261025-223944-611-qualify-owned-koog-tools-inside-deterministic-real.md -->
