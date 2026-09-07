---
title: "Forbid silent else on sealed lifecycle and security machines"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "testing"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorNotificationMachine.kt"
  - "config/detekt/detekt.yml"
target_symbols:
  - "JvmVerdict"
verify_cheap:
  - "./gradlew :enforcer:detektMain"
  - "./gradlew :enforcer:test --tests io.mazewall.ArchitectureTest"
needs_kernel: false
core_lock: true
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: skipped
has_side_effects: true

paperclip_issue_id: "b152d7bb-5814-47b2-ae38-aec82beccc0f"
paperclip_identifier: "MAZ-1168"
---

# 🟡 [Severity: MEDIUM]: Forbid silent else on sealed lifecycle and security machines

**Context:**
Kotlin already makes a sealed `when` a compile error when a variant is added — but only if there is no `else`. Detekt `ElseCaseInsteadOfExhaustiveWhen` (`detektMain`, type-resolved) is the repo-wide gate; the four-file `SealedWhenExhaustivenessTest` scanner is gone. Remaining hole: `JvmVerdict` parses from `Int` with `else -> null`, which Detekt cannot exhaust because the subject is not sealed.

**Progress:**
- ✅ Detekt enabled for library modules; `ElseCaseInsteadOfExhaustiveWhen` is severity error; `:tools` skips ktlint and Detekt
- ✅ Silent sealed `else` removed from `PureJavaBpfEngine`, `LandlockState`, `SupervisedOpen` resolve, `SeccompAction.toKernelReturnCode`, `AttributionCollector`, portal `encodeProperty`
- ⏳ `JvmVerdict` still parses `Int` with `else -> null`
- ⏳ `ArchitectureTest.sealedSecurityOutcomesHaveAClosedSubclassSet` may still miss types

**Needed:**
1. Replace `JvmVerdict` `else -> null` with an explicit parse of known integers; unknown values must be a reject/abort path, not a silent null.
2. Add any missing sealed security types to `ArchitectureTest.sealedSecurityOutcomesHaveAClosedSubclassSet` in the same commit as the subtype list change.
3. Run `./gradlew :enforcer:detektMain` and `./gradlew :enforcer:test --tests io.mazewall.ArchitectureTest`.

## Side effects
- Adding a sealed subtype without updating when branches is a Detekt error on `detektMain`

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-080201  file: issue-20260907-080201-forbid-silent-else-on-sealed-lifecycle-and-security-machines.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
