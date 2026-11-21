---
title: "Declare Linker.Option.firstVariadicArg(1) on variadic syscall and prctl FFM downcalls"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt"
target_symbols: 
  - "RealNativeEngine"
open_questions: false
---

# 🟡 [Severity: HIGH]: Declare Linker.Option.firstVariadicArg(1) on variadic syscall and prctl FFM downcalls

**Context:**
RealNativeEngine.SYSCALL and RealNativeProcess.PRCTL invoke variadic C libc functions without Linker.Option.firstVariadicArg(1), violating the x86_64 System V variadic ABI (%al register contract) relied upon by C va_list wrappers such as musl libc on Alpine Linux.

**Needed:**
1. Pass Linker.Option.firstVariadicArg(1) on RealNativeEngine.SYSCALL and RealNativeProcess.PRCTL downcall descriptors.

## Progress Evidence
- Added `Linker.Option.firstVariadicArg(1)` to `RealNativeEngine.SYSCALL` and `RealNativeProcess.PRCTL` downcall descriptors, `Linker.Option.firstVariadicArg(2)` to `RealNativeEngine.IOCTL_ADDR`, `RealNativeEngine.IOCTL_LONG`, `RealNativeEngine.FCNTL`, and `RealNativeFileSystem.OPEN`, and `Linker.Option.firstVariadicArg(3)` to `RealNativeFileSystem.OPENAT` in `platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt` (`:lockdown` inherits the updated `RealNativeEngine.kt` via `PrepareLockdownCoreSources`).
- Added live variadic libc downcall tests (`exerciseVariadicDowncalls` covering `syscall`, `prctl`, `open` including `EEXIST` `errno` capture, `openat`, `fcntl`, `ioctl` long/addr) in `platform/src/test/kotlin/io/mazewall/ffi/RealNativeHelperDowncallTest.kt` and verified `./gradlew :platform:test` (528 tests, 0 skipped, 0 failed), `:lockdown:test` (17 tests, 0 failed), `:lockdown:integrationTestFreshJvm` (2 tests, 0 failed), `:lockdownConsumerCheck` (JDK 22 & 25), and `ktlintCheck` / `checkKotlinAbi`.
- Executed read-only Alpine Linux `3.24.4` / `musl libc (x86_64) Version 1.2.6` container verification (`eclipse-temurin:25-jdk-alpine`, `Temurin-25.0.5.1+2-LTS`) covering both packaged `:lockdown` (classpath `JAVA_CLASSPATH_LOCKDOWN_OK`, JPMS `--module-path` `JAVA_JPMS_LOCKDOWN_OK`, non-Linux zero-warning failure, and `--illegal-native-access=deny`) and `:platform` (`MuslVariadicDriver` running `RealNativeHelperDowncallTest.exerciseVariadicDowncalls` plus missing-symbol handle shape and `prctl` validation -> `MUSL_PLATFORM_VARIADIC_OK` with zero restricted-method warnings).
- Remaining before archival: confirm whether full `./gradlew build` / kernelCheck completion is required for closure and resolve/retire cleanly via the backlog archival flow.

---
<!-- id: issue-20261120-151750-281-declare-linker-option-firstvariadicarg-1-on-variad  file: issue-20261120-151750-281-declare-linker-option-firstvariadicarg-1-on-variad.md -->
