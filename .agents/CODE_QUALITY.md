# mazewall Code Quality & Craftsmanship Standards

To ensure mazewall is production-grade, secure, and maintainable, all code changes must adhere strictly to these engineering standards:

1. **SOLID Principles:** Favor composition over inheritance, design cohesive interfaces, and maintain loosely coupled architectural boundaries.
2. **Verification via Types & Compiler Features:** Use strict domain types to prevent interchangeable identifiers, units, handles, and outcomes. Reuse existing value classes and validated constructors; validate external input at the boundary. Use enums for finite named choices instead of string/integer tags or invalid combinations of flags. Kotlin `when` and Java `switch` over sealed or enum types must enumerate all variants without a catch-all `else`/`default`, so new variants require compiler-checked handling. Defaults for open external inputs must preserve validation and fail-closed behavior.
3. **Immutability & Functional Programming (FP):** Use pure decision functions with explicit inputs and outputs whenever possible. Keep native calls, filesystem access, clocks, environment reads, logging, and other effects at explicit boundaries. Prefer `val`/final fields and immutable values; read-only collection interfaces do not make mutable backing data immutable. Keep necessary mutation local or owned by one lifecycle component.
4. **AOT (Ahead-of-Time) Friendliness:** Code must support AOT compilation (e.g. to GraalVM/Native images). Avoid dynamic proxy generation, runtime classloader manipulation, or heavy reflection. Keep structures immutable and layouts deterministic at runtime.
5. **Logical Modularity & Traits:** Decouple components and interact with OS/environment resources exclusively through trait interfaces (e.g., `NativeEngine`) to enable robust unit testing and fault injection.
6. **Debuggability & Clean Diagnostics:** State must be easily inspectable during failures. Error messages and logs must be context-rich and trace-friendly. Never swallow underlying exceptions.
7. **Future-Proofness & Valhalla Readiness:** Design code to be adaptable to upcoming JVM updates (such as Project Valhalla value types) and new Linux subsystems.
8. **Readability & Idiomatic Kotlin:** Code must be idiomatic, self-documenting, and explain complex low-level OS/FFM mechanics via comments.
9. **State Machines with Sealed Hierarchies:** Model closed workflows, lifecycles, events, and outcomes with Kotlin `sealed class`/`sealed interface` hierarchies or Java sealed types when variants carry different data. Use enums when variants are finite names without distinct payloads. Evaluate transitions as pure functions producing the next state and explicit effects; execute those effects at the I/O boundary.
10. **FFM & Native Memory Safety:** Off-heap allocations must use deterministic scopes like `Arena.ofConfined().use { }` to prevent memory leaks. Captured native `errno` states must be read immediately after downcalls, and struct layouts must align precisely with host CPU architectures to avoid JVM crashes.
11. **Fail-Closed Security Default:** Security violations, enforcement failures, or environment mismatches must result in critical exceptions or process termination. Never degrade enforcement to a silent warning-and-bypass.

12. **Minimum Visibility & Justified Exceptions:** Choose the narrowest supported scope for every function, type, property, field, and setter: local/private first, then `internal` or package-private for actual callers, then public for a required API or cross-module contract. Restrict setter visibility independently. Preserve existing supported APIs. Broader visibility, open hierarchies, impure decisions, or mutable state require a concrete interop, resource-lifecycle, extension, or compatibility reason; do not add them for hypothetical future use.

13. **Pure machines for lifecycle and syscall decisions:**
    - New daemon, handshake, or session protocol: sealed state + `evaluate(state, event)` + effects. Do not assign `state =` inside I/O.
    - New supervisor/syscall decision: sealed `SupervisorRoute` / `SupervisedKind`, exhaustive `when`, no `else ->` on those types.
    - Unit-test the matrix without UNIX sockets. Interpreter tests may use `MockNativeEngine`.
    - Adding a sealed subtype: update `ArchitectureTest.sealedSecurityOutcomesHaveAClosedSubclassSet` in the same commit.
    - Silent `else` on sealed/enum/`Boolean` `when` is a Detekt error (`ElseCaseInsteadOfExhaustiveWhen` via `detektMain`). Do not add a file-list source scan. `:tools` does not run ktlint or Detekt.
    - `EPERM` / `EACCES`: rethrow or `Rejected` / `Abort`. Never `recover { 0 }`.
    - Keep machines, events, and effects private where possible; use `internal` for actual module callers. `public` is for required operator-facing types (`Policy`, `FileDescriptor`, `UnixListenDaemonState`). Cross-module protocol helpers stay public only when another Gradle module must call them; mark those with `@MazewallInternal` (`SupervisedKind`, `UserNotifReply`, `SeccompNotifications`).

Use compiler exhaustiveness, explicit API/ABI checks, and existing Detekt rules for enforceable requirements. Review purity, domain modeling, ownership, and minimum visibility in connected code; do not substitute a lexical source scan for those judgments.

