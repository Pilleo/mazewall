---
title: "Lazy Bootstrap Classloads Under Narrow Allow-List Floors Return Corrupted Bytes"
severity: "HIGH"
status: "open"
priority: high
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "docs/internals/designs/core/security-considerations.md"
effort: "large"
autonomy: "supervised"
open_questions: true
dependencies: []
---

# 🔴 [Severity: HIGH]: Lazy Bootstrap Classloads Under Narrow Allow-List Floors Return Corrupted Bytes

**Context:** Discovered while enabling install-time self-verification (issue-20260823-172003).
Under an ALLOW_LIST policy with a jvmFloor-style allow-set (`AllowListTest`, default
`SECCOMP_RET_ERRNO(EPERM)`), ANY post-install lazy load of a not-yet-touched **bootstrap** class
(e.g. `java/util/logging/LogRecord`, `java/io/NotSerializableException`) can fail with
`ClassFormatError: Incompatible magic value <garbage>` (observed 0xFFFFFFFF and the ASCII text
`java`). The corrupted bytes come from the bootstrap/jrt read path — i.e. some syscall involved in
serving the class data (likely `pread64`/`readv` on the modules image, or similar) is NOT covered by
the floor's allow-set and is denied mid-read, yet the JDK surfaces this as garbage class bytes
instead of a clean IOException. Deterministic evidence: with runtime self-verification enabled
(which performs logging/simulation work post-install), `AllowListTest` fails 2/2 runs; disabled, it
passes 2/2. Warmup of specific closures (buildList machinery, JUL) only moved the failure to the
next lazy class — the problem is systemic for narrow floors.

This matters beyond self-verification: any user task that lazily touches a new boot class under a
narrow floor is one missed syscall away from a corrupt-class crash instead of a clean EPERM.

**Needed:**
1. Identify the exact denied syscall on the bootstrap-read path (strace the failing child; compare
   against `jvmFloor()` contents). Prime suspects: `pread64`, `readv`, `lseek` variants,
   `mmap(PROT_READ)` of newly opened segments.
2. Extend `jvmFloor()`/floor presets to include the full bootstrap-read closure — OR make the
   failure mode clean: if the root cause is seccomp denying a *partial* read primitive, evaluate
   returning `ENOSYS`/`EACCES` consistently so JDK raises IOException instead of defining garbage.
3. Document in `security-considerations.md`: narrow floors must either cover the bootstrap-read
   closure or applications must pre-touch required classes pre-containment; link the
   `ContainedExecutors.init` preload pattern as the mitigation template.
4. Re-evaluate issue-20260823-172003's default gate after the floor closure is fixed: with reliable
   bootstrap reads, defaulting self-verification ON becomes safe again.

## ❓ Open Questions
1. Is the corrupted-read behavior a JDK robustness gap worth reporting upstream
   (ClassFormatError instead of IOE on failed jrt reads)?
