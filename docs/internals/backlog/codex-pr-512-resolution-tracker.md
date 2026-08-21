# Codex PR #512 Resolution Tracker

This document tracks the resolution status of all Codex-generated review comments on PR #512.

## Summary Statistics

| Category | Total | Addressed | Not Changing | Unresolved | Backlogged |
|---|---|---|---|---|---|
| P1 | 50+ | 26 | 3 | 15+ | 11 |
| P2 | 41+ | 16 | 0 | 25+ | 28 |

## Resolution Legend

### ✅ Addressed Comments

Comments with "Addressed in [commit]" replies are resolved by the specified commit.

#### Addressed by commit 74ad6616
"Fail-close profile coverage and policy composition review findings"
- **3796525617**: Legacy ProfilingResult coverage incomplete by default → 3-arg constructor now defaults `coverage.complete=false`
- **3796525626**: Require evidence that hybrid mode disabled io_uring → `DISABLED_FOR_HYBRID` only inferred when `ProfileEnvironment.ioUringDisabled == true`
- **3796525631**: Do not treat absent eBPF events as kernel blocking → eBPF with no IoUring observation is `UNSEEN` (incomplete)
- **3796525649**: Execute the advanced policy configuration block → `advanced { }` now calls `inner.apply(block)`
- **3796525668**: Make restrictive composition honor default actions → `PolicyDefinition.combine()` now uses effective default actions
- **3796525673**: Frame full supervisor responses over the stream socket → `sendResponse` loops `write()` until full `SUPERVISOR_RESPONSE_SIZE` sent
- **3796525679**: Propagate dropped USER_NOTIF events into coverage → `Profiler.profile` passes `sessionListener.eventQueue.droppedCount` into `ProfilingCoverage`
- **3797199297**: Clear executable mappings for native-image profiles → `forRuntime()` assigns `allowMmapExec` from selected `RuntimeProfile`
- **3797199311**: Mark unresolved path-bearing events incomplete → path-bearing strace lines with empty paths yield `FAILED`/`MIXED`

#### Addressed by commit 329f317e
"Fail-close policy compilation when coverage evidence is missing"
- **3819192633**: Reject policy compilation when coverage is absent → `BillOfBehavior.toPolicy()` treats missing coverage as incomplete
- **3819192640**: Account for malformed eBPF event lines → `EbpfEventParser.parse` now returns `droppedLines`
- **3819192647**: Preserve the syscall kind → sockaddr-to-Connect conversion runs only when `syscallName == CONNECT`
- **3819192657**: Preserve raw USER_NOTIF coverage when attaching session metadata → `attachCoverage` seeds USER_NOTIF drain with `raw.coverage.droppedEvents`
- **3819192671**: Observe the profiled block before completing eBPF coverage → recorded-eBPF profile ` { }` runs lambda for value then compiles
- **3819192677**: Preload concrete TraceEvent classes before installing USER_NOTIF → `ProfilerAckPreload` Class.forName now includes `TraceEvent.Generic`/`Open`/`Exec`/`Mmap`
- **3819192682**: Send the exec rewrite acknowledgment reliably → `sendExecRewriteAck` uses same `SocketIo.writeFully` path as framed responses

#### Addressed by commit 12ff3785
"Keep path-resolution evidence and stop widening OPENAT2 to writes"
- **3819324196**: Preserve unresolved path evidence when stacks are disabled → `ProfilingResult` now carries observations
- **3819324201**: Avoid granting write access for every openat2 → `OPENAT2` uses same flag classification as `OPEN`/`OPENAT`

#### Addressed by commit 6b0dd1cf
"Do not treat recorded eBPF logs or unexpected EOF as complete coverage"
- **3819470483**: Reject non-contemporaneous hybrid eBPF drains → hybrid attach infers coverage from live USER_NOTIF observations only
- **3819470488**: Distinguish graceful drains from unexpected EOF → `drainComplete` set on EOF only after `passThrough()`/`close()` requested graceful drain

#### Addressed by commit c6923e5b
"Fail-close coverage, Landlock intersection, and validation preloads"
- **3819590937**: Preload verdict variants before installing USER_NOTIF → `ValidationListenerPreload` now Class.forName's `JvmVerdict.Deny`/`Allow`/`InjectFd`
- **3819590945**: Enforce recorded network endpoints when compiling policies → `toPolicy()` refuses BoB with non-empty connects unless `allowIncomplete=true`
- **3819590971**: Intersect empty Landlock capability sets → Landlock `combine`/`intersection` uses every `enforceLandlock` policy's path sets
- **3819590980**: Reject hybrid coverage without io_uring disable evidence → `HYBRID_NO_URING` is incomplete unless `environment.ioUringDisabled == true`
- **3819590990**: Include every resolved syscall in path coverage → path-bearing coverage now includes `READLINK`/`READLINKAT`/`CHROOT`/`UTIME`/`UTIMES`

#### Addressed by commit aa355303
"Parse IPv6 connects, require every rename operand, and CLOEXEC exec fds"
- **3819751044**: Parse IPv6 connect endpoints before compiling → strace `parseConnect` handles `sin6_port`/`inet_pton(AF_INET6)`
- **3819751056**: Require every pathname operand to resolve → rename/link/symlink-family events require two path operands

### ⏭️ Not Changing Comments

Comments with "Not changing" replies are deemed out of scope or acceptable as-is.

- **3796525635**: Preserve the existing public enforcer package classes → This artifact is 0.0.1-prealpha, breaking changes acceptable
- **3796525654**: Rewrite exec registers before continuing the syscall → Register rewrite not yet implemented (issue-20260817-033800), parking SETREGS+CONTINUE
- **3797199294**: Preserve the existing install method descriptors → `installOnCurrentThread`/`installOnProcess` already return `InstallationReceipt`; void variants preserved via deprecated overloads

### 📋 Unresolved Comments (Backlogged)

#### P1 Security Issues (BACKLOGGED)
1. **3819861561** → `issue-20260821-000011`: Apply Landlock for empty restrictive intersections
2. **3819861566** → `issue-20260821-000012`: Preserve explicit open denials during restrictive composition
3. **3819861580** → `issue-20260821-000009`: Detect notification defaults during installation assessment
4. **3825587200** → `issue-20260820-214309-route-custom-supervised-syscalls`: Route custom supervised syscalls through JVM validation
5. **3819861587** → `issue-20260821-113000-validation-deadline-full-frame`: Keep validation deadline while reading full frame
6. **3819982838** → `issue-20260821-113000-perform-exec-rewrite-before-ack`: Perform exec rewrite before acknowledging
7. **3819982841** → `issue-20260821-113000-constrain-exec-destinations`: Constrain exec destinations during policy compilation
8. **3819982854** → `issue-20260821-113000-classify-create-truncate`: Classify create/truncate as filesystem mutations
9. **3819982863** → `issue-20260821-113000-no-revive-retired-descriptors`: Do not revive retired descriptors through unsafe
10. **3823789271** → `issue-20260821-113000-start-ebpf-collector-before-workload`: Start eBPF collector before workload
11. **3823789280** → `issue-20260821-113000-require-coverage-for-dsl`: Require coverage when generating DSL

#### P1 Security Issues (BACKLOGGED - Continued)
12. **3823789292** → `issue-20260821-113000-trace-mutation-syscalls`: Trace mutation syscalls before certifying coverage
13. **3823789298** → `issue-20260821-113000-decode-openat2-open-how`: Decode openat2's open_how before injecting
14. **3823789305** → `issue-20260821-113000-forward-creation-mode`: Forward creation mode when emulating open calls
15. **3825290317** → `issue-20260821-113004-preserve-cet-override`: Preserve CET capability override during installation
16. **3825290321** → `issue-20260821-113004-preserve-void-installation-entry-points`: Preserve void installation entry points

#### P2 Issues (BACKLOGGED)
1. **3819861572** → `issue-20260821-000008`: Mark discarded strace records as dropped
2. **3796525642** → `issue-20260821-113001-terminate-sessions-after-notif-errors`: Terminate sessions after notification receive errors
3. **3796525657** → `issue-20260821-113001-reject-process-wide-user-notif`: Reject process-wide USER_NOTIF during assessment
4. **3796525664** → `issue-20260821-113001-preserve-whitespace-ebpf`: Preserve whitespace in recorded eBPF field values
5. **3797199300** → `issue-20260821-113001-worker-installing-tests-fresh-jvms`: Keep worker-installing tests in fresh JVMs
6. **3797199301** → `issue-20260821-113001-classify-mutating-iouring`: Classify mutating io_uring operations as writes
7. **3797199306** → `issue-20260821-113001-preserve-portless-ipv6`: Preserve portless IPv6 endpoints during JSON round trips
8. **3819324208** → `issue-20260821-113001-reject-process-wide-landlock-no-tsync`: Reject process-wide Landlock when TSYNC unavailable
9. **3819470485** → `issue-20260821-113002-retain-observations-in-snapshots`: Retain observations in session snapshots
10. **3819470487** → `issue-20260821-113002-restrict-quoted-paths-to-fs-syscalls`: Restrict quoted paths to filesystem syscalls
11. **3819590949** → `issue-20260821-113002-capture-every-pathname-operand`: Capture every pathname operand from strace
12. **3819590960** → `issue-20260821-113002-treat-iouring-open-modes`: Treat io_uring open modes as unresolved
13. **3819751052** → `issue-20260821-113002-gate-native-memory-assertion`: Gate native-memory assertion on actual availability
14. **3819751061** → `issue-20260821-113002-preserve-cloexec-injected-exec`: Preserve close-on-exec on injected exec descriptor
15. **3819751067** → `issue-20260821-113003-open-exec-without-read`: Open executable targets without requiring read permission
16. **3819751071** → `issue-20260821-113003-classify-every-errno-default`: Classify every errno default as an allow list
17. **3819861583** → `issue-20260821-113003-preserve-requested-cloexec`: Preserve requested close-on-exec state on injected FDs
18. **3819982846** → `issue-20260821-113003-extract-only-syscall-pathnames`: Extract only syscall pathname operands from strace
19. **3819982867** → `issue-20260821-113003-include-cet-in-assessment`: Include Intel CET support in installation assessment
20. **3823789286** → `issue-20260821-113005-count-each-unparsed-connect`: Count each unparsed connect as incomplete
21. **3823789313** → `issue-20260821-113003-report-already-active-landlock`: Report already-active Landlock in repeat-install receipts
22. **3825290323** → `issue-20260821-113004-give-errno-precedence-over-trace`: Give ERRNO precedence over TRACE during intersection
23. **3825912167** → `issue-20260821-113004-exclude-descriptor-only-calls`: Exclude descriptor-only calls from path completeness
24. **3825912173** → `issue-20260821-113004-reject-unmappable-observations`: Reject observations that cannot map to a syscall
25. **3825912176** → `issue-20260821-113005-advance-generation-on-adopt`: Advance generation when adopting new descriptor
26. **3825912180** → `issue-20260821-113005-no-read-for-o-path`: Do not grant file reads for O_PATH observations
27. **3825912186** → `issue-20260821-113005-close-injected-exec-descriptor`: Close injected exec descriptor when rewrite fails
28. **3825912190** → `issue-20260821-113005-reject-unenforceable-iouring-opcodes`: Reject unenforceable io_uring opcodes before compiling

#### Review 4987654735 (BACKLOGGED)
1. **3825587178** (P2) → `issue-20260820-214309-poll-reused-descriptors`: Do not reject reused live descriptors during poll
2. **3825587180** (P2) → `issue-20260820-214309-cet-probe-require-shstk`: Require shadow-stack support for CET probe
3. **3825587185** (P2) → `issue-20260820-214309-denylist-scope-narrowing`: Downgrade read-only deny lists to thread-local scope
4. **3825587188** (P2) → `issue-20260820-214309-ebpf-namespace-probe`: Do not use container PID 1 to identify initial user namespace
5. **3825587194** (P2) → `issue-20260820-214309-strace-drain-child-output`: Drain child output while waiting for strace
6. **3825587200** (P1) → `issue-20260820-214309-route-custom-supervised-syscalls`: Route custom supervised syscalls through JVM validation
7. **3825587202** (P2) → `issue-20260820-214309-platform-probe-short-circuit`: Check platform before probing Linux kernel features

### 🔍 Additional Unresolved Comments

There are **142+ total unresolved comments** across **66 reviews** on PR #512.

- **50+ P1 comments** without "Addressed in" or "Not changing" replies
- **41+ P2 comments** without "Addressed in" or "Not changing" replies
- **50+ other comments** (likely P2 without badge formatting)

Newer reviews with unresolved comments:
- Review 4988149166: 6 P2 comments
- Review 4988299157: 4 comments (2 P1 + 2 P2)
- Review 4988464819: 6 comments (2 P1 + 4 P2)
- And 63+ more reviews...

**Note:** The scale of Codex comments is extremely large. This document tracks the resolution status of comments processed so far. Additional comments continue to be generated by Codex and need to be triaged.

## Backlog Files Created

All backlogged comments have corresponding `.md` files in `docs/internals/backlog/{security,code_health,profiler,platform}/` with:
- Severity level (HIGH/MEDIUM/LOW)
- Component and target files
- Full context and problem description
- Impact analysis
- Required fixes
- Link to original Codex comment
