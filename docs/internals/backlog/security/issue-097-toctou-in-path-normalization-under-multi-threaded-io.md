---
title: "TOCTOU in Path Normalization under Multi-Threaded I/O"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "unknown"
effort: "small"
---

# 🔴 [Severity: MEDIUM]: TOCTOU in Path Normalization under Multi-Threaded I/O

*   **Dimension:** TOCTOU & Concurrency
*   **Target Area:** `io.mazewall.SbobParser`
*   **Failure Hypothesis:** A profiled application operates on a directory symlink that is constantly being updated by a sibling thread or background process (e.g. `/app/current -> /app/v1` switching to `/app/v2`). If the Iterative Profiler records the resolved target (`/app/v1/file`), but by the time the `SbobParser` generates the Landlock policy the symlink points to `/app/v2`, the generated policy will hardcode `/app/v1`, denying access to the application in production.
*   **Context & Proof:** Landlock's absolute path resolution binds strictly to the inode at `addRule` time. Dynamic symlinks or active directory swaps (like Capistrano deployments) break statical Landlock profiling.
*   **Cascading Risk Potential:** Medium DX friction. Applications using atomic directory swapping will fail under strict Landlock profiles.
*   **Recommendation:** Document the incompatibility of Landlock rules with atomic directory symlink swapping, and advise users to profile and restrict the parent umbrella directory (`/app/`) rather than the dynamic target.
