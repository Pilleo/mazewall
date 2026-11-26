# Changelog

All notable changes to the supported public release artifacts of Mazewall are documented in this file.

## [0.1.0] - Initial Public Lockdown Release

### Added
- Extracted the standalone `:lockdown` Gradle module published as `io.mazewall:mazewall-lockdown` (with `-sources.jar`, `-javadoc.jar`, JPMS `module-info.class` for `io.mazewall.lockdown`, and zero project dependencies beyond `kotlin-stdlib`).
- Added the stable Java-friendly public API in package `io.mazewall.lockdown`:
  - `Mazewall.installOnProcess(first, ...more)` for process-wide Seccomp-BPF installation via `SECCOMP_FILTER_FLAG_TSYNC`.
  - Selectable `LockdownPreset` presets (`ELASTICSEARCH`, `OPENSEARCH`, `MAZEWALL`, `MAZEWALL_STRICT`) via `ProcessPolicy.elasticsearch(...)`, `ProcessPolicy.openSearch(...)`, `ProcessPolicy.denyProcessCreation(...)`, and `ProcessPolicy.fromPreset(...)`, plus type-safe immutable syscall customization via `ProcessPolicy.block(...)` and `ProcessPolicy.unblock(...)` over `LockdownSyscall` (including `pidfd_open` / `pidfd_getfd`, excluding critical JVM coordination syscalls) and `PR_SET_NAME` / `PR_GET_NAME` / seccomp / `no_new_privs` `prctl` allowlisting + `clone3 -> ENOSYS`.
  - Explicit `RuntimeProfile` (`HOTSPOT_JIT` vs `NATIVE_IMAGE`) without runtime profile guessing, rejecting conflicting profiles in one call and rejecting irreversible `NATIVE_IMAGE` -> `HOTSPOT_JIT` relaxation.
  - `ProcessInstallation` result value (`filterDepth`, `architecture`) and fail-closed `MazewallInstallationException` (including missing `--enable-native-access`, virtual-thread, and non-Linux hosts without premature FFM initialization).
- Added container `no_new_privs` resilience (`PR_GET_NO_NEW_PRIVS == 1` verification fallback when `PR_SET_NO_NEW_PRIVS` is blocked by an outer seccomp policy, with phase-specific errno diagnostics) and pre-install rejection of `-XX:OnError` / `-XX:OnOutOfMemoryError` when blocking process creation (guiding callers to `-XX:+ExitOnOutOfMemoryError` / `-XX:+CrashOnOutOfMemoryError`).
- Enabled lightweight startup verification (`PR_GET_SECCOMP == 2` + `getpid()` liveness) by default for production safety under strict outer seccomp profiles/IDS, with opt-in `-Dio.mazewall.selfVerify=true` active denied-syscall probing.
- Fixed shared `:platform`/`:enforcer` seccomp core issues included in `:lockdown`: `Linker.Option.firstVariadicArg(1)` on variadic `syscall`/`prctl` downcalls, missing-libc-symbol FFM handle shapes, `RealNativeProcess.prctl` managed pointer validation, `Arch.AARCH64.renameat` (`38`), canonical `SeccompAction.restrictivenessRank()` planning, and delta `ALLOW_UNSAFE_PRCTL` diagnostic noise suppression.
- Targeted JDK 22 bytecode (`--release 22` / JVM target 22) using finalized JDK FFM APIs with zero external C libraries or JNI artifacts.
- Enforced ABI, JPMS, dependency, and publication gates via `lockdown/api/lockdown.api`, `verifySupportedApiSurface`, and `verifyLockdownPublication`.
- Added [STABILITY.md](STABILITY.md) and Apache License 2.0 ([LICENSE](LICENSE)) defining Stable, Experimental, and Internal tiers.
