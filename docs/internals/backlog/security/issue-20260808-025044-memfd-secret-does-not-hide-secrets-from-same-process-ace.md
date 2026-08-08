---
title: "memfd_secret Does Not Hide Memory from Same-Process Native ACE"
severity: "HIGH"
status: "open"
priority: 2
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "docs/internals/designs/core/kernel-primitives-roadmap.md"
  - "docs/internals/unprivileged-bpf-jvm-opportunities.md"
effort: "small"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: memfd_secret Does Not Hide Memory from Same-Process Native ACE

**Context:** The roadmap says `memfd_secret` pages cannot be inspected even by an attacker with native ACE on an unrestricted sibling JVM thread. The mapping is intentionally accessible to the process that owns/maps it; all JVM threads share that address space. `memfd_secret` reduces exposure through the kernel direct map and unauthorized cross-process access, but it is not an intra-process confidentiality boundary and its manual explicitly avoids an absolute security guarantee. Sealed `memfd` mappings likewise do not make arbitrary same-process memory or previously writable mappings tamper-proof.

**Needed:** Remove same-process ACE and absolute dump-invisibility claims. Document the precise threat model, descriptor/mapping lifecycle, dump behavior, swap/locking considerations, and kernel availability. Require a separate process or hardware-backed key service when secrets must remain unavailable after native compromise of the JVM.
