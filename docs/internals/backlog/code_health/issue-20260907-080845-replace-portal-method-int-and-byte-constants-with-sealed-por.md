---
title: "Replace portal method Int and Byte constants with sealed PortalMethod"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "docs"
target_modules:
  - ":portal"
  - ":portal-codegen"
  - ":portal-worker"
target_files:
  - "portal/src/main/kotlin/io/mazewall/portal/PortalFrame.kt"
  - "portal-codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt"
  - "portal-worker/src/main/kotlin/io/mazewall/portal/worker/PortalBuiltinDispatch.kt"
  - "portal/src/test/kotlin/io/mazewall/portal/PortalFrameTest.kt"
target_symbols:
  - "PortalFrame"
verify_cheap:
  - "./gradlew :portal:test --tests io.mazewall.portal.PortalFrameTest"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "cd95e9c6-b94b-4eb2-8403-adcd54354ff9"
paperclip_identifier: "MAZ-1172"
---

# 🟡 [Severity: MEDIUM]: Replace portal method Int and Byte constants with sealed PortalMethod

**Context:**
Portal, codegen, and worker wire `PortalKind` as `Byte` constants and `PortalMethods` as `Int`. Frames, `ProcessBroker.invoke`, builtin dispatch, and generated stubs all `when (methodId: Int)` with `else -> error`. `encodeProperty` uses `else` on sealed `BoundaryTypes.Kind`. The wire can stay numeric; Kotlin should not.

**Needed:**
1. Introduce a sealed `PortalMethod` (and `PortalKind` if it is not already sealed) with the on-wire `Int`/`Byte` as a property. Parse once at the frame boundary.
2. Replace `when (methodId: Int)` in `ProcessBroker`, `PortalBuiltinDispatch`, and codegen stubs with exhaustive `when (method)`.
3. Make `encodeProperty` exhaustive on `BoundaryTypes.Kind` with no `else`.
4. Keep the numeric encoding on the frame; do not change the portal protocol layout.
5. Run `:portal:test`, `:portal-worker:test`, and `:portal-codegen:test`.

## Side effects
- Wire methodId stays numeric on the frame; Kotlin dispatch uses a sealed PortalMethod

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-080845  file: issue-20260907-080845-replace-portal-method-int-and-byte-constants-with-sealed-por.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
