---
title: "Qualify optional integrations and expose read-only execution diagnostics"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - ":enforcer"
  - ":portals:runtime"
  - ":integrations:koog"
  - ":integrations:spring:mazewall-spring-boot"
  - ":integrations:spring:mazewall-spring-boot-starter"
target_files:
  - "integrations"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution"
  - "portals/runtime"
  - "buildSrc"
  - "gradle/verification-metadata.xml"
  - ".github/workflows/ci.yml"
  - "demos/koog-contained-tool"
  - "src/consumerVerification"
  - "tests/consumers"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Qualify optional integrations and expose read-only execution diagnostics

**Context:**
Operator approved all four follow-ups after PR533: fix Koog strict dependency verification and Spring configuration-cache capture, add optional integration CI/publication, refresh guides/showcases, and implement read-only lifecycle/admission/cancellation/retirement/identity diagnostics with optional Spring Actuator exposure.

**Needed:**
1. Implement the approved staged plan in the current checkout with strict dependency provenance, existing ownership semantics, focused regressions, packaged consumers and complete included-module gates.

## Scope and constraints

- Baseline: merged PR533, `628983edfeb3181bfee605ec91824da6a6c907bc`.
- Operator approved all four recommendations, including optional Actuator API
  dependencies. Keep them compile-only/conditional, following the existing optional
  Security pattern; no new required framework dependency in core or ordinary starter.
- Execute inline in the existing checkout. No new worktree, agent delegation,
  automatic GitHub merge, or publication of raw benchmark/command receipts.
- Read-only diagnostics expose finite lifecycle, actual generation identity and
  owner-observed admission/cancellation/delivery/end counters. They cannot install,
  start, admit, retry, cancel, drain, retire, or certify containment. Exclude payloads,
  resource paths, environment and exception text.
- Preserve result-versus-physical-end semantics, explicit process baseline, required
  native installation, strict verification, historical artifacts/locks, public
  constructor compatibility and all existing quality/security gates.
- Codanna impact evidence for `ManagedRuntime` is PARTIAL (`CODANNA_TIMEOUT`,
  `run-4312f1b5f5de`); direct connected-code inspection supplies caller evidence.

## Build regression evidence

- Actual Spring Test configuration reproduced the Project-capture configuration-cache
  failure inside a new real Gradle/TestKit fixture (`run-0712dc365d9b`). The fixture
  also compiles an external public consumer and rejects a test-only class, so merely
  making the cache reusable cannot hide test-output leakage.
- The provider correction passed that mandatory fixture and existing buildSrc
  contracts (`run-a15cdab87220`). The four missing pinned artifacts match fresh
  Maven Central bytes and local-cache bytes; JVM metadata's legitimate parent link
  and advertised JAR SHA256 were independently checked (`run-d83746c87df3`).
- Both actual optional builds now pass with strict verification and fatal
  configuration-cache checks (`run-4606ca57f962`): **5 Koog and 93 Spring tests,
  no failures/errors/skips**, plus Spring static analysis. The identical invocation
  then reuses the configuration cache (`run-c95b08fe9dc4`). Existing checksum
  approvals and coordinates are preserved.

---
<!-- id: issue-20261016-101505-976-qualify-optional-integrations-and-expose-read-only  file: issue-20261016-101505-976-qualify-optional-integrations-and-expose-read-only.md -->
