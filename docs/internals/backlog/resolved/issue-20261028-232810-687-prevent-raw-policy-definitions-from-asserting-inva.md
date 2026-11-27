---
title: "Prevent raw policy definitions from asserting invalid process scope"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/PolicyDefinition.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/Policy.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/PolicyBuilder.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ProcessPolicyBuilder.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/FileSystemPolicy.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/CanonicalPolicyArtifact.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/artifact/CanonicalPolicyJson.kt"
  - "enforcer/src/test/kotlin/io/mazewall/PolicyBuilderContractTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/CanonicalPolicyArtifactTest.kt"
target_symbols:
  - "PolicyDefinition"
open_questions: false
---

# 🟡 [Severity: HIGH]: Prevent raw policy definitions from asserting invalid process scope

**Context:**
Host-only external Kotlin consumer constructs PolicyDefinition<PolicyScope.ProcessWideSafe>(enforceLandlock=true); ProcessPolicyBuilder.base/build accepts it, while CanonicalPolicyArtifact.process rejects worker filesystem intent. javap also confirms the Kotlin-internal Policy constructor is JVM-public. This is a construction-contract gap, not a demonstrated kernel containment bypass. Related prior issue: issue-20261006-155730-233-replace-scope-promoting-builders-with-fixed-proces.md.

**Needed:**
1. Centralize process-scope validation at construction and process base entry points; cover raw constructors, copy and Java access, not only builder method availability. Add Kotlin/Java negative consumer checks and valid worker/process tests. Any removal or restriction of supported public constructors requires explicit API migration approval; coordinate with the existing scope issue.

**Design-review follow-up (2026-11-03, finding 4):**
- `ProcessPolicyBuilder.kt:30-32` accepts the raw process-typed definition, while `CanonicalPolicyArtifact.kt:21-30` separately rejects worker filesystem intent. A caller-selected generic marker cannot establish that invariant.
- Establish scope through validated construction and carry filesystem intent using the existing `Unrestricted`, `DenyAll` and `AllowResources` alternatives. Avoid representing disabled enforcement with independent nonempty grants; compatibility properties can project from the validated value.
- Preserve runtime/JVM compatibility checks and installation-time kernel validation. Include negative constructor/copy/builder-base/Java cases and valid worker/process cases; this construction correction does not establish a kernel containment bypass.

---
## Progress — 2026-11-27

Fail-closed process-scope validation is in place without removing the public constructor:

- `ProcessPolicyBuilder.base` and `build` reject path grants and active-empty `enforceLandlock`.
- A process-built definition keeps that claim across `copy`; adding filesystem intent throws.
- `installOnProcess` and `assessOnProcess` reject the same intent before Landlock or preflight.
- Java `ProcessPolicyBuilder.base` and process `copy` are covered. Worker `DenyAll` and path grants still build.
- Canonical process artifacts, `ProcessFilterInstaller`, and host-baseline checks use the same predicate.

The raw constructor still accepts filesystem fields. The type argument is erased, so it cannot tell a worker definition from a process-typed one; banning paths there would also reject valid worker policies. Process claim, `ProcessPolicyBuilder.base`, copy of a claimed process definition, and process install/assess reject that intent.

## Resolution Evidence (2026-11-27)

- Process policies cannot carry worker filesystem intent. `ProcessPolicyBuilder.base`/`build`, copy of a process-claimed definition, `installOnProcess`, `assessOnProcess`, canonical process artifacts, and `ProcessFilterInstaller` reject `enforceLandlock` or a nonempty path set before Landlock.
- Worker policies still carry path grants and Landlock. The public constructor was not banned: `PolicyDefinition<ProcessWideSafe>` and `PolicyDefinition<ThreadLocalOnly>` are the same JVM constructor, so a constructor ban would also reject valid worker policies.
- Host `./gradlew build` passed in 6m 42s (281 tasks). The confined kernel lane in `scripts/run_local_build.sh` then verified confined-helper mounts, identity, namespace, and ownership.
- LL-01 documentation and ABI8 qualification were not done. Operator closed this construction slice and left that qualification on the existing platform-envelope owner.

## Repository-review expansion — 2026-11-04

[BPF-05](../../reviews/2026-11-04-repository-review-findings.md#bpf-05) confirms that public
path fields alone suffice for forged process filesystem intent; setting an internal property
is unnecessary. Reject raw/copy/base/Java and active-empty invalid scope at the direct process
installation boundary **before Landlock effects**. Canonical-import rejection alone is insufficient.
Astra's connected ABI8 recipe uses parked siblings, a shared Seccomp baseline, narrower local
Landlock, then forged process Landlock and stale deduplication: caller-domain TSYNC can replace
the sibling's narrower domain. This is conditional source analysis, not an executed native
bypass or a new valid-scope supported-path finding. Preserve ManagedRuntime's baseline guard.
[LL-01](../../reviews/2026-11-04-repository-review-findings.md#ll-01) also requires correcting
unconditional monotonicity/flags-zero prose and qualifying baseline-before-local-domain ordering.

---
<!-- id: issue-20261028-232810-687-prevent-raw-policy-definitions-from-asserting-inva  file: issue-20261028-232810-687-prevent-raw-policy-definitions-from-asserting-inva.md -->
