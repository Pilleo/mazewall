---
title: "Supervisor Proxy Pattern (FD Injection) & Stacktrace Scoping"
severity: "ENHANCEMENT"
status: "open"
priority: 2
dependencies: []
component: "unknown"
effort: "medium"
---

# 🔵 [Severity: ENHANCEMENT]: Supervisor Proxy Pattern (FD Injection) & Stacktrace Scoping

**Target:** `docs/internals/design-specs/supervisor-proxy-design.md`
**Context:** Thread-scoped network or file containment currently relies on static kernel rules (BPF/Landlock). These cannot provide context-aware authorization (e.g., "only allow this specific Java method to open a database connection") and are vulnerable to path traversal or TOCTTOU attacks if the sandbox needs to access dynamic files.
**Needed:** Implement a `USER_NOTIF` daemon that acts as an Authorization Proxy. The BPF filter handles fast-path I/O but punts rare, sensitive operations (like `execve` or connection pooling) to the proxy.
1.  **Stacktrace Scoping:** The proxy maps the trapped thread's OS TID to a JVM `Thread` and inspects `getStackTrace()` to authorize the call. This is protected from spoofing by `mazewall`'s Tier 1 `NO_EXEC` memory baseline.
2.  **FD Injection:** For file access, the proxy executes the open and injects the FD via `SECCOMP_IOCTL_NOTIF_ADDFD`.
3.  **Confused Deputy Mitigation:** The proxy must NEVER use string manipulation for path resolution. It must strictly use `openat2` with the `RESOLVE_BENEATH` flag to ensure the kernel physically blocks TOCTTOU symlink escapes.
For full architectural details, see `design-specs/supervisor-proxy-design.md`.
