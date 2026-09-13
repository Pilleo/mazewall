---
title: "Version protocol golden fixtures and remove duplicated cache guarantees"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - "profiler"
target_files: 
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/ControlProtocolFixtureTest.kt"
target_symbols: 
  - "ControlProtocolFixtureTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Version protocol golden fixtures and remove duplicated cache guarantees

**Context:**
Golden transcript updates record current behavior but do not prove compatibility for supported older peers, and the raw-stack-cache guarantee is duplicated in the design document.

**Needed:**
1. Freeze fixtures per supported protocol version and retain one normative cache guarantee referenced by the other design section.

## Acceptance criteria

1. Committed transcripts are partitioned by supported protocol version and include success and failure paths.
2. A fixture update cannot silently redefine compatibility; a wire-visible change requires an explicit version decision.
3. Older supported transcripts replay byte-identical replies through `ControlProtocol` and `ControlSessionMachine`.
4. The Tier E design contains one normative raw-stack-cache guarantee, with all other references linking to it.

## Required verification

1. Replay every supported-version fixture and retain exact request/reply bytes and terminal states.
2. Add a compatibility test that rejects same-version reply drift and requires an explicit version update for intentional changes.
3. Run a documentation link/duplicate check and report `dictionary_integrity`, `lifecycle_validity`, and `transport_completeness`.

Verification command: `./gradlew :profiler:test --tests io.mazewall.profiler.tierE.daemon.ControlProtocolFixtureTest`.

---
<!-- id: issue-20260913-024414-875-version-protocol-golden-fixtures-and-remove-duplic  file: issue-20260913-024414-875-version-protocol-golden-fixtures-and-remove-duplic.md -->
