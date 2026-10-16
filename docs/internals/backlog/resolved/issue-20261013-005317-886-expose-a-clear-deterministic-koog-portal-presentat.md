---
title: "Expose Koog Portal features through readable JUnit showcase tests"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "demos"
target_modules: []
target_files:
  - "demos/koog-contained-tool"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Expose Koog Portal features through readable JUnit showcase tests

**Context:**
uppercase_ascii, generated service metadata, opaque worker packaging and real native denial/crash/cancellation controls are implemented, but the demo README points to tests and packaging rather than a simple presenter command. Test-only denied/crash services must not enter the advertised model registry.

**Needed:**
1. Add presentation-tagged JUnit tests using the actual upstream registry and prepared generated operation; assert and print original/transformed Unicode text, actual worker identity/readiness and physical close, and keep negative native fixture probes separately labeled and packaged. Reuse existing fixtures; no new demo main or shell command.

**Acceptance:** Text aBc éß🙂 produces ABC éß🙂 through the actual Koog adapter/generated protected service. Host guest-class loading remains impossible; launch/readiness and distinct live child identity are observed. Text/UTF-8/wire bounds reject. Worker death and temporary-resource cleanup complete. Test-only denied/crash probes are shown as separate controls, not added to the advertised production registry.

## Resolution evidence

Current producer `4edb465fd2cc014f5c9b1af886dd4f0931271172` passes the actual
source-bound `optionalIntegrationCheck` in 9m59s with zero owning failures/skips.
It executes `PortalPresentationTest` through the real two-stage source workflow,
then the strict published-only native consumer: upstream registry, Unicode/UTF8
bounds, distinct ready child, opaque guest classpath, actual denial/crash/cancel,
immutable ready/closed diagnostic identity and physical retirement. Only
`uppercase_ascii` is advertised. The production and separate native-fixture package
variants additionally pass the existing bootstrap fault suite (`run-9fba04cdb40f`).
See resolved `issue-20261016-101505-976-qualify-optional-integrations-and-expose-read-only`
for complete producer/source/gate identities. Human timing/comprehension and
production authority remain separate prerequisites.

---
<!-- id: issue-20261013-005317-886-expose-a-clear-deterministic-koog-portal-presentat  file: issue-20261013-005317-886-expose-a-clear-deterministic-koog-portal-presentat.md -->
