# Mazewall API Stability Policy

Mazewall divides its modules and packages into three stability tiers. The only stable artifact planned for `0.1.0` is `io.mazewall:mazewall-lockdown` (`io.mazewall.lockdown`).

## Tier 1 — Stable surface for `0.1.0`

- **Artifact:** [`:lockdown`](lockdown/README.md), planned as `io.mazewall:mazewall-lockdown` on GitHub Packages. See the [public API reference](docs/public-api.md) and the source JAR for API documentation.
- **JPMS module:** `io.mazewall.lockdown` (exports only `io.mazewall.lockdown`)
- **Public package:** `io.mazewall.lockdown`
- **Public API surface:**
  - `Mazewall.installOnProcess(first: ProcessPolicy, vararg more: ProcessPolicy): ProcessInstallation`
  - `ProcessPolicy` presets & immutable customization:
    - `ProcessPolicy.elasticsearch([runtime])` (`execve`, `execveat`, `fork`, `vfork`)
    - `ProcessPolicy.openSearch([runtime])` (`elasticsearch` + `ptrace`, `process_vm_readv`, `process_vm_writev`, `pidfd_getfd`)
    - `ProcessPolicy.denyProcessCreation([runtime])` (default `MAZEWALL` preset: `execve`, `execveat`, `memfd_create`, `io_uring_setup`, `io_uring_enter`)
    - `ProcessPolicy.fromPreset(preset: LockdownPreset, [runtime])` (`ELASTICSEARCH`, `OPENSEARCH`, `MAZEWALL`, `MAZEWALL_STRICT`)
    - `ProcessPolicy.block(...)`, `ProcessPolicy.unblock(...)`, `ProcessPolicy.blockedSyscalls()`, `ProcessPolicy.runtimeProfile()`
  - `LockdownPreset` (`defaultBlockedSyscalls()`), `LockdownSyscall`
  - `RuntimeProfile` (`HOTSPOT_JIT`, `NATIVE_IMAGE`)
  - `ProcessInstallation` (`filterDepth`, `architecture`; the supported architecture is `"amd64"`)
  - `MazewallInstallationException`
- **Compatibility guarantee:**
  - Follows Semantic Versioning (`0.1.x` patch releases maintain binary and source compatibility for `io.mazewall.lockdown.*`).
  - Compiled with Kotlin `explicitApi()` and verified against the checked-in ABI dump (`lockdown/api/lockdown.api`), `verifySupportedApiSurface`, and `verifyLockdownPublication`.
  - Bytecode constructors and internal helpers of `ProcessInstallation` and `ProcessPolicy` are `private` / `@JvmSynthetic`.
- **Supported runtime matrix (`0.1.0`):**
  - **OS & kernel:** Linux `x86_64` (`amd64`) only, with Seccomp-BPF and `SECCOMP_FILTER_FLAG_TSYNC` enabled. Linux 3.17 introduced TSYNC; that is a feature floor, not a tested deployment matrix.
  - **JVM:** HotSpot JDK 22 and 25 are the release qualification targets (`--release 22` bytecode floor). Use `--enable-native-access=io.mazewall.lockdown` (module path) or `--enable-native-access=ALL-UNNAMED` (classpath). GraalVM Native Image is not a qualified deployment target.
  - **Language compatibility:** Java 22 bytecode floor; independent Kotlin consumers are checked with Kotlin `2.4.21`. Older Kotlin compiler versions are not qualified here.
  - **Filter & verification contract (`0.1.0`):** Each preset denies its documented syscalls (and non-allowlisted `prctl` options outside `PR_SET_NAME`, `PR_GET_NAME`, `PR_GET_SECCOMP`, `PR_SET_SECCOMP`, `PR_SET_NO_NEW_PRIVS`, `PR_GET_NO_NEW_PRIVS`) with `EPERM`, returns `ENOSYS` for `clone3` (falling back to `clone`), and can be immutably tailored with `ProcessPolicy.block(...)` / `ProcessPolicy.unblock(...)` over `LockdownSyscall` without touching critical JVM coordination syscalls. `fork`/`vfork` are blocked in `ELASTICSEARCH`, `OPENSEARCH`, and `MAZEWALL_STRICT`, and left permitted (with inherited filter) in the `MAZEWALL` (`denyProcessCreation`) preset. Startup verification checks `PR_GET_SECCOMP == 2` plus `getpid()` liveness by default; active denied-syscall probing is opt-in via `-Dio.mazewall.selfVerify=true`.

## Tier 2 — Experimental (Unpublished in `0.1.0`)

- **Modules:** `:enforcer`, `:profiler`, `:portals:runtime`, `:portals:codegen`, `:portals:worker`, `:integrations:koog`
- **Capabilities:** Landlock filesystem restrictions, thread-scoped workers (`ManagedRuntime` / `InProcessDomain`), `SECCOMP_RET_USER_NOTIF` stacktrace supervision, eBPF/strace profiling, and out-of-process Portal brokers/workers.
- **Compatibility guarantee:**
  - Retained in-repository for active development and evaluation; not published as stable `0.1.0` Maven coordinates.
  - APIs, threat-model envelopes, and behavior may change without deprecation notice prior to promotion into a future release.

## Tier 3 — Internal (Private Implementation Details)

- **Modules & packages:** `:platform`, `io.mazewall.lockdown.internal.*`, `io.mazewall.ffi.*`, `io.mazewall.seccomp.*`, `io.mazewall.platform.*`, `io.mazewall.enforcer.engine.*`, `io.mazewall.enforcer.internal.*`, `io.mazewall.enforcer.state.*`, `io.mazewall.enforcer.diagnostics.*`
- **Guarantees:** None.
  - Inside `io.mazewall:mazewall-lockdown`, internalized seccomp/FFM classes are relocated under `io.mazewall.lockdown.internal.*`, rewritten to Kotlin `internal` visibility, unexported in JPMS `module-info`, and excluded from `lockdown/api/lockdown.api`.
  - Java and Kotlin consumers must never reference classes outside the exported `io.mazewall.lockdown` package.
