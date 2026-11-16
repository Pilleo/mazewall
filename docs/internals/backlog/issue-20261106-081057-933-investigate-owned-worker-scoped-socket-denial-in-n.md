---
title: "Investigate owned-worker scoped socket denial in non-root checkpoint profile"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "enforcer/src/integrationTest/kotlin/io/mazewall/OwnedScopingNativeTest.kt"
target_symbols:
  - "OwnedScopingNativeTest"
open_questions: false
---

# 🟡 [Severity: HIGH]: Investigate owned-worker scoped socket denial in non-root checkpoint profile

**Context:**
Full gate012 had one owned-scoping failure independent of seventeen helper mapping rejections: the first authorized loopback connect returned EPERM wrapped as ContainmentViolationException. Later instrumentation identified the native boundary below. This profile used UID1000 NNP1 and only CHECKPOINT_RESTORE; no product defect or ptrace cause is proven.

**Needed:**
1. Preserve the focused evidence and complete the separately controlled full gate under the newly declared test baseline; do not bypass library checks or add capabilities.

## Native boundary and historical policy audit

The operator approved investigation. Transient instrumentation of the actual helper
observed `AskJvm` followed by the authorized `InjectFd` decision, then native
`pidfd_getfd` returning EPERM. All temporary production edits were removed and
verified against pre-instrumentation hashes; no library checks were changed.

A bounded negative probe returned EPERM for an invalid pidfd in the existing
container, while identical host code returned EBADF. The daemon reports built-in
seccomp; its precise effective baseline is not established. Evidence is retained
under `owned-scoping-fix-001/`, including the temporary diff and probe receipts.

The operator conditionally approved a container-only flags-zero syscall allowance,
with no extra capabilities or unconfined mode, only after baseline audit. That audit
could not establish equivalence: public engine tag/commit lookups failed; local Go
metadata identifies seccomp module v0.2.4 but has no checksum establishing unmodified
vendor code, and the running executable identity could not be confirmed. No
replacement profile was prepared or applied at that point; running-daemon equivalence
remains unproven.

## New declared baseline and focused result

The operator subsequently selected official `moby/profiles/seccomp` v0.2.4 commit
`245180c51918481c0525424b3ee025d2b435d46c` as a **new declared test baseline**, not
as an equivalent of the running daemon's built-in policy. The controller independently
retrieved the pinned JSON and matched SHA-256
`785b2429264afba4d594320337cb17f144f3c7d51585f9805eef72e28f4f9334`.
The candidate adds only `ALLOW pidfd_getfd` with argument 2 equal to zero; its
`check.sh` and independent policy review passed. Paired C probes returned EPERM for
flags 0 and 1 under the baseline, and EBADF for flags 0 / EPERM for flags 1 under
the candidate. Both exited 0 with UID 1000, NNP 1 and all five capsets limited to
CHECKPOINT_RESTORE.

The actual owned socket test passed 1/1 with no skips, aborts or failures under the
candidate; its container exited 0, PID 0, without OOM. Evidence is retained in
`b4-d2-implementation-001/owned-scoping-fix-001/seccomp-policy-002/` and
`b4-d2-implementation-001/focused-kernel-runner-002/attempt-001/`. No library/source
permission bypass or added capability was used. This is not a default-built-in or
default-parallel pass: full012 remains the last failed full gate and full015 has not
launched. Keep this issue open and full acceptance pending.

---
<!-- id: issue-20261106-081057-933-investigate-owned-worker-scoped-socket-denial-in-n  file: issue-20261106-081057-933-investigate-owned-worker-scoped-socket-denial-in-n.md -->
