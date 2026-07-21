---
title: "TOCTOU in Path Normalization `PathNormalizer.kt`"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: true
blast_radius: "medium"
reversible: true
---

# ✅ [RESOLVED]: TOCTOU in Path Normalization `PathNormalizer.kt`

**Context:**
**Hypothesis:** Can an attacker rename directory to bypass path normalizer?
`PathNormalizer` does static analysis. Does the system ensure paths aren't modified post-normalization?

**Analysis & Verification:**
1. Once path normalization resolves paths to their physical absolute locations (`toRealPath()`), the paths are passed as rules to the Landlock LSM ruleset using `landlock_add_rule`.
2. `landlock_add_rule` opens the resolved directory with `O_PATH` and associates the underlying **inode** with the rule.
3. Once the ruleset is applied to the thread/process using `landlock_restrict_self`, the Linux kernel handles path/inode resolution dynamically at the VFS layer.
4. If an attacker renames an allowed directory, Landlock remains securely bound to the original directory's underlying inode, preventing any TOCTOU bypass. The new directory created under the old name gets a different inode and is correctly blocked by Landlock.
5. This has been empirically verified and asserted via a dedicated integration test: `testLandlockDirectoryRenameResistance` in `LandlockTest.kt`.

**Needed:**
1. Verify path resolution constraints are verified against Landlock or Seccomp hooks safely.

## Solution

We added `testLandlockDirectoryRenameResistance` in `LandlockTest.kt` to verify Landlock's immunity to path-renaming TOCTOU attacks. The test configures Landlock to allow reads from `allowedDir`, renames `allowedDir` to `allowed_renamed` post-enforcement, and places a new `forbiddenDir` under the original `allowedDir` path name. It verifies that:
- Reading from the original allowed directory (now `allowed_renamed`) still succeeds, proving the inode permissions are followed.
- Reading from the new directory (at the old `allowedDir` path) is blocked, proving that Landlock is not fooled by directory renaming.

**Acceptance Criteria:**
- [x] Tests verify the fix works as expected.
- [x] Issue is fully resolved in the codebase.
