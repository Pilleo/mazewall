---
title: "Qualify HotSpot kernel-symbol access under kptr_restrict"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/HOTSPOT_AGENTLESS.md"
target_symbols:
  - "observeHotSpot"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Qualify HotSpot kernel-symbol access under kptr_restrict

**Context:**
Native attempt3 passed capability-aware host initialization and reached OTel tracer loading, then failed before Ready with unable to read kallsyms addresses - check capabilities. Gradle1/XML1failure/collector1; target1886276 exited0/pending ownership false; both helpers removed and file capabilities absent. Passive UID1000 inspection observes kptr_restrict=1 and16/16sampled addresses zero, no raw kernel addresses retained. Existing six-capability set omits CAP_SYSLOG. No numeric EPERM/EACCES was emitted and no fourth grant exists.

**Needed:**
1. Select explicit kernel-symbol authority before another native attempt: recommend adding only CAP_SYSLOG to a fresh private collector copy under unchanged Docker setup/removal and non-root JVM/test execution, with source-pinned unchanged assets and one fresh attempt4. Preserve kptr_restrict and real failures; no privileged probe, root fallback or automatic retry.

**Investigation:** Selected OTel `kallsyms.go:371-386,465-466` returns its
ErrSymbolPermissions when parsed kernel addresses are zero; tracer.go:229-231
propagates the message before main tracer BPF load. The actual failure is not a
numeric EPERM/EACCES and does not imply every later BPF operation is permitted.
[Linux sysctl guidance](https://docs.kernel.org/admin-guide/sysctl/kernel.html#kptr-restrict)
and [upstream v6.12 kernel implementation](https://github.com/torvalds/linux/blob/v6.12/kernel/ksyms_common.c)
require initial-user-namespace CAP_SYSLOG at kptr_restrict1; kptr_restrict2 rejects
regardless. That upstream reference is not a claim that the custom running7.2.11
kernel source was locally inspected. Passive current UID/GID tuples are all1000;
the host bounding set includes CAP_SYSLOG but it is not effective. No privileged
symbol read, capability assignment or sysctl change was performed to test it.

The native-first workspace `syslog-permission-proposal.md` specifies the pending
choice. CAP_SYSLOG includes privileged kernel-log/address access/control; it is a
new authority, not merely a renamed existing BPF capability. Preserve prior grants
and fail closed; root execution or lowering kptr_restrict is not the selected fix.

## Resolution — selected capability and bounded native acceptance

The operator directed “Justt apply, try and see” for the proposed scope. A fresh
read-only asset4 copy passed released byte/OCI/negative checks without rebuilding
the unchanged collector. Only CAP_SYSLOG was added to the private collector file's
existing six-capability grant; helper privileges, non-root host execution, JVM,
400/4/20fixture, timing, sysctls and cleanup rules stayed unchanged.

Attempt4 ran23:38:21–23:38:31 UTC2026-10-23: Gradle0/XML1test/0skips/failures/errors;
the fixture accepted all400unique marked operations,380useful correct routes and
176marked compiled application events (88per route). Selected401event accounting,
terminal accounting and physical drain complete, pending0. Collection-loss/global
coverage and policy eligibility remain false; this is bounded observational
acceptance, not strict lifetime/universal JIT/GC or Kubescape persistence proof.

Target1896794 exited0/pending ownership false; both acquired helpers exited0,
were observed stopped/removed and file capabilities cleared. kptr_restrict stayed1.
Native-first private/docker-permission-4/reviewed-outcome.json verifies484new and
230prior evidence hashes. Original failures/grants remain intact; no fifth attempt,
root execution, publication or storage/live action. Independent outcome review
remains pending. See the [current native result](../../reviews/2026-10-23-hotspot-public-native-attempt.md).

---
<!-- id: issue-20261023-232330-761-qualify-hotspot-kernel-symbol-access-under-kptr-re  file: issue-20261023-232330-761-qualify-hotspot-kernel-symbol-access-under-kptr-re.md -->
