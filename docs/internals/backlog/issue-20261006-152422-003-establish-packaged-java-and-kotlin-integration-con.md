---
title: "Establish packaged Java and Kotlin integration consumer baselines"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "tests/consumers"
  - "platform/build.gradle.kts"
  - "enforcer/build.gradle.kts"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Establish packaged Java and Kotlin integration consumer baselines

**Context:**
C0 requires external dependency resolution and public API compilation before canonical API migration.

**Needed:**
1. Stage current publications and compile independent Java and Kotlin consumers; preserve ambiguity evidence.

## Evidence (6 October 2026)

- Base source revision: `d3e11915`; review snapshot: `98da39f7f71b64a14633ab3f19046f6bc2afc0e3`.
- Independent Gradle fixtures resolve only staged Maven artifacts for MazeWall, with no project substitution or source-classpath injection.
- Initial staging selected `:platform:publishAllPublicationsToConsumerBaselineRepository` successfully but published nothing: platform had no publication. Consumer compilation then failed resolving the required platform artifact.
- Added the same `mavenJava`/Java-component publication pattern already used by enforcer.
- Single-dependency Java consumer next failed because `Syscall` was absent from compileClasspath. Enforcer's public API exposes platform types, but its project dependency was `implementation`. Applied `java-library` and changed the existing project dependency to `api`; no dependency coordinate was added.
- After restaging, Java and Kotlin construction/rule-inspection programs passed with only `io.mazewall:enforcer` declared. The separate javac characterization reproduced `compiler.err.ref.ambiguous` for the uncast Callable/Supplier lambda against the packaged artifact.
- Upstream Koog `agents-tools:1.3.0` and Spring Boot `4.1.1` BOM dependency graphs resolved. Koog uses serialization `1.10.0`, versus core `1.9.0`; optional adapter compatibility is still unverified.
- The dependency-probe root applies `java-base` so Gradle interprets Maven BOM constraints as Java platform metadata. Without that plugin the custom configuration selected an untyped default BOM variant and omitted its constraints.
- Commands and compatibility boundaries are recorded in `tests/consumers/README.md`. Stable Kotlin, JDK 22 runtime, native consumer actions, and actual adapters remain separate gates.
- Fresh consumer `clean :java-api:run :java-api:verifyCallableAmbiguity :kotlin-api:run resolveIntegrationBaselines --write-locks` passed: nine tasks executed, both consumer compilers reran, and resolved graphs were locked.
- `./gradlew build` passed after registering the new issue in the backlog index: 265 tasks, 41 executed, 224 up-to-date. The initial attempt correctly rejected the missing index entry; it was corrected without changing the validation gate.

---
<!-- id: issue-20261006-152422-003-establish-packaged-java-and-kotlin-integration-con  file: issue-20261006-152422-003-establish-packaged-java-and-kotlin-integration-con.md -->
