---
title: "Testability Limitations due to Native Coupling prevent 80% coverage"
status: "open"
priority: 10
severity: "MEDIUM"
scope: "all"
dependencies: []
target_files:
  - "io.mazewall.LinuxNative"
  - "io.mazewall.enforcer.supervisor.SupervisorDaemon"
  - "io.mazewall.profiler.engine.ProfilerDaemon"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# Description

**Context:**
Achieving >80% test coverage in the `enforcer` and `profiler` modules is hindered by hard dependencies on native FFM APIs (e.g., `LinuxNative`, BPF instruction sets, memory reading via `process_vm_readv`) within domain logic classes. Since the project disallows external mocking frameworks (like MockK) and relies on actual kernel capabilities when run, many classes that manage state machines, sockets, or daemon coordination cannot be fully exercised purely in host unit tests without causing real system side effects or kernel rejections.

# Impact
Many test paths require either running under a fully supported Linux kernel with specific privileges (which unit tests often don't have) or heavily refactoring the codebase to accept dependency-injected interfaces for the native OS boundary. Without this, coverage hits a ceiling ~50% in Enforcer and ~40% in Profiler because failure paths, IPC protocol logic, and state transitions are fundamentally bound to native operations. Packages like `io.mazewall.enforcer.supervisor` (9% coverage), `io.mazewall.ffi.networking` (7%), and `io.mazewall.profiler.engine` (41%) cannot be safely instantiated and driven through their lifecycles without a real kernel tracee/tracer relationship.

# Proposed Solution
Extract the direct `LinuxNative` static calls into a mockable/stubbable interface that can be injected into the `SupervisorDaemonManager`, `ProfilerDaemonManager`, and the protocol handlers. This Dependency Inversion (DIP) will allow comprehensive unit testing of the state machines and IPC parsing without triggering real syscalls or failing due to unsupported host features. We also need to introduce abstract interfaces for Socket and Process management to fake them in memory.

**Needed:**
1. Implement a fix based on the issue description.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: `Unknown`
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] Tests verify the fix works as expected.
- [ ] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
