---
title: "Landlock Symlink Rejection Bypass via Canonicalization"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Landlock Symlink Rejection Bypass via Canonicalization

**Context:** The Landlock documentation states that rules explicitly use `O_NOFOLLOW` to reject symlinks and prevent attackers from redirecting path rules. However, `addRule` called `SandboxedPath.of` which used `toRealPath()`, silently bypassing this protection.
**Fix:** Switched to syntactic normalization (`Paths.get(path).toAbsolutePath().normalize()`) in `SandboxedPath.of`. This defers symlink resolution to the kernel, which then correctly rejects links via `O_NOFOLLOW`.
