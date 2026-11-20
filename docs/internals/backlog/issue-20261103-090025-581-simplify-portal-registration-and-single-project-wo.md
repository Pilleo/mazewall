---
title: "Simplify Portal registration and single-project worker packaging"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261103-095306-294-validate-complete-portal-dto-shapes-before-source-"
  - "issue-20261103-095306-992-fingerprint-the-complete-versioned-portal-wire-sch"
  - "issue-20261103-095309-827-reject-overlapping-portal-generated-output-destina"
  - "issue-20261103-101536-305-expose-and-validate-aggregate-portal-capacity-befo"
component: "portal-codegen"
target_modules:
  - ":portals:codegen"
  - ":portals:runtime"
target_files:
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalCodegenPlugin.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalManifestGenerator.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalDiagnosticTasks.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalServiceSpec.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalConventionsFunctionalTest.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntimeConfig.kt"
  - "tests/consumers/portal-plugin"
  - "tests/consumers/README.md"
  - "docs/guides/portal-service-isolation.md"
target_symbols:
  - "PortalCodegenExtension"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Simplify Portal registration and single-project worker packaging

**Context:**
Portal developer-experience plan P02: infer mechanical registration fields and compile API, host and worker source sets in one consumer project while preserving explicit instance mode and sandbox/resource choices.

**Needed:**
1. Add an opt-in named services container and `portalApi`/`portalWorker` conventions, deriving only mechanical identifiers and compilation wiring. Keep instance mode, policy profile and intentional grouping explicit.
2. Add the explicit `PortalRuntimeConfig.loadBaseline()` convenience for the reserved `portal-compute` profile and the design's finite defaults; retain existing entrypoints and missing-profile rejection.
3. Deliver complete Java-authored and Kotlin-authored single-project consumers, diagnostic output and public setup guidance.

**Plan and dependencies:**
- [Implementation plan P02](../../superpowers/plans/2026-11-03-portal-developer-experience.md) and [build/profile contract](../designs/portal/portal-developer-experience.md#3-build-conventions-and-explicit-policy-selection).
- Follows CG02/CG03/CG07 from the [codegen hardening review](../reviews/2026-11-03-portal-codegen-hardening.md): reuse the validated schema and corrected output publisher. Coordinate with the existing [worker packaging separation issue](issue-20261028-230731-061-separate-production-portal-worker-packaging-from-p.md); its dependency-closure changes remain separately owned.
- Use [P12](issue-20261103-101536-305-expose-and-validate-aggregate-portal-capacity-befo.md)'s runtime capacity report/budget in examples. Build-only diagnostics show group topology and label runtime-selected limits unresolved; they do not execute application config.

**Acceptance:**
- `build`, `portalCheck` and `portalDescribe` work without handwritten API-JAR wiring. A guest compile error fails ordinary build.
- Explicit existing registrations/source sets still work. Conflicting declarations reject; host implementation/runtime dependencies cannot enter the worker implicitly.
- Clean, repeated, relocated and configuration-cache builds preserve atomic generated outputs, deterministic bundles and portable metadata. Deleted registrations remove stale output.
- Missing bundles/profiles and unsupported values produce actionable service/group diagnostics. No guest class becomes host-loadable.
- Diagnostics describe validated method IDs, full field paths, schema/codec version, limits and artifact roles from the shared model; payload values are not logged.
- The baseline is opt-in: one worker per group, 64 admissions, `PortalWorkerProfile()` with the launcher's existing 64 MiB heap maximum and existing protocol/deadline caps. This corrects the earlier incompatible 256 MiB heap-only proposal. Custom heap/cgroup memory limits remain paired and require delegation; heap size is not an RSS/CPU/cgroup guarantee.

**Verification:**
- [Junior implementation walkthrough: P02](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#p02--build-conventions-and-onboarding) gives fixture order, build-wiring controls and independent Java/Kotlin onboarding checks.
- Run `./gradlew :portals:codegen:test --tests '*PortalConventionsFunctionalTest'`, affected runtime config tests and strict staged-plugin consumer gates from `tests/consumers/README.md`.
- Run `./gradlew build`, backlog validation and whitespace checks; preserve existing public constructors/default bridges and review lock changes if build declarations change.

---
## Repository-review acceptance expansion — 2026-11-04

[PCG-09](../reviews/2026-11-04-repository-review-findings.md#pcg-09): the current bundle
action accepts 257 tiny distinct JARs while verified runtime materialization correctly rejects
them at 256. Enforce the existing entry/aggregate/compressed-byte bounds before successful
publication and stream hashing. Actual portalCheck must reject over-limit inputs and accept
consumable exact-limit controls. Portable-packaging162 owns PCG-08 cache equivalence; this P02
must consume it. Also include PRT-05/PCG-11 legal Object-name overloads and ARCH-02's supported
binary bridges in final plain-consumer acceptance. No second codec/fingerprint framework.

---
<!-- id: issue-20261103-090025-581-simplify-portal-registration-and-single-project-wo  file: issue-20261103-090025-581-simplify-portal-registration-and-single-project-wo.md -->
