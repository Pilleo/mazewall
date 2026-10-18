---
title: "Add SandboxEscapeBench-inspired Koog containment contracts"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "testing"
target_modules:
  - ":portals:runtime"
target_files:
  - "tests/consumers/koog-portal/src/main/kotlin/io/mazewall/consumer/koog/KoogPortalProbe.kt"
  - "tests/consumers/koog-portal/denied-api/src/main/java/io/mazewall/consumer/koog/DeniedResourceService.java"
  - "src/consumerVerification/kotlin/io/mazewall/verification/OptionalIntegrationAcceptanceTest.kt"
  - "tests/consumers/koog-portal/denied-worker/io/mazewall/consumer/koog/worker/DeniedResourceProbe.kt"
  - "tests/consumers/koog-portal/src/main/kotlin/io/mazewall/consumer/koog/KoogContainmentChecks.kt"
  - "tests/consumers/koog-portal/build.gradle.kts"
  - "src/consumerVerification/kotlin/io/mazewall/verification/KoogContainmentSummaryTest.kt"
  - "benchmarks/koog-containment/README.md"
  - "tests/consumers/koog-portal/README.md"
  - "integrations/koog/README.md"
target_symbols: ["DeniedResourceService", "verifyFilesystemContainment", "verifyNetworkAndProcessContainment", "requireKoogContainmentSummary"]
needs_kernel: true
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Add SandboxEscapeBench-inspired Koog containment contracts

**Context:**
The operator approved the SandboxEscapeBench-inspired Koog/Portal plan and explicitly assigned implementation to this session on branch `test/sandbox-escape-contracts`, using sequential subagent implementers and independent reviews. Strengthen the existing published-only native acceptance path instead of importing AISI's container exploits or creating a second framework. This is a bounded engineering demonstration, not arbitrary-agent containment or a SandboxEscapeBench score.

The local reviewed plan/spec are `docs/superpowers/plans/2026-10-18-sandbox-escape-bench.md` and `docs/superpowers/specs/2026-10-18-sandbox-escape-bench-design.md`. Those paths are intentionally Git-ignored. `:portals:runtime` identifies the exercised boundary owner supported by the legacy scaffolder; the explicit file inventory above defines the implementation scope and does not authorize changes to Portal enforcement.

**Needed:**
1. Add controlled read/write/symlink attempts through the generated test-only worker, with readable/writable host canaries, typed completed denials, unchanged bytes and retained physical worker handles.
2. Add bounded loopback-connect and current-JDK child-launch probes with successful outside-worker controls. Preserve exact permission evidence; refusal, missing executables, timeout and cleanup failure are failures, not successful containment.
3. Require one exact native completion summary only after all existing/new assertions and joined physical retirement. Add host-safe corruption tests and print accepted evidence into the existing CI JUnit artifact.
4. Publish a reproduction guide using the same checks, keeping dangerous fixture methods outside the sole production `uppercase_ascii` registry. Preserve all existing input/wire/readiness/cancellation/no-replay guarantees.
5. Run focused host/native verification, the full build checkpoint and actual committed optional-integration aggregate; record unavailable prerequisites honestly. Submit through the existing review/control plane; no direct default-branch push or merge.

## Investigation
- Existing adapter/consumer tests already cover the registry, bounded data, required readiness, remote read denial, crash/no-replay and cancellation/lifetime separation.
- Existing CI requires `optionalIntegrationCheck` and uploads its JUnit output. No new workflow, dependencies, production APIs, policies, exploit scripts or model credentials are needed.
- Initial host baseline: `./gradlew consumerHarnessTest --tests '*Koog*' --console=plain --max-workers=1` passed before implementation; compiler/deprecation warnings were already present.
- ADK is not installed. The existing orchestrator scaffolder produced this ID; its old module allowlist does not include Koog. Current ADK validation remains unavailable until provisioned by the operator.

## Side effects
- Extends only the native test fixture API and required Koog consumer evidence; production tools, policies and public APIs remain unchanged.

## Implementation and verification checkpoint

- Implemented filesystem controls/retained worker owners in `c804efa3`, network/process probes in `61d40c42`, and strict CI summary/public guide in `50e61493`; independent per-task reviews approved all three. Formatting correction `2a752008` and canonical-status metadata correction `6f7e69be` also received scoped re-review.
- Host harness: 15 tests, zero failures/errors/skips. Actual JAR-only native staging reached both the original and new exact completion summaries after controls, denials and physical retirement. Production/fixture packaging separation was checked with the existing verifier.
- Current `validateBacklogIndex` passes. ADK is unavailable; the legacy full-backlog validator rejects unrelated post-migration schemas and is not passing current-backlog evidence.
- Default-locale full build exposed the unchanged web-demo fixture's locale limitation; recorded separately as [issue-20261018-004800](issue-20261018-004800-make-vulnerable-web-network-ceiling-evidence-locale-independ.md). The focused C-locale control passes. Final C-locale full build, committed container aggregate and broad branch review remain pending at this checkpoint; this item is not closed or archived.

---

**Verification:** `./gradlew consumerHarnessTest --tests '*Koog*'`, focused published-only `verifyFreshNative`, `./gradlew build`, and `./scripts/run_containerized_tests.sh :optionalIntegrationCheck --no-configuration-cache --max-workers=1`. Run the existing backlog validator without changing unrelated issues; distinguish legacy-validator incompatibility from current ADK validation.

<!-- id: issue-20261017-230729  file: issue-20261017-230729-add-sandboxescapebench-inspired-koog-containment-contracts.md -->
