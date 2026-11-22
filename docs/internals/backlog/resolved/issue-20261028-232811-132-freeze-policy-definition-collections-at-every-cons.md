---
title: "Freeze policy definition collections at every construction boundary"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/PolicyDefinition.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/PolicyBuilder.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/CanonicalPolicyArtifact.kt"
  - "enforcer/src/test/kotlin/io/mazewall/PolicyBuilderContractTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/CanonicalPolicyArtifactTest.kt"
target_symbols:
  - "PolicyDefinition"
open_questions: false
---

# 🟡 [Severity: HIGH]: Freeze policy definition collections at every construction boundary

**Context:**
Host-only reproduction: direct construction and a built definition copy(syscallActions=mutableMap) retain the caller map. Changing GETPID from ACT_ERRNO to ACT_ALLOW changes both definitions isSyscallAllowed answers. Builder frozenCopy does not protect raw constructor or generated copy inputs. Related prior issue: issue-20261006-021323-433-preserve-active-filesystem-intent-and-immutable-po.md.

**Needed:**
1. Own immutable snapshots of all collection inputs at the value boundary, including copy, composition and imported definitions; reuse the existing owned-value pattern. Test mutation of constructor/copy inputs and Java-visible collections, preserving filesystem intent and equality. Review API compatibility before changing data-class surface; coordinate with the existing snapshot issue.

**Design-review follow-up (2026-11-03, finding 3):**
- `PolicyDefinition.kt:23-40` accepts collection references, whereas `:182-190` freezes only callers that explicitly invoke `frozenCopy`. Immutability must be a property of every constructed value, not knowledge each caller needs to retain.
- Reuse the owned-collection approach already present in `FileSystemPolicy.AllowResources`. Preserve equality, hash stability and constructor/copy compatibility through an approved migration if changing the public data-class surface is necessary.
- Extend the existing builder snapshot checks to raw construction and copy inputs, including syscall maps, filesystem sets and diagnostic lists. Mutating any source collection or Java-visible view must not change a published policy.

---
## Repository-review revalidation — 2026-11-04

[BPF-04](../../reviews/2026-11-04-repository-review-findings.md#bpf-04) freshly reproduces
raw collection mutation and traces copy/cache-key exposure. Builder/canonical/owned snapshots
are already frozen; kernel-installed programs do not mutate. Cover every raw/copy collection,
Java-visible view and stable cache identity under the existing compatibility decision.

---
## Resolution Evidence (2026-11-21)

- Converted `PolicyDefinition` (`enforcer/src/main/kotlin/io/mazewall/enforcer/api/PolicyDefinition.kt`, shared with `:lockdown` via `PrepareLockdownCoreSources`) from `data class` to `class` owning defensive `java.util.Map.copyOf`, `java.util.Set.copyOf`, and `java.util.List.copyOf` snapshots for all 5 collection properties (`syscallActions`, `allowedFsReadPaths`, `allowedFsWritePaths`, `customViolationPhrases`, `customViolationRegexes`) at every construction and `copy` boundary.
- Implemented `component1`–`component11` (`internal operator fun component9` preserving `enforceLandlock` internal visibility), `copy`, `equals`, `hashCode`, and `toString` with zero binary ABI changes (`enforcer/api/enforcer.api` and `lockdown/api/lockdown.api` diffs empty), and updated `config/detekt/detekt.yml` `LongParameterList` excludes (`**/PolicyDefinition.kt`) matching the prior `ignoreDataClasses: true` exemption.
- Added Kotlin and Java regressions in `enforcer/src/test/kotlin/io/mazewall/PolicyBuilderContractTest.kt` and `enforcer/src/test/java/io/mazewall/MazewallJavaApiTest.java` covering raw constructor & `copy` snapshot immutability, Java-visible unmodifiable views, and `PolicyCompilationCache` key stability after caller mutation.
- Verified sequentially: `./gradlew :enforcer:ktlintCheck :lockdown:ktlintCheck :enforcer:detektMain :lockdown:detektMain :detektWarningRatchet :enforcer:checkKotlinAbi :lockdown:checkKotlinAbi :verifySupportedApiSurface :verifyPackageTopology --no-daemon --console=plain` (`65` tasks, passed), `./gradlew :enforcer:ktlintTestSourceSetCheck :enforcer:test --tests io.mazewall.enforcer.api.PolicyBuilderContractTest --tests io.mazewall.enforcer.api.CanonicalPolicyArtifactTest --tests io.mazewall.MazewallJavaApiTest :lockdown:test --no-daemon --console=plain` (`PolicyBuilderContractTest` `6`, `CanonicalPolicyArtifactTest` `6`, `MazewallJavaApiTest` `13`, `:lockdown:test` `17`, all `0` failed/`0` skipped).

- Full completion gate verified in commits `52e81d2e1` and `7e62bacf7` (`./gradlew build -PmazewallKernelLane=BASELINE` and rootful Docker `CONFINED` kernel lane passed).
---
<!-- id: issue-20261028-232811-132-freeze-policy-definition-collections-at-every-cons  file: issue-20261028-232811-132-freeze-policy-definition-collections-at-every-cons.md -->
