---
title: "Additional Architecture Coverage: s390x, ppc64le, riscv64"
severity: "LOW"
status: "open"
priority: low
component: "enforcer"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/Arch.kt"
  - "platform/src/main/kotlin/io/mazewall/core/Syscall.kt"
effort: "large"
autonomy: "supervised"
open_questions: false
dependencies: []
paperclip_issue_id: d5444e0a-b3e1-49c7-9ede-eed086795d1e
---

# 🟡 [Severity: LOW]: Additional Architecture Coverage (s390x, ppc64le, riscv64)

**Context:** `Arch` supports x86_64 and aarch64 only (`Arch.current()` throws otherwise; CET is
x86_64-only by nature). Enterprise Linux estates commonly run s390x and ppc64le; riscv64 matters for
embedded/future clouds. The architecture table is hand-maintained but guarded by the completeness
test (`SyscallTest.numberFor maps to correct properties`), which makes adding an arch mechanical:
every new enum entry must map for every arch or the test fails.

**Needed:**
1. Add audit tokens (`AUDIT_ARCH_S390X = 0x80000016`, `AUDIT_ARCH_PPC64LE = 0xC0000015`,
   `AUDIT_ARCH_RISCV64 = 0xC0000083` — verify against `linux/audit.h`) and full NR tables.
2. Mind s390x quirks: old syscall numbers differ wholesale; `clone` lacks `CLONE_THREAD`-style
   flags differences? Verify each JVM-critical syscall mapping against
   `docs/internals/research/jvm-syscall-floor-research.md` before whitelisting in
   `BpfFilter.getJvmCriticalNrs`.
3. seccomp filter endianness note: cBPF K operands are host-endian; data loaded from
   `seccomp_data.args` is 64-bit little-endian on LE archs only — s390x is big-endian, so
   hi/lo word splitting for 64-bit argument inspections must be arch-parameterized
   (see `emitInspections` argOffsetHi/Lo).
4. CI: add cross-build/test matrix entries only if container images exist; otherwise document as
   compile-verified-until-hardware-available.
5. Extend `SyscallProbeMatrix`/differential suite to iterate all supported arches.

