# Development Guide for mazewall

This file contains essential information for developers contributing to the `mazewall` project.

## 1. Build & Configuration
- **Prerequisites**: JDK 22+ (finalized FFM API). Linux host recommended (Kernel 6.2+ for full feature support).
- **Build**: Use `./gradlew build` to compile the project.
- **Project Structure**:
    - `:enforcer`: Core sandbox engine (Seccomp/Landlock).
    - `:profiler`: Developer diagnostic suite (syscall profiling).

## 2. Testing Information
- **Unit Tests (Host-side)**: Run `./gradlew test`. Fast, no kernel interaction.
- **Integration Tests (Containerized)**: Run `./scripts/run_tests.sh`. This uses Podman/Docker to ensure the correct kernel capabilities.
- **Adding Tests**: Follow the project's TDD approach.
    - **Example (Unit Test)**:
      To run a specific test class:
      ```bash
      ./gradlew :enforcer:test --tests io.mazewall.PolicyTest
      ```

## 3. Additional Development Information
- **Code Quality**: Strictly adhere to `[mazewall Code Quality & Craftsmanship Standards](.agents/CODE_QUALITY.md)`.
- **Architectural Rules**:
    - Follow strict engineering rules in `enforcer/AGENTS.md` and `profiler/AGENTS.md`.
    - Components interact with OS resources exclusively through `NativeEngine` trait interfaces for testability.
    - Off-heap allocations must be scoped using `Arena.ofConfined().use { }`.
- **Documentation**: All new architectural findings, kernel behavior discoveries, or security nuances must be documented immediately in `/docs/internals/`. Critical insights must be logged in `docs/internals/backlog/code-issues-backlog.md`.
