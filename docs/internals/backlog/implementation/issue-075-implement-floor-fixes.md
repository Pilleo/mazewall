---
title: "🔴 [REOPENED]: JVM Invariant Syscall Floor Implementation Fixes"
severity: "HIGH"
status: "open"
priority: 1
dependencies: ["issue-075"]
component: "enforcer"
effort: "medium"
---
### Context
Initial attempt to expand the JVM invariant syscall floor (PR #98) identified several critical mapping and stability issues. The JVM requires a much larger set of syscalls for modern features (Loom, ZGC) and networking. Additionally, 64-bit register garbage causes BPF inspection failures.

### Problems Found
1. **Broken Wiring**: `SyscallMapper` and intermediate mappers were not updated with new mappings, causing `numberFor` to return `-1` even for valid syscalls.
2. **Missing Network Floor**: `getsockopt`, `setsockopt`, `getsockname`, `getpeername`, and `recvfrom`/`sendto` are required for JVM stability.
3. **Register Garbage**: 64-bit equality checks in BPF fail on 32-bit arguments (ioctl, prctl) due to high-word garbage.
4. **Brittle Tests**: `BpfFilterTest` asserts exact instruction sequences which break under BPF optimization.

### Needed
- Fix the multi-stage mapping in `Syscall.kt`.
- Implement `ArgCheck.EqualsAny32` and update `BpfFilter` to use it for 32-bit arguments.
- Expand `jvmCriticalNrs` and `jvmDefaultAllowedNrs` sets in `BpfFilter.kt`.
- Relocate `JvmFloorWorkload` to `src/sharedTest`.
- Update `BpfFilterTest` to use robust property-based assertions.

See `docs/internals/research/jvm-syscall-floor-implementation-findings.md` for full technical details.
