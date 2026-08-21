---
title: "Advance the generation when adopting a new descriptor"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt"
effort: "small"
autonomy: "supervised"
related_pr: 512
related_thread: 3825912176
---

# 🟡 [Severity: MEDIUM]: Advance the generation when adopting a new descriptor

**Review (2026-08-21):** Still present. `unsafe()` not reviving **retired** fds is already fixed (`113000-no-revive-retired-descriptors`). This issue is the **still-live** slot case.

**Review (2026-08-21, later):** Resolved. `FdEpoch.adoptKernelReuse` force-retires then claims; `FileDescriptor.adopt` and `claimDupIfNeeded` use it. `claimOpen` still keeps a live owner's generation. `SafeLocalFd(Int)` wraps kernel-minted integers with `adopt`; `accept4` uses the existing handle instead of adopting a second time.

**Current tree:** `FileDescriptor.adopt()` calls `open()` → `FdEpoch.claimOpen()`. `claimOpen` does `if (cur.live) return cur.generation` **without bumping**. If the previous owner closed the integer outside the typed wrapper, the slot can still be `live=true`. The kernel then reuses that integer for `open`/`accept`/`dup`/`SCM_RIGHTS`. `adopt(newFd)` reuses generation N, so leftover `Open` tokens for the **old** resource compare equal / `isLive` with the new one.

`replace()` already `forceRetire` then claims. `adopt()` does not.

**Do not:**
- Change `unsafe()` again (retired path already returns a dead token).
- Make `claimOpen` always bump even for the same live owner (that would invalidate in-use tokens on a second `open()` of the same fd).
- Use `Thread.sleep` or “close harder” in tests instead of driving `FdEpoch` states.

**Do:**
1. `adopt()` (kernel-reused integer) must install a **new** generation, invalidating leftover tokens. `forceRetire` + claim, or a `claimAdopt` that increments even if `live`.
2. Ordinary `open()` of a still-owned live fd may keep the current generation (same resource).
3. Document: wrappers that observed close(2) themselves must `retire` first; `adopt` is for integers the kernel just minted.

**Tests:** Mark fd X live at gen 1 without retiring. `adopt(X)` → generation `!= 1`. Old token `isLiveForIo` is false. `replace()` behavior unchanged.

**Codex:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825912176
