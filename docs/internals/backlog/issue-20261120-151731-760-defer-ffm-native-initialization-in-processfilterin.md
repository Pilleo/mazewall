---
title: "Defer FFM native initialization in ProcessFilterInstaller and wrap Error failures into MazewallInstallationException"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/ProcessFilterInstaller.kt"
target_symbols: 
  - "ProcessFilterInstaller"
open_questions: false
---

# 🟡 [Severity: HIGH]: Defer FFM native initialization in ProcessFilterInstaller and wrap Error failures into MazewallInstallationException

**Context:**
ProcessFilterInstaller.install eagerly evaluates LinuxNative.snapshot() as a default argument before platform-thread and Linux OS checks, causing RealNativeEngine.<clinit> to fail with ExceptionInInitializerError (wrapping IllegalCallerException) when --enable-native-access is absent or emit restricted-method warnings on non-Linux hosts, escaping catch(Exception) in Mazewall.installOnProcess.

**Needed:**
1. Defer LinuxNative.snapshot() until after platform-thread, Linux OS, and Arch checks, and convert native initialization ExceptionInInitializerError/IllegalCallerException into MazewallInstallationException.

## Progress Evidence
- Deferred `LinuxNative.snapshot()` in `enforcer/src/main/kotlin/io/mazewall/seccomp/ProcessFilterInstaller.kt` until after platform-thread validation, `SeccompPlatformProbe.validateLinux()`, and `Arch.current()`.
- Caught `ExceptionInInitializerError` (unwrapping `IllegalCallerException` with `--enable-native-access` guidance) and native `NoClassDefFoundError` in `lockdown/src/main/kotlin/io/mazewall/lockdown/Mazewall.kt`, rethrowing `MazewallInstallationException`.
- Verified via `:lockdown:test` (`MazewallInstallOnProcessTest`) and packaged subprocess tests in `:lockdown:integrationTestFreshJvm` (`--illegal-native-access=deny` and `-Dos.name=Mac OS X` without `--enable-native-access`, confirming zero `ExceptionInInitializerError` and zero restricted FFM warnings on non-Linux).

---
<!-- id: issue-20261120-151731-760-defer-ffm-native-initialization-in-processfilterin  file: issue-20261120-151731-760-defer-ffm-native-initialization-in-processfilterin.md -->
