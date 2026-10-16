---
name: review
description: >
  Read-only security and architectural review of mazewall changes or a requested
  subsystem. Use for code review, PR review, audits, vulnerabilities, and bug
  investigations. Findings include code evidence and concrete corrections.
---

# Security and architectural review

Review JVM/Linux sandboxing correctness, native memory safety, architecture,
and meaningful test coverage. Match the scope requested by the operator.

## Review procedure

1. Start a PR review from the compact diff against its actual base and declared
   target files/symbols. Expand to callers, tests, and lifecycle code needed to
   verify a concrete hypothesis; avoid unrelated source dumps.
2. Read relevant module instructions and design documents. Recall applicable
   architectural decisions when memory is available; verify them against current code.
3. Follow root `AGENTS.md` for discovery: direct reads/exact search for known
   locations, semantic discovery for unknown implementation, optional outlines
   for large unfamiliar files, and ast-grep for structural patterns. Record
   unavailable indexes and truncated results as incomplete evidence.
4. Check the invariants below and `.agents/CODE_QUALITY.md`. Green CI complements
   review; inspect which tests and analysis the gate actually selected.
5. Return findings with file/line evidence, impact, a concrete correction, and
   a meaningful regression check. Distinguish verified defects from incomplete evidence.

Reviews are read-only by default. Publish GitHub comments, create backlog issues,
edit files, or save review-derived memory only when the operator authorizes that follow-up.

## Security and lifecycle invariants

- **Fail closed:** reject silent swallows or bypasses of `EPERM`/`EACCES` and
  unapproved containment degradation.
- **JVM coordination:** preserve the coordination-syscall floor, argument
  inspection, virtual-thread installation restrictions, and Landlock-before-Seccomp order.
- **Native safety:** verify exact UAPI widths/alignment, Arena/MemorySegment
  lifetimes, immediate errno capture, owned descriptor cleanup, and isolation of
  raw FFM operations behind native boundaries.
- **Concurrency:** examine ownership, cancellation, terminal states, races, and
  TOCTOU where siblings can mutate memory around native calls.
- **Portability:** verify supported kernel/JDK behavior and explicitly chosen fallback policy.
- **Tests:** require assertions that prove behavior; reject warmups, sleeps, or
  test-only exception swallowing that hide containment failures.
- **Scope:** compare actual changed files and symbols with the authorized work package.

## Coding contract

- Use sealed states/events/outcomes for closed variants with differing data and
  enums for finite named choices. Branch exhaustively over closed types.
- Use strict domain types and validated boundaries; reuse existing wrappers such
  as `FileDescriptor`, `Pid`, and `SyscallNumber` where applicable.
- Keep decisions and state transitions pure, effects explicit, and necessary
  mutable state narrowly owned.
- Use the minimum declaration and setter visibility required by actual callers.
  Preserve supported public APIs; justify interop, lifecycle, extension, and compatibility exceptions.
- Verify native results use the existing result contracts and protocol states
  preserve compiler-checked outcomes.

Use `docs/internals/designs/core/architectural-map.md` and its module design/diagram
links when architecture context is needed. Check existing backlog findings before
recommending a new issue. Authorized remediation follows the normal backlog and
verification workflow.
