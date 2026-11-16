---
title: "Bind cached Tier E terminal results to admitted snapshot identity"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/AgentDefinitionFile.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierECollectorRuntime.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierECollectorRuntimeTest.kt"
target_symbols:
  - "TierECollectorRuntime"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bind cached Tier E terminal results to admitted snapshot identity

**Context:**
The in-memory finish overload keys terminal requests by null artifact plus producerClosure; a different snapshot or session epoch with the same closure value receives the prior cached result before identity validation.

**Needed:**
1. Extend runtime tests with repeated in-memory finalization using identical, changed, and wrong-session definition snapshots.
2. Bind the cached terminal result to an immutable identity of the admitted snapshot and producer-closure assertion, rather than treating all in-memory inputs as a null artifact.
3. Check request identity before returning cached evidence. Reject conflicting terminal requests without reopening or re-finalizing the already retired source.

## Evidence and impact

Static review on 2026-09-26; no runtime reproduction yet:

- `TierECollectorRuntime.kt:129–137`: the in-memory overload constructs `TierETerminalRequest(null, producerClosure)`, compares it with the cached key, and returns the old result before checking `definitions.publication?.sessionEpoch`.
- `TierETerminalRequest` at lines 173–176 stores only the optional path artifact and closure flag; it carries no in-memory snapshot identity.
- Calling `finish(snapshotA, PROVEN)` and then `finish(snapshotB, PROVEN)` returns A's evidence even when B has a different session epoch or conflicting definitions. This violates the runtime's claimed rejection of different terminal requests and can return a previously complete result for invalid retry input.
- The first finalization does validate session identity. The production daemon normally finalizes once; this is an internal retry/idempotency contract defect, not a demonstrated external containment bypass.
- Existing tests reject a changed path-artifact request but only exercise a single prevalidated in-memory finalization.

## Acceptance criteria and validation

- Repeating the same admitted immutable request returns the identical cached result and closes the source once.
- A wrong epoch, changed publication/content, changed closure assertion, or conflicting overload request is rejected before cached success is returned.
- Mutation of caller-held collections cannot alter the stored request identity after admission. Choose a bounded immutable identity/receipt or snapshot representation rather than retaining an extra unbounded evidence copy.
- Rejection does not overwrite the original result, re-run collection, reopen the definition path, or repeat native cleanup.
- Extend `TierECollectorRuntimeTest`, run its focused host tests, then `:profiler:test` and the merge gate.

## A2b implementation evidence (2026-11-03; before review and combined build)

- Runtime terminal requests now retain the same immutable `AgentDefinitionFile.Contents` consumed
  by the collector. The shared snapshot model compares all definition lists, counters, publication
  metadata and source provenance by value; neither a claimed digest nor null artifact identifies
  in-memory input on its own. Nested stack/frame collections already own defensive copies.
- No second evidence image or dictionary copy is created for caching. Existing descriptor decoding
  bounds remain: 64 MiB input/decoded payload, 300,000 full-decoder records, 129 frames per stack
  and 65,535 bytes per string. Direct internal `Contents` construction has no size admission bound;
  this change does not claim a new heap-memory limit or change that existing contract.
- Runtime tests cover equivalent retries, every contents/provenance field, epoch/publication/closure
  conflicts, both overload directions, caller collection mutation and original-failure preservation.
  Rejection cannot rerun collection, reopen an artifact or repeat successful physical cleanup.
- Detailed command logs and execution counts are recorded in
  `.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a2b-report.md`.
  At the time of that report, independent review and the combined A2 build were pending;
  the later completion evidence follows. The issue remains **open** for orchestrator review/integration.

## 2026-11-03 combined A2 completion evidence

Independent Sol reviews of A2a and A2b each passed with no findings. The final
`MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock ./gradlew build` exited 0 in
14m 7s (242 tasks: 79 executed, 163 up-to-date; local `a2-full-build.log`).
Profiler host: 1,484 discovered, 1,480 executed, four skipped; rootful: 22
executed, zero skipped; regular integration: 16 discovered, seven executed,
nine skipped; fresh-JVM: 24 discovered, 19 executed, five skipped. No failures
or errors. The current dirty-source, rootful image, kernel and JDK limitations
are the same as A1 in the split-deployment guide; this is not clean-source or
historical qualification. Remains open for orchestrator review/integration.

## Related work

Distinct from [Tier E cleanup ownership](issue-20260926-121446-405-preserve-tier-e-resource-ownership-until-cleanup-c.md): this concerns result/request identity after successful terminalization. Coordinate changes to their shared runtime file. Preserve the existing prevalidated-artifact admission and producer-closure contract; declare additional identity model files before implementation if needed.

---
<!-- id: issue-20260926-122447-716-bind-cached-tier-e-terminal-results-to-admitted-sn  file: issue-20260926-122447-716-bind-cached-tier-e-terminal-results-to-admitted-sn.md -->
