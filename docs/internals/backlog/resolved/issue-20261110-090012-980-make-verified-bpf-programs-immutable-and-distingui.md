---
title: "Make verified BPF programs immutable and distinguish static verification from installation"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/BpfProgram.kt"
target_symbols: 
  - "BpfProgram"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make verified BPF programs immutable and distinguish static verification from installation

**Context:**
P1 security invariants (:enforcer, :platform where needed, effort medium, risk medium, review required). Coordinate with the typed-instruction hierarchy issue to avoid conflicting API changes. Current BpfProgram<BpfStatus.Unverified|Verified> has weaknesses: (1) BpfProgram takes a List<BpfInstruction> without defensive copy and BpfStaticVerifier.verify() builds the verified program from the same list; Kotlin List is read-only, not immutable, so mutation after verification is a TOCTOU risk. (2) Internal Kotlin code can construct BpfProgram<BpfStatus.Verified> directly without BpfStaticVerifier, weakening the type-parameter guarantee. (3) BpfStatus.Verified docs claim verified and installed by the kernel, but verify() is local static analysis only; installation is context-dependent (thread/process) and must not be an intrinsic property of reusable bytecode. Required: (1) real immutable instruction snapshot via defensive copy at the transition into a verified program; callers cannot mutate through exposed list; no third-party persistent-collection dependency; (2) restrict verified construction via restricted constructors and controlled factories or a dedicated verified-program wrapper; keep a test-only mechanism to build malformed unverified programs for negative verifier tests; (3) naming/docs: Unverified = constructed, not locally validated; StaticallyVerified = accepted by MazeWall static checks; keep old name temporarily only if compatibility requires; (4) keep installation state separate: reuse existing installation-state machinery or a scoped installation result; do not put Installed in the BPF program generic type; preserve distinction between kernel acceptance, current installation mode, and optional post-install behavior verification; (5) PureJavaBpfEngine.install() must receive the exact immutable snapshot that passed validation, with no mutation/replacement between validation and native serialization and no separate mutable copies unless verified-to-installed relation is explicit and checked. Tests: A) mutable-list-backed unverified program, verify, mutate original, verified stream unchanged; B) attempt mutation through exposed runtime collection interfaces (e.g. cast to MutableList) fails; C) internal code cannot manufacture verified state without the verifier; D) instruction stream serialized for installation equals the verified snapshot; E) existing installation and self-verification tests unchanged incl. fail-closed errors. Acceptance: verified data immutable; original-collection mutation cannot affect verified output; status cannot be forged via normal construction APIs; static verification clearly distinguished from kernel installation; installation consumes validated snapshot; installation-state logic reused not duplicated; no new dependencies; unit and relevant integration tests pass; docs reflect actual guarantees without claiming formal proof of seccomp policy safety. Related: MAZ-214, MAZ-288, docs/internals/backlog/issue-20261007-074331-621-require-current-installation-verification-instead-.md and docs/internals/backlog/issue-20261007-014904-639-capture-optional-self-verification-authority-befor.md (runtime installation evidence; do not duplicate or interfere). Out of scope: policy semantics, redesigning installation state machine, new runtime self-verification, claiming static verification proves absence of vulnerabilities. Principle: the type system prevents structural mistakes; the verifier validates the program; Linux remains the final authority on installation.

**Needed:**
1. Add failing test A (mutate source list after verify) in the BpfStaticVerifier tests, then snapshot instructions defensively at verification and restrict Verified construction

## Resolution Evidence (2026-11-21)
- Already resolved in `BpfProgram.kt` via immutable snapshot copy and private `Verified` proof construction through `BpfStaticVerifier`; verified in `BpfVerificationBoundaryTest` and `:enforcer:test`.

---
<!-- id: issue-20261110-090012-980-make-verified-bpf-programs-immutable-and-distingui  file: issue-20261110-090012-980-make-verified-bpf-programs-immutable-and-distingui.md -->
