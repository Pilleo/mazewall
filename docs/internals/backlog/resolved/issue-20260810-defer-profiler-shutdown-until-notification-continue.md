---
title: "Defer profiler shutdown until the pending notification is continued"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
effort: "small"
autonomy: "supervised"
solution_approved: true
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: HIGH]: Defer profiler shutdown until the pending notification is continued

**Context:** A socket read containing both the profiler ACK and shutdown command invoked the shutdown callback while the current seccomp notification was still pending. Reactor cleanup could close the listener before the daemon sent `SECCOMP_USER_NOTIF_FLAG_CONTINUE`.

**Needed:** Carry the shutdown request in the successful handshake result and invoke the shutdown callback only after the continue response has completed. A regression test must verify this ordering.
