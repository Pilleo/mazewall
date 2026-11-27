---
title: "Phase 4: Verify kernel matrix, packaged consumers, and independent security review"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261120-122433-169-phase-3-implement-stable-io-mazewall-lockdown-api-"
  - "issue-20261028-230731-560-decouple-core-release-qualification-from-optional-"
  - "issue-20261028-230730-359-publish-qualified-platform-envelopes-for-each-cont"
  - "issue-20261006-152422-003-establish-packaged-java-and-kotlin-integration-con"
  - "issue-20261008-001530-668-stable-packaged-consumer-compatibility-gates"
  - "issue-20261112-063058-038-preserve-expected-ffm-handle-shape-when-a-libc-sym"
component: "core"
target_modules: [":lockdown"]
target_files:
  - "build.gradle.kts"
  - "tests/consumers/java-api/build.gradle"
  - "tests/consumers/kotlin-api/build.gradle"
  - ".github/workflows/ci.yml"
target_symbols:
  - "verifyPublishedArtifacts"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Phase 4: Verify kernel matrix, packaged consumers, and independent security review

**Context:**
Qualify the exact `:lockdown` release candidate on Linux x86_64 with kernel denial/TSYNC/liveness/rejection lanes, HotSpot JDK 22 and 25 packaged consumers, publication allowlists, the full `./gradlew build`, and independent security review. AArch64 and actual GraalVM Native Image deployments are not `0.1.0` targets.

**Needed:**
1. Run the publication allowlist, JDK 22/25 packaged consumer checks, full kernel/liveness/rejection gates, and `./gradlew build` on a clean candidate. Record exact supported environments and complete independent security review.

## Progress Evidence
- Expanded `lockdown/src/integrationTest/kotlin/io/mazewall/lockdown/MazewallLockdownIntegrationTest.kt` (`integrationTestFreshJvm`): verified pre-install `/bin/echo` control, repeated idempotent `installOnProcess`, authenticated `EPERM` (`Operation not permitted`) for `ProcessBuilder` and `Runtime.exec` across main thread, pre-existing platform thread (`TSYNC`), pre-existing `ExecutorService` thread, new platform thread, and virtual thread, direct kernel syscall verdicts (`EPERM` on `execve`, `execveat`, `memfd_create`, `io_uring_setup`, `io_uring_enter`, disallowed `prctl`; `ENOSYS` on `clone3`; `2` on `PR_GET_SECCOMP`), packaged JAR subprocess contracts (`--module-path` JPMS `io.mazewall.lockdown`, `--illegal-native-access=deny`, `-Dos.name=Mac OS X` without native access, `-Dio.mazewall.selfVerify=true`), plus post-install JIT warmup, GC, class loading, temp file I/O, and loopback socket liveness.
- Added `MazewallLockdownNativeImageIntegrationTest` (`integrationTestFreshJvm`): isolated `-Xint` child JVM subprocess verifying `RuntimeProfile.NATIVE_IMAGE` blocks `mmap(PROT_EXEC)`, `mprotect(PROT_EXEC)`, and `ProcessBuilder` with `EPERM`, and rejects `HOTSPOT_JIT` relaxation.
- Added `VerifyLockdownPublication` (`:lockdown:verifyLockdownPublication`) in `lockdown/build.gradle.kts` verifying JAR class allowlist/blocklist (`io.mazewall.lockdown` + `io.mazewall.lockdown.internal.*` relocation), JPMS `module-info.class` / `Automatic-Module-Name` / `ModuleFinder` package consistency, `-sources.jar` / `-javadoc.jar`, and generated Maven POM metadata + `kotlin-stdlib`-only dependency closure; wired into `:lockdown:check` and `:lockdown:unitCheck` (covered by root `./gradlew unitCheck`).
- Added standalone external packaged-consumer build in `tests/consumers/lockdown/` (`java-classpath`, `java-jpms`, `kotlin-api`), `LockdownPackagedConsumerTest`, and `./gradlew :lockdownConsumerCheck`, staging only `:lockdown` into a temporary local Maven repo and verifying dependency closure, `-sources.jar`/`-javadoc.jar`/`.pom`/`.module`, javac internal/JPMS encapsulation, non-Linux zero-warning failure, `--illegal-native-access=deny`, and live EPERM kernel enforcement across JDK 22 and JDK 25.
- Executed Alpine Linux `3.24.4` / `musl libc (x86_64) Version 1.2.6` container verification (`eclipse-temurin:25-jdk-alpine`, `Temurin-25.0.5.1+2-LTS`) covering packaged `:lockdown` (classpath `JAVA_CLASSPATH_LOCKDOWN_OK`, JPMS `--module-path` `JAVA_JPMS_LOCKDOWN_OK`, non-Linux zero-warning failure, `--illegal-native-access=deny`) and `:platform` variadic FFM downcalls (`RealNativeHelperDowncallTest` -> `MUSL_PLATFORM_VARIADIC_OK`) after declaring `firstVariadicArg` across all 6 variadic libc wrappers (`syscall`, `prctl`, `ioctl`, `fcntl`, `open`, `openat`). This is not a full release-candidate or OCI profile receipt.

---
<!-- id: issue-20261120-122441-870-phase-4-verify-kernel-matrix-packaged-consumers-an  file: issue-20261120-122441-870-phase-4-verify-kernel-matrix-packaged-consumers-an.md -->
