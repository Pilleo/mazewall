---
title: "Unprivileged Pivot Root (Empty `tmpfs`)"
severity: "ENHANCEMENT"
status: "open"
---

# 🔵 [Severity: ENHANCEMENT]: Unprivileged Pivot Root (Empty `tmpfs`)

**Context:** Landlock is excellent for thread-scoped restrictions, but it operates on the host's view of the filesystem. If an exploit finds a bypass in Landlock or uses a filesystem action Landlock doesn't handle yet, the host files are physically present in the mount namespace.
**Needed:** Inspired by `bubblewrap`, implement a process-wide Tier 1 initialization option that uses `unshare(CLONE_NEWUSER | CLONE_NEWNS)` at JVM startup (before background threads spawn) to `pivot_root` into a `tmpfs` bind-mount jail. This provides an absolute physical backstop to Landlock by ensuring only necessary host directories are physically present in the sandbox's mount namespace.
