---
title: "Make portal-codegen stubs reviewable with named method IDs"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies:
  - "issue-20260907-080845"
component: "docs"
target_modules:
  - ":portal-codegen"
target_files:
  - "portal-codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt"
  - "portal-codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalStubGeneratorTest.kt"
target_symbols:
  - "PortalStubGenerator"
verify_cheap:
  - "./gradlew :portal-codegen:test --tests io.mazewall.portal.codegen.PortalStubGeneratorTest"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "bf000fd5-78e4-4fd2-a094-1d78ebb3f126"
paperclip_identifier: "MAZ-1186"
---

# 🟡 [Severity: MEDIUM]: Make portal-codegen stubs reviewable with named method IDs

**Context:**
portal-codegen emits uncommented `when (methodId)` stubs whose IDs are truncated hashes and whose granted FDs are positional `granted[i]`. Tests assert KotlinPoet `toString()`, not reviewed generated sources. A security pass cannot read the generated dispatcher as a protocol spec.

**Needed:**
1. Emit named `PortalMethod` constants (or comments with the interface method name) next to each `methodId` branch. Do not leave bare hashes.
2. Name granted FD slots from the interface, not `granted[i]`.
3. Snapshot generated Kotlin (or assert on a `FileSpec` write to a reviewed fixture) instead of only `toString()` contains-checks.
4. Run `:portal-codegen:test`.

## Side effects
- Generated when(methodId) stubs gain named constants; tests assert generated sources not FileSpec toString

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

**Resolution evidence (2026-09-07):** generated host and worker sources define method-name-based
constants, construct `PortalMethod.Generated` at the wire boundary, and emit named capability-slot
comments next to indexed granted FDs. The generator tests inspect rendered Kotlin. `./gradlew
:portal:test :portal-worker:test :portal-codegen:test` passed.

<!-- id: issue-20260907-081112  file: issue-20260907-081112-make-portal-codegen-stubs-reviewable-with-named-method-ids.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
