---
title: Network Isolation via Namespaces (`CLONE_NEWNET`)
severity: ENHANCEMENT
status: open
priority: low
dependencies: []
target_files:
- enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt
target_modules:
- :enforcer
component: enforcer
effort: medium
paperclip_issue_id: 171c7720-b504-4b48-b530-43431571e9d6
---

# 🔵 [Severity: ENHANCEMENT]: Network Isolation via Namespaces (`CLONE_NEWNET`)

> **2026-08-25: Design ready** — `docs/internals/designs/enforcer/network-namespace-design.md`
> specifies spawn-time user+net namespace on portal-style workers (unprivileged
> CLONE_NEWUSER|CLONE_NEWNET chain, uid_map write, socketpair RPC, fail-closed probe).
> Two Jules implementation attempts failed deterministically on the missing privilege
> chain before this design existed (evidence on board MAZ-104). Implementation deferred
> until design is ratified; loop testing must use trivial tasks only.

**Context:** Seccomp effectively blocks *new* network connections (`socket`, `connect`), but it cannot prevent data exfiltration over a pre-existing, inherited network file descriptor if the policy permits `write` or `send` calls (which are often needed for file I/O).
**Needed:** Propose an optional process-wide `CLONE_NEWNET` initialization to create a private network namespace. This physically removes the host's routing tables and network interfaces (leaving only loopback), ensuring that even if a process possesses an open socket FD, it has no route to the external network, providing a stronger architectural guarantee than syscall blocking alone.
