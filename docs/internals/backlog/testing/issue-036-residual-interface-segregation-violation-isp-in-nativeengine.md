---
title: "Residual Interface Segregation Violation (ISP) in `NativeEngine`"
severity: "HIGH"
status: "open"
priority: 5
dependencies: []
component: "shared"
effort: "large"
autonomy: "supervised"
solution_approved: false
---


# 🔴 [Severity: MEDIUM]: Residual Interface Segregation Violation (ISP) in `NativeEngine`

**Target:** `io.mazewall.NativeEngine`
**Context:** While sub-engines (FileSystem, Networking) were extracted, the main `NativeEngine` interface still exposes low-level, unconstrained `syscall`, `ioctl`, and `poll` methods. Any component requiring the engine for simple file operations is unnecessarily exposed to raw syscall capabilities.
**Needed:** Segregate raw syscall operations into a separate `RawSyscallOperations` interface, ensuring higher-level components only depend on restricted, domain-specific traits.

## Solution Options

### Option A
(To be filled)

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ]

**Implementation Hints:**
-
