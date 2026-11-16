---
title: "B4: discriminate owned O_PATH/proc-FD regular-file reopen under real Landlock in isolated JVMs"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/ffi/internal/OwnedProcFdReopenProbe.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ffi/internal/OwnedProcFdReopenProbeTest.kt"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/landlock/OwnedProcFdReopenKernelTest.kt"
  - "docs/internals/backlog/README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: B4: discriminate owned O_PATH/proc-FD regular-file reopen under real Landlock in isolated JVMs

**Context:**
B4 exact-object filesystem authority needs a real Landlock discriminator before helper confinement or stack enforcement can be promoted. Existing seams suffice for a test-only O_PATH regular-type and owned proc-FD reopen probe.

**Needed:**
1. Implement the bounded three-file test-only kernel discriminator for
   [Plan B, B4](../../superpowers/plans/2026-11-03-tier-e-owned-stack-scoping.md#b4-establish-broker-side-filesystem-authority).
   Use constrained `openat2(O_PATH|O_CLOEXEC, resolve=0x0f)`, regular-object
   metadata, and trusted owned proc-FD `openat(O_RDONLY|O_CLOEXEC)`; read the
   returned descriptor directly and retain the original pin through comparison.
2. Demonstrate an allowed object returns its pinned identity/bytes and a forbidden
   object's readable reopen returns `EPERM`/`EACCES` with no readable result.
   Exercise deterministic rename/replacement/unlink boundaries, nonregular
   rejection before readable open, and constrained path escapes.
3. Install actual Landlock only on a platform worker in an owned disposable JVM;
   retain explicit runtime support, environment, stage, descriptor and cleanup
   receipts. Unsupported, skipped or incomplete controls do not qualify the gate.
4. Run focused host/kernel checks and independent review, preserving failures.
   This mechanism experiment does not complete B4: helper sealing/provisioning,
   process-wide baseline, inherited-security equivalence, generation authority,
   full mount/hardlink races and notification injection remain separate gates.

**Status (2026-11-05):** Test-only mechanism implemented; keep this issue open
pending independent whole-task review and the controller's combined full build.
No production, shared-harness, native API, Gradle or default behavior changed.

**Initial measured focused checks:** Seven host tests failed against the compiled
unimplemented fixture, then passed. Final host XML: 7 tests, 0 failures/errors/
skips. Fresh host `:enforcer:integrationTest --tests
io.mazewall.landlock.OwnedProcFdReopenKernelTest --rerun-tasks --no-build-cache`
completed: 1 JUnit test, all 9 disposable children, 8 mutation variants, no skips.
The existing `compileVulnerableRop` prerequisite was explicitly authorized and
executed; no A4 discriminator native rebuild was performed.

- Under actual platform-worker Landlock, constrained O_PATH acquisition succeeded
  for allowed and forbidden regular objects. Allowed trusted proc-FD reopen
  returned matching regular identity and original bytes through the returned FD
  (`NativeMemory.read`), with the O_PATH pin live through EOF.
- Forbidden readable `openat` returned native `EACCES` (13), exactly one attempt,
  zero readable acquisitions and zero reads. Metadata rejection was not accepted
  as a permission refusal. Moving outside the granted hierarchy also returned
  `EACCES` at both barriers.
- Target/root rename-replacement and unlink returned original pinned bytes at
  both after-acquisition and after-first-metadata barriers. Directory, FIFO, bound Unix socket
  and `/dev/null` were rejected before readable open. Traversal, final/intermediate/
  dangling symlinks and an actual user proc magic-link acquisition were rejected.
- Final receipts confirm worker, descriptor, socket and child retirement; all
  attempted small fixture trees and failure streams/XML remain retained.

**Environment/limits:** Linux `7.2.11-x64v3-xanmod1`, amd64, Ubuntu JDK
`25.0.4.1+1-1-26.04.4-Ubuntu`, Landlock ABI 10, btrfs application fixtures and
Linux dev/inode-backed file keys. Rules handled mask 65535, no network rights;
application directory grant 12, exact module-image/JAR support grant 4. No broad
proc/runtime/class-output-directory grants, seccomp installation or FD injection.
Block-device and bind-mount/mount-race fixtures remain explicitly unqualified.
LSM names/configuration were readable, but per-task `attr/current` returned
`EINVAL`; arbitrary external worker restrictions and helper equivalence remain
unknown and block deployment qualification. Existing formatter/lint/Detekt tasks
passed after corrections; 13 new integration warning-level diagnostics remain
visible for review (complexity/length/nesting, numeric/style and reflective-entry
usage), with no suppressions or baseline expansion.

**Retained evidence:** `.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/`
`b4-kernel-probe-report.md` and `b4-kernel-probe-001/` contain numbered commands,
source hashes/snapshots, original failures and fresh focused XML/child receipts.
This is not B4 completion, helper/whole-JVM confinement, process-wide Tier 1/no-exec
qualification, cold-runtime closure, or T4 qualification. The remaining gates in
Needed item 4 and the broader Plan B remain open.

**Fix round 1/5 (2026-11-05), pending scoped re-review:** Independent review found
two MEDIUM exceptional-path defects. The actual descriptor inventory now fails
closed on permission, malformed flags, unexpected I/O and fatal failures. Only
`NoSuchFileException` plus a second link read confirming disappearance produces
the narrow transient receipt; missing fdinfo for a still-live FD is a failure.
Worker/FIFO operation failures are captured before bounded retirement, with each
retirement/stream-close failure merged through `CleanupFailures.merge`, preserving
original identities, fatal-first ordering and interruption. All FIFO stream closes
are attempted independently. While a worker remains alive, shared setup owners
are retained for isolated-child disposal and its mutable trace is not read.

Compiling shared-path RED: 6 regressions executed, 5 failed for the reviewed defects;
an additional inventory parser RED executed 2 tests with 1 failure. Corrected
regressions: 7 passed. Final original host suite: 7 passed. The single fresh final
uncached class run executed **8 JUnit tests** (7 new regressions plus 1 mechanism
test), **9 actual child cases / 8 mutation variants**, zero failures/errors/skips.
Allowed/original-object direct reads, native forbidden/move-outside `EACCES` (13),
nonregular/escape rejections and successful retirement receipts remain intact.
Formatting/lint/Detekt tasks passed without suppressions or baseline changes;
the integration file now has **19 warning-level diagnostics** (the original review
judgments remain applicable, plus numeric/throw-count diagnostics in new checks).
All 28 original fixture trees/evidence remain retained, alongside 9 new trees.

Correction-only diff and fresh evidence/report are under
`.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/`
`b4-kernel-probe-fix1-001/` and `b4-kernel-probe-fix1-report.md`. Only this issue
and the integration test changed from the controller's exact fix base. Independent
re-review and combined full build remain required; this issue stays open.

---
<!-- id: issue-20261105-003742-653-b4-discriminate-owned-o-path-proc-fd-regular-file-  file: issue-20261105-003742-653-b4-discriminate-owned-o-path-proc-fd-regular-file-.md -->
