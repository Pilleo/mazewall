---
title: "Add JPMS module-info, sources/javadoc JARs, lockdown virtual-thread error message, and complete 5-syscall plus NATIVE_IMAGE kernel tests"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "lockdown/build.gradle.kts"
target_symbols: 
  - "verifyLockdownPublication"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Add JPMS module-info, sources/javadoc JARs, lockdown virtual-thread error message, and complete 5-syscall plus NATIVE_IMAGE kernel tests

**Context:**
mazewall-lockdown lacks module-info.class / Automatic-Module-Name (forcing --enable-native-access=ALL-UNNAMED), lacks sourcesJar/javadocJar for IDE and Maven Central compatibility, emits an inaccurate virtual-thread error naming ManagedRuntime/portal worker, and only kernel-tests execve under HOTSPOT_JIT.

**Needed:**
1. Add lockdown module-info.java and Automatic-Module-Name, enable withSourcesJar()/withJavadocJar() and extend verifyLockdownPublication, fix virtual-thread message in ProcessFilterInstaller, and add kernel integration tests for memfd_create/io_uring_setup/io_uring_enter/execveat and NATIVE_IMAGE PROT_EXEC denial.

## Progress Evidence
- Added `lockdown/src/main/java/module-info.java` (`module io.mazewall.lockdown` exporting only `io.mazewall.lockdown`), set `Automatic-Module-Name` to `io.mazewall.lockdown`, enabled `withSourcesJar()`/`withJavadocJar()`, relocated internalized core classes to `io.mazewall.lockdown.internal.*` (eliminating Java keyword package `platform.native` and split-package hazards), and extended `VerifyLockdownPublication` with `ModuleFinder` package/export checks plus sources/javadoc JAR checks.
- Updated `ProcessFilterInstaller` virtual-thread error text, verified `MazewallLockdownIntegrationTest` (`memfd_create`, `io_uring_setup`, `io_uring_enter`, `execveat`, `execve`, disallowed `prctl`, `clone3 -> ENOSYS`, JPMS `--module-path` subprocess) and `MazewallLockdownNativeImageIntegrationTest` (isolated `-Xint` subprocess confirming `mmap`/`mprotect(PROT_EXEC)` `EPERM` + `execve` `EPERM` + `HOTSPOT_JIT` rejection).

---
<!-- id: issue-20261120-151800-231-add-jpms-module-info-sources-javadoc-jars-lockdown  file: issue-20261120-151800-231-add-jpms-module-info-sources-javadoc-jars-lockdown.md -->
