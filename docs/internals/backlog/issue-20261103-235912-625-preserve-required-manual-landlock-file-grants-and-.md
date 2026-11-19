---
title: "Preserve required manual Landlock file grants and acquisition failures"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt"
target_symbols:
  - "applyUserRules"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve required manual Landlock file grants and acquisition failures

**Context:**
Repository-wide review, 2026-11-04: [BPF-06](../reviews/2026-11-04-repository-review-findings.md#bpf-06). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · source-confirmed correctness/fail-closed-reporting defect; mask computation corroborated, kernel operation not run · high confidence · manual Landlock installation. NEW bounded gap, not the prepared-resource path.**

**Trigger/evidence:** Grant write access to an existing regular file outside automatic runtime grants. `landlock/Landlock.kt:585-595` includes WRITE_FILE, MAKE_REG, MAKE_DIR, REMOVE_FILE, REMOVE_DIR and TRUNCATE. `:491-500` strips only READ_DIR/MAKE_DIR/REMOVE_DIR for files, leaving **MAKE_REG and REMOVE_FILE**. Linux rejects non-directory rules with those directory-content rights ([v6.18 `landlock_append_fs_rule`, `ACCESS_FILE`](https://github.com/torvalds/linux/blob/v6.18/security/landlock/fs.c)). `:503-518` logs that EINVAL and proceeds. `LandlockState.kt:360-367` can then restrict and return Applied; `ContainedExecutors.kt:473-487` publishes success. The host probe computes the exact invalid remaining bits using the current helper; no add-rule syscall was executed.

The same manual path also logs and returns on explicit path-open errors, including EPERM/EACCES (`Landlock.kt:424-430,480-488`), although runtime-classpath errors and prepared grant errors reject (`:386-398`; `PreparedLandlock.kt:107-116`). Failing to acquire a requested grant must not masquerade as a complete installed recipe.

**Effect/correction:** An explicitly allowed exact-file write is denied after a successful receipt; permission errors are downgraded to warning-only lost grants. This narrows rather than exposes access, but can fail production work after irreversible restriction and violates the required error contract. Derive valid file/directory rights from the acquired object, reuse the correct prepared grant semantics, and reject required user-rule/open failures rather than swallowing EINVAL/EPERM/EACCES. Preserve owned cleanup and original errno.

**Regression/mapping:** Add actual existing-regular-file write success plus forbidden-sibling denial, a kernel-faithful mask test, and explicit user-path open/add failure injection under every fallback choice. Existing `LandlockFailureModeTest.kt:242-285` covers missing-path write and existing-file **read**, not existing-file write; native write fixture grants a directory (`integrationTest/.../LandlockTest.kt:98-108`). No matching active exact-file correction found. Related `issue-20261008-034648-678-bind-prepared-core-resources-and-reviewed-operatio.md` has the correct prepared implementation; do not regress it or reopen its fixed slice.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235912-625-preserve-required-manual-landlock-file-grants-and-  file: issue-20261103-235912-625-preserve-required-manual-landlock-file-grants-and-.md -->
