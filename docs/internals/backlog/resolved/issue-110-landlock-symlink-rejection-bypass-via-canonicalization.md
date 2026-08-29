---
title: "Landlock Symlink Rejection Bypass via Canonicalization"
severity: "RESOLVED"
status: "resolved"
priority: medium
paperclip_issue_id: ab50fdcd-1d7f-4c70-ad21-b39871f6f3b5
paperclip_identifier: MAZ-287
---

# ✅ [RESOLVED]: Landlock Symlink Rejection Bypass via Canonicalization

**Context:** The Landlock documentation states that rules explicitly use `O_NOFOLLOW` to reject symlinks and prevent attackers from redirecting path rules. However, `addRule` called `SandboxedPath.of` which used `toRealPath()`, silently bypassing this protection.
**Fix:** Switched to syntactic normalization (`Paths.get(path).toAbsolutePath().normalize()`) in `SandboxedPath.of`. This defers symlink resolution to the kernel, which then correctly rejects links via `O_NOFOLLOW`.
