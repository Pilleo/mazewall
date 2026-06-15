# mazewall Code Quality & Craftsmanship Standards

To ensure mazewall is production-grade, secure, and maintainable, all code changes must adhere strictly to these engineering standards:

1. **SOLID Principles:** Favor composition over inheritance, design cohesive interfaces, and maintain loosely coupled architectural boundaries.
2. **Verification via Types & Compiler Features:** Leverage the Kotlin type system (value classes, sealed hierarchies, generics) to move runtime checks to compile-time. Pattern matching (`when` blocks) must always be exhaustive.
3. **Immutability & Functional Programming (FP):** Maximize read-only structures, functional paradigms, and pure functions. Minimize mutable state.
4. **AOT (Ahead-of-Time) Friendliness:** Code must support AOT compilation (e.g. to GraalVM/Native images). Avoid dynamic proxy generation, runtime classloader manipulation, or heavy reflection. Keep structures immutable and layouts deterministic at runtime.
5. **Logical Modularity & Traits:** Decouple components and interact with OS/environment resources exclusively through trait interfaces (e.g., `NativeEngine`) to enable robust unit testing and fault injection.
6. **Debuggability & Clean Diagnostics:** State must be easily inspectable during failures. Error messages and logs must be context-rich and trace-friendly. Never swallow underlying exceptions.
7. **Future-Proofness & Valhalla Readiness:** Design code to be adaptable to upcoming JVM updates (such as Project Valhalla value types) and new Linux subsystems.
8. **Readability & Idiomatic Kotlin:** Code must be idiomatic, self-documenting, and explain complex low-level OS/FFM mechanics via comments.
9. **State Machines with Sealed Hierarchies:** Prefer modeling complex workflows, lifecycles, and component states as formal state machines using Kotlin `sealed class` or `sealed interface` hierarchies. This guarantees type-safety and exhaustive compiler verification during state transitions.
10. **FFM & Native Memory Safety:** Off-heap allocations must use deterministic scopes like `Arena.ofConfined().use { }` to prevent memory leaks. Captured native `errno` states must be read immediately after downcalls, and struct layouts must align precisely with host CPU architectures to avoid JVM crashes.
11. **Fail-Closed Security Default:** Security violations, enforcement failures, or environment mismatches must result in critical exceptions or process termination. Never degrade enforcement to a silent warning-and-bypass.


