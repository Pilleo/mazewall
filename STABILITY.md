# Mazewall API Stability Policy

Mazewall divides its modules and packages into three stability tiers. For the `0.1.0` release, only `io.mazewall:mazewall-lockdown` (`io.mazewall.lockdown`) is published and supported as a stable external API.

## Tier 1 — Stable (Published in `0.1.0`)

- **Artifact:** `io.mazewall:mazewall-lockdown` (`:lockdown`, with `-sources.jar` and `-javadoc.jar`)
- **JPMS module:** `io.mazewall.lockdown` (exports only `io.mazewall.lockdown`)
- **Public package:** `io.mazewall.lockdown`
- **Public API surface:**
  - `Mazewall.installOnProcess(first: ProcessPolicy, vararg more: ProcessPolicy): ProcessInstallation`
  - `ProcessPolicy.denyProcessCreation(runtime: RuntimeProfile): ProcessPolicy`
  - `RuntimeProfile` (`HOTSPOT_JIT`, `NATIVE_IMAGE`)
  - `ProcessInstallation` (`filterDepth`, `architecture` — `"amd64"` / `"aarch64"`)
  - `MazewallInstallationException`
- **Compatibility guarantee:**
  - Follows Semantic Versioning (`0.1.x` patch releases maintain binary and source compatibility for `io.mazewall.lockdown.*`).
  - Compiled with Kotlin `explicitApi()` and verified against the checked-in ABI dump (`lockdown/api/lockdown.api`), `verifySupportedApiSurface`, and `verifyLockdownPublication`.
  - Bytecode constructors and internal helpers of `ProcessInstallation` and `ProcessPolicy` are `private` / `@JvmSynthetic`.
- **Supported runtime matrix (`0.1.0`):**
  - **OS & kernel:** Linux `x86_64` (`amd64`) and `aarch64` (`arm64`), kernel 3.17+ with Seccomp-BPF and `SECCOMP_FILTER_FLAG_TSYNC` enabled.
  - **JVM:** JDK 22 through JDK 25 (`--release 22` bytecode floor) running with `--enable-native-access=io.mazewall.lockdown` (module path) or `--enable-native-access=ALL-UNNAMED` (classpath).
  - **Language compatibility:** Java 22+ (all standard types); Kotlin consumers require a Kotlin compiler compatible with `2.4.21` metadata (Kotlin `2.3+`).
  - **Filter & verification contract (`0.1.0`):** Denies `execve`, `execveat`, `memfd_create`, `io_uring_setup`, `io_uring_enter`, and non-allowlisted `prctl` options with `EPERM`; returns `ENOSYS` for `clone3`; leaves `fork`/`vfork`/`clone` permitted (children inherit the filter). Startup verification checks `PR_GET_SECCOMP == 2` plus `getpid()` liveness by default; active denied-syscall probing is opt-in via `-Dio.mazewall.selfVerify=true`.

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
