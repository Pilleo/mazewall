---
title: "STRICT_SANDBOX crashes on Linux kernels < 6.10 (Landlock ABI < 5) due to unblocked `ioctl`"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: STRICT_SANDBOX crashes on Linux kernels < 6.10 (Landlock ABI < 5) due to unblocked `ioctl`

**Status:** RESOLVED (June 2026)
**Target:** `io.mazewall.landlock.Landlock` (specifically `validateAbiSupport`) and `io.mazewall.PolicyPresets` (specifically `PURE_COMPUTE`)
**Context & Proof:** The `Policy.PURE_COMPUTE` preset previously did not block `Syscall.IOCTL`. Running `PURE_COMPUTE` on a system with Landlock ABI < 5 (Linux < 6.10) caused `validateAbiSupport` to throw a fatal `UnsupportedOperationException` unconditionally.
**Fix:**
1. Updated `validateAbiSupport` to query and respect `Platform.configuredFallback()` before throwing an `UnsupportedOperationException`.
2. Explicitly blocked `Syscall.IOCTL` in the `PURE_COMPUTE` preset definition to ensure clean initialization and robust containment on older Linux kernels by default.
3. Verified via unit and build verification tests.
