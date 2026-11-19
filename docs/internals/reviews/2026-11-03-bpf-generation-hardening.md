# BPF generation hardening — 2026-11-03

## Scope and guarantees

Reviewed policy dispatch, argument inspections, assembly, structural verification,
the independent opcode simulator, action encoding, and ordinary/supervised native
installation handoff. The [containment design](../designs/enforcer/containment-design.md#2-bpf-construction-and-dispatch-guarantees)
describes the resulting contracts.

| Boundary | Enforcement |
| --- | --- |
| Instructions | Closed variants fix their opcodes; conditional comparisons and JA have different operand shapes; RET requires a typed action. |
| Builder sequence | Concrete stages expose only permitted operations; a private owner prevents freely constructing an arbitrary stage. |
| Verification status | Private program construction and immutable snapshots; only successful structural validation produces `Verified`. |
| Dynamic operands | Validated aligned seccomp-data loads, unsigned conditional offsets, nonnegative JA offsets, six argument slots, immutable equality lists and valid masks. |
| Assembly ownership | Builder-owned labels and termination prefixes; foreign, stale, duplicate, backward, unresolved and oversized destinations are rejected where applicable. |
| Control flow | Every instruction is checked, including unreachable code. Destinations use overflow-safe arithmetic; every successor advances within a nonempty program of at most 4096 instructions ending in RET. |
| Installation handoff | Ordinary and supervised installers pass a verified program to scoped native serialization; `sock_filter` stays 16/8/8/32 bits. |

Compiler contracts use JDK `JavaCompiler` against the actual JVM classes, with both
accepted and rejected consumers. They check constructors, builder transitions,
verification-status assignment, opcode/operand mismatches and argument-slot types.
Runtime checks cover facts that depend on collection contents, ownership or numeric
values. Structural validity does not establish policy correctness or installation.

The subsequent [upstream comparison](../research/bpf-upstream-validation.md) records
future fragment-exit and differential-oracle corrections, reusable test corpora,
generated-bytecode coverage and kernel conformance patterns. Those recommendations
are separate from the implemented guarantees and verification evidence below.

## Corrected defects

- Unverified code could assert the verified marker; caller mutation could invalidate
  an already checked program. Terminated builders also retained live mutable output.
- The verifier checked conditional fields for JA rather than its 32-bit `k`, and
  omitted unreachable invalid instructions. Unconditional jumps were unnecessarily
  restricted to the conditional 8-bit range.
- The AMD64 audit token also admits x32. The shared prologue now rejects its syscall
  bit and legacy numbers 512–547 before native policy dispatch, including copied
  architecture metadata with the same audit token.
- Invalid errno/TRACE payloads were accepted and masked; the shared TRACE encoder
  lost valid payloads. Constructor and copy paths now enforce the canonical ranges.
- Safe PRCTL options inherited explicit denial/default actions instead of preserving
  the JVM and filter-stacking floor.
- Duplicate argument inspections could shadow restrictions, including the special
  clone3 ENOSYS fallback. Both emission paths now reject duplicate ownership.

The existing small-BST and grouped linear dispatch algorithms remain in use.
Both architectures' explicit, default and profiling verdicts are checked around
their dispatch thresholds. Memory protection, thread-clone and PRCTL inspections
remain argument-sensitive.

## Verification evidence

- Initial failing reproductions: `/tmp/opencode/bpf-red.log`,
  `/tmp/opencode/bpf-prctl-red.log` and `/tmp/opencode/bpf-clone3-red.log`.
- Platform action/instruction/simulator contracts: **43 tests, zero failures or
  skips**, retained in `/tmp/opencode/bpf-focused-native.log` and platform JUnit XML.
- Final enforcer seccomp regressions: **113 tests, zero failures or skips**;
  `./gradlew :enforcer:test --tests 'io.mazewall.seccomp.*' :enforcer:ktlintCheck`
  passed (`/tmp/opencode/bpf-final-regression.log`).
- Canonical-policy and native-lifetime focused tests also passed in the broader
  focused run. Correctly qualified selectors subsequently ran **30 architecture
  checks and five native packing/delegation tests**, all without failures or skips
  (`/tmp/opencode/bpf-architecture-packing.log`). Their package is
  `io.mazewall.enforcer.api`, not the package implied by their source directory.
- Final instruction-interface/bound corrections passed the focused tests, native
  x32 regression, both modules' ktlint and the global Detekt warning ratchet:
  **zero added findings** (`/tmp/opencode/bpf-ratchet-regression.log`). No warning
  inventory or threshold was relaxed.
- Native x32 regression: **one test, zero failures or skips**. Three baseline child
  JVMs survived syscall probes `0x40000027`, `512`, and `547`; three filtered child
  JVMs completed installation, wrote a parent-checked readiness marker and exited
  with SIGSYS (159). Command:
  `./gradlew :enforcer:integrationTestFreshJvm --tests '*SeccompDifferentialVerdictTest.x32*'`.
  Evidence: `/tmp/opencode/bpf-ratchet-regression.log` and the corresponding JUnit XML.
  Host: x86_64, Linux `7.2.11-x64v3-xanmod1`, JDK `25.0.4.1.1`.
- First `./gradlew build` stopped at `detektWarningRatchet` with eleven new findings
  in the changed BPF files (`/tmp/opencode/bpf-full-build.log`). Those findings are
  corrected. The full retry **passed in 22m 40s** on 2026-11-03:
  **243 actionable tasks, 107 executed and 136 up-to-date**, with the configuration
  cache entry stored (`/tmp/opencode/bpf-full-build-final.log`). The 13:32:56 +01:00
  liveness check had found active portal tests and nested compilation; the build
  subsequently completed successfully without a restart.
- The retry log contains profiler child-shutdown cleanup exceptions reporting
  `Function not implemented` for private socket/directory deletion. Completed
  profiler JUnit reports inspected at this checkpoint showed zero failures/errors:
  host tests 1497 (5 skipped), integration tests 16 (9 skipped), and fresh-JVM
  integration tests 24 (5 skipped). The cleanup diagnostics remain an observation;
  their cause was not established by the build-liveness check.
- Local documentation links, `git diff --check` and `./scripts/adkw check-backlog`
  passed. The four implementation issues now record the successful full gate;
  the two upstream-review follow-ups have no implementation evidence yet.

Native AARCH64 execution and an exhaustive kernel/version matrix were not run.
The local structural checks and finite regressions are not a formal proof of
policy correctness; kernel verification and installation-result checks remain
mandatory.
