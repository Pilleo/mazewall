# Codex PR #512 Resolution Tracker

This document tracks the resolution status of all Codex-generated review comments on PR #512.

## Summary Statistics

| Category | Total | Addressed | Not Changing | Unresolved | Backlogged |
|---|---|---|---|---|---|
| P1 | 50+ | 26 | 3 | 15+ | 7 |
| P2 | 41+ | 16 | 0 | 25+ | 1 |

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

#### P2 Issues (BACKLOGGED)
1. **3819861572** → `issue-20260821-000008`: Mark discarded strace records as dropped

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
