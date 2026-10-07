---
title: "Enforce exact nested domain transitions and retain observed invocation failure"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [enforcer, profiler]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/Mazewall.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/InvocationEvidence.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/ExecutionTransitionGuard.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/RuntimeWorkerContext.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedInvocation.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedDomain.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedRuntimeTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/ExecutionTransitionGuardTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedRuntimeJavaTestSupport.kt"
  - "enforcer/src/test/java/io/mazewall/MazewallJavaApiTest.java"
  - "profiler/src/test/java/io/mazewall/profiler/ProfilerJavaApiTest.java"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/OwnedRuntimeNativeTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt"
  - "enforcer/api/enforcer.api"
  - "enforcer/README.md"
  - "docs/internals/designs/enforcer/containment-design.md"
  - "tests/consumers/README.md"
  - "tests/consumers/java-api/build.gradle"
  - "tests/consumers/java-api/gradle.lockfile"
  - "tests/consumers/kotlin-api/gradle.lockfile"
  - "tests/consumers/java-api/src/main/java/consumer/JavaApiConsumer.java"
  - "tests/consumers/kotlin-api/src/main/kotlin/consumer/KotlinApiConsumer.kt"
  - "tests/consumers/java-api/src/ambiguity/java/consumer/UncastCallable.java"
  - "tests/consumers/java-api/src/callable/java/consumer/UncastCallable.java"
target_symbols: [Mazewall, ExecutionTransitionGuard, InvocationEvidence, RuntimeWorkerContext]
open_questions: false
---

# 🟡 [Severity: HIGH]: Enforce exact nested domain transitions and retain observed invocation failure

**Context:**
Approved C8/C9 foundation: synchronous Callable entry dispatches from host, runs inline only in the exact active domain, rejects unknown/different-domain transitions, and retains library-observed nested containment failure even when business code catches it. Reuse the owned runtime and existing classifier; metadata never grants native authority.

**Needed:**
1. Verify same-worker inline nesting, cross-domain refusal, context cleanup and retained caught containment evidence before introducing canonical Callable entry.

## Implementation and verification evidence

The legacy top-level JVM facade is replaced by the canonical synchronous `Mazewall.callContained(runtime, domain, Callable)` entry, without Supplier/Runnable aliases or implicit executor ownership. Existing policy recipes remain on their canonical policy owners. Remaining enforcer/profiler Java fixture callers and independent packaged Java/Kotlin consumers are migrated rather than preserving facade shims.

The pure closed transition guard dispatches only from an unbound host, permits inline execution only in the exact active domain, and rejects other/unknown scopes before entry. A logical invocation context exists only around its body and is cleared in finally. Read-only evidence retains library-observed nested containment or transition failure; a business catch cannot turn that observed unsuccessful operation into successful delivery. Evidence uses the existing classifier and does not attest swallowed/unobserved kernel activity or upgrade uncertain permission provenance.

The initial API baseline was missing (`run-9282a48de72a`). Removing the retained-root-failure gate independently made caught containment and cross-domain refusal regressions fail (`run-e9016a49dec6`); restoring it passed. Host suites, exact sealed outcomes, ABI and unchanged quality budget passed (`run-c5ac0228b333`). Actual-kernel owned-runtime acceptance now includes caught nested fresh-file denial remaining unsuccessful.

`./gradlew build integrationTest integrationTestFreshJvm` passed in 4m41s: 272 tasks (83 executed, 189 up-to-date), `run-ef6fdb480616`. Serial full mapped Docker passed in 58s (`run-55076c5101ae`). Independent staged candidate publications based on `5c369da1` plus this diff passed real Java/Kotlin builds, scope negatives and uncast Callable compilation (`run-1768cd6ecc52`); version `0.1.0-baseline.5c369da1.c8-candidate` is explicitly candidate evidence, not a final-source release pin.

Framework-specific context bridges, artifact bindings and remaining old low-level caller-owned surfaces retain their own migration/release gates. This issue remains open for repository review.

---
<!-- id: issue-20261007-111005-986-enforce-exact-nested-domain-transitions-and-retain  file: issue-20261007-111005-986-enforce-exact-nested-domain-transitions-and-retain.md -->
