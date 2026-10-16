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

## Diagnostics checkpoint

- Added the maintained [read-only diagnostics contract](../designs/core/execution-diagnostics.md).
  The ABI adds only immutable diagnostic models/source and the domain observation
  method; existing declarations remain present. Prepared Portal operations retain
  their original package-private constructor and expose no public constructor.
- Tests first rejected missing diagnostics APIs (`run-2c3ada1e33b9`,
  `run-a14c88eafef6`, `run-3fbb61d35d46`, `run-f0cc3079302a`,
  `run-bff2663b2c9b`). A strengthened real-thread test also failed when polling
  incorrectly certified retirement before the canonical drain boundary
  (`run-da2b17746903`, exact assertion `run-c32ba707c3ae`). The guarded observation
  then passes (`run-2b42d37fd32a`).
- Native Portal checks observe the actual launch/readiness input tuple, reject
  publication after failed readiness, preserve held retirement/admission, and
  retain post-close views. Actual scoped checkpoints pass (`run-0b3655ff68e7`,
  `run-c2d454fd5ca1`).
- The final checkpoint includes **66 selected core ownership/architecture cases,
  148 Portal host cases, 5 selected Portal native cases, 6 Koog cases and 103 Spring
  cases**, all with zero failures/errors/skips. Additive ABI checking, Spring
  static analysis and the unchanged Detekt warning ratchet pass. Existing separate
  optional native prerequisites are not relabeled as passes.
- Health/read endpoints are independently optional, explicit-property enabled,
  immutable and passive over existing singleton sources. Actual context tests
  cover absent APIs, disabled/default behavior, independent API absence, user
  override and no lazy-source initialization. Health rejects unknown, failed,
  draining and closed observations and never masks source failures.
- Strict optional-module locks are generated; the six added optional Boot4.1.1 /
  managed Jackson-annotation artifacts match independent Maven Central bytes
  (`run-d95e29bf5a9e`, `run-8b23216f2393`). No existing checksum changes or dependency
  version drift are approved. Root lock changes only add the actual consumer
  verification configuration association for existing versions.

## CI, publication and adoption implementation

- Added a dedicated optional job, actual full-adapter build/cache-reuse steps and
  `optionalIntegrationCheck`. The latter compiles as ordinary test code and remains
  excluded from the host-safe harness (`run-854dd7c90830`). It uses existing owned
  command/clone/package/Boot fixtures, pins committed producer inputs, stages a
  fresh exact local version, verifies constructor/public observation visibility,
  rejects malformed version namespaces, preserves historical locks, and executes
  real JAR-only native Koog, JDBC/Security and finished-Boot consumers.
- The existing required core job uses an always-run success guard on optional
  acceptance; optional failure cannot skip into a green required job. Workflow
  parsing/dependency checks passed (`run-eda4fb43118a`). Existing master publication
  selects both optional library flags and bootstrap-only Koog selection, preserving
  credential bindings and excluding demo/denied fixture publications.
- The exact proposed CI host build passes (`run-60279924664d`) and its unchanged
  second invocation reuses configuration cache (`run-4a89964f18e5`). Actual standalone
  consumer parsing rejects `../escape` before resolution/lock writes
  (`run-abcba477f664`). The publication gate additionally checks this regression.
- Updated guides remove stale coordinator-patch/retired-shell/private-candidate
  instructions. Existing JUnit stories now assert real ready/held-draining/closed
  diagnostics and optional Actuator absence against published artifacts. Reference,
  anchor and Bash-syntax checks passed for 18 relative links (`run-3c3e28979da7`).
- Full source-bound native/package acceptance and the final included-module build
  remain completion gates; implementation wiring is not claimed as a native pass.

## Cold-gate and connected-review corrections

- The first exact-publication run failed closed in real producer compilation
  (`pty_b9becef0`, retained evidence under
  `/tmp/mazewall-consumer-bootstrap-18206841922636170268/checkout/optional-integration-evidence`).
  Shared fixtures forecast friend JAR names before the staging init script changed
  versions; actual platform/enforcer JARs had the fresh candidate names. Root
  friend inputs now bind the actual Jar task archive providers after project
  configuration. Actual renamed-candidate shared compilation passes
  (`run-bdc0104cadcd`). No internal declaration is widened or test skipped.
- Connected review confirmed that Spring7.0.9's `getSingletonNames` includes early
  factories and `getSingleton` can invoke them (`run-3b8176639b7f`). A real registry
  regression failed when polling invoked an early factory (`run-fc03daf2aed5`).
  Sampling now requires `containsSingleton` before lookup; the owning regression
  passes with the renamed-candidate check above.
- Actual external javac shows the added package-private overload changes the
  diagnostic envelope to `compiler.err.cant.apply.symbols`, with the same explicit
  package-access cause (`run-a4580779ec5b`). The consumer now independently rejects
  both legacy and observed constructor signatures, still requiring exactly one
  error and actual package-access evidence, plus positive public-facade compilation.

---
<!-- id: issue-20261016-101505-976-qualify-optional-integrations-and-expose-read-only  file: issue-20261016-101505-976-qualify-optional-integrations-and-expose-read-only.md -->
