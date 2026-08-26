---
title: "Tier E WP-07: Container metadata association"
severity: "ENHANCEMENT"
status: "open"
priority: medium
component: "ebpf-prototype"
target_modules:
  - "ebpf-prototype"
target_files:
  - "ebpf-prototype/daemon/"
effort: "medium"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260825-023935-tier-e-wp-05-concurrency-stress.md"
paperclip_issue_id: 13e0f59f-e306-4392-b333-6293d9163881
---

# 🟢 [Severity: ENHANCEMENT]: WP-07 — Container Metadata Association

**Context:** Attribution correctness needs NO PID-namespace translation (both uprobe and
sys_enter run on the real host task — the uprobe design's core win). But consumers (Kubescape,
reports) need container/workload metadata attached to events. That association is metadata only;
getting it wrong must degrade enrichment, never attribution.

Design reference: [tier-e-design.md §5](../../designs/profiler/tier-e-design.md).

**Needed:**

1. Resolve container identity for a target process: cgroup path (`/proc/<pid>/cgroup` v2),
   container runtime metadata available on the host node.
2. Record namespace facts for reporting (PID ns, user ns) without using them in any correctness
   path.
3. Emit metadata alongside events (or as periodic side-channel), clearly separated from the
   kernel event record.

### Tests

```text
host JVM: association present, attribution unaffected
JVM in rootful Docker / Podman: correct container id
two containers running identical inner PIDs: distinct identities, zero cross-talk
container restart: new identity, old events not retroactively relabeled
```

**PR is done when:** association is proven identical inside/outside PID namespaces and its
failure mode (missing metadata) demonstrably leaves attribution intact.
