---
title: "Hardware-Hardening Documentation Mixes Implemented CET with Speculative Absolute Controls"
severity: "HIGH"
status: "open"
priority: 2
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "docs/internals/intel-cet-jvm-sandbox-integration.md"
  - "docs/internals/designs/core/security-considerations.md"
  - "docs/presentation/article5-graalvm.md"
effort: "large"
autonomy: "supervised"
---

# 🔴 [Severity: HIGH]: Hardware-Hardening Documentation Mixes Implemented CET with Speculative Absolute Controls

**Context:** Current code enables and locks x86 shadow stacks, but the hardware document presents PR_SET_MDWE, ARM PAC/BTI, MPK, MTE, V8 cages, ELF-note auditing, and JVM flags as one current Mazewall protection stack. It says CET prevents ROP/JOP, MTE eliminates UAF/OOB and makes FFM immune, and pointer cages mathematically trap corruption. These mechanisms mitigate specific exploit techniques under architecture-, compiler-, runtime-, and configuration-dependent conditions; none provides the stated absolute result. In particular, MTE uses finite tags and probabilistic checking, and untrusted native code can execute unprivileged `WRPKRU` unless a separate mechanism prevents it.

**Needed:** Split the document into implemented, experimentally verified, and research-only sections. Cite the exact supported kernel/JDK/toolchain contracts and remove undocumented JVM flags or `prctl` operations. Describe CET, PAC/BTI, MTE, MPK, MDWE, and cages by the attacks each mitigates and their bypass/compatibility conditions. Add hardware-gated tests before presenting any mechanism as an enforced Mazewall tier.
