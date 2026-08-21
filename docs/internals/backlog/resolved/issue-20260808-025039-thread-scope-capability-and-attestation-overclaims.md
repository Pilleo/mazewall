---
title: "Thread-Scoped Policies Are Documented as Enforcing Capabilities They Cannot Observe"
severity: "HIGH"
status: resolved
priority: low
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "docs/internals/case-studies/agent-sandbox-findings.md"
  - "docs/presentation/article1-threat-model.md"
effort: "medium"
autonomy: "supervised"
---

# 🔴 [Severity: HIGH]: Thread-Scoped Policies Are Documented as Enforcing Capabilities They Cannot Observe

**Context:** The agent case study and threat-model article claim that a thread policy can restrict a library to public IP addresses, prevent reads of environment variables, provide zero-trust isolation, and attest that sensitive data could not have been exfiltrated. Classic Seccomp cannot dereference the userspace `sockaddr` supplied to `connect`, Landlock network rules constrain TCP ports rather than destination IPs, and `System.getenv` reads process memory without a syscall. Blocking selected network and file syscalls also does not prove non-exfiltration through inherited descriptors, shared memory, unrestricted sibling threads, logs, IPC, or allowed channels. These claims exceed the implemented observation and enforcement points.

**Needed:** Replace capability claims with an exact matrix of enforceable syscall families, Landlock path/port rights, and unobservable in-process data flows. Describe destination-IP control as requiring a network namespace, cgroup/eBPF or external firewall policy. State that environment and heap confidentiality require a process/address-space boundary. Reframe audit output as evidence that configured operations were denied, not proof that data was never exfiltrated.
