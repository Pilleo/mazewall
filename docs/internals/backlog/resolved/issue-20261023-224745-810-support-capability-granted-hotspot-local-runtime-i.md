---
title: "Support capability-granted HotSpot local runtime initialization"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/hotspot-agentless/internal/capture/collector.go"
  - "experiments/hotspot-agentless/internal/capture/collector.go"
target_symbols:
  - "Run"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Support capability-granted HotSpot local runtime initialization

**Context:**
The corrected Btrfs collector c5b97fd1 passed pinned JVM discovery and the real preflight/exec barrier in fresh native attempt2, but failed before Ready because pinned Inspektor Gadget local.Runtime.Init unconditionally rejects EUID!=0. Temporary approved file capabilities are therefore insufficient for the current adapter, despite successful native preflight. Gradle1/XML1failure; collector137 retained; target1823767 exited0; both helper containers removed and file capabilities cleared. No kernel EPERM/EACCES or unsupported JVM identity is inferred.

**Needed:**
1. Select a capability-aware adapter correction that preserves real BPF/target checks and host initialization (no automount or permission fallback), with regression tests and one separately selected fresh native attempt; or select a different root-collector scope explicitly. Do not silently run the JVM/collector as root or patch around kernel denials.

**Investigation:** The pinned `pkg/runtime/local/local.go:32-35` has an unconditional
nonzero-EUID rejection and exposes no capability-based option. Its remaining
initialization is `host.Init(host.Config{})`. The public host initializer performs
the existing filesystem/namespace sanity checks; default automount is disabled.
Our collector calls this runtime initializer only after actual preflight, pidfd,
exec-barrier and pinned JVM discovery. Those earlier stages passed in attempt2.
This is not evidence that every later BPF operation will be permitted.

The proposed correction uses the same host initialization after real permission
checks without the SDK wrapper's UID-only assumption, retaining all errors and
automount restrictions. It needs explicit source-correction and fresh-run scope;
none is inferred from the now-consumed attempt2 grant. Context7 was quota-limited;
the exact selected Gadget module source, not guessed current documentation, was
inspected. See the [native result](../../reviews/2026-10-23-hotspot-public-native-attempt.md)
and native-first workspace `capability-init-proposal.md`.

**Selected correction:** Operator “continue” after the explicit proposal approval
request selects the capability-aware adapter, regressions/completion gate, fresh
verified build and exactly one attempt3. Both owners now initialize the same host
state with automount disabled after existing real preflight/identity checks, then
construct the unchanged local runtime. No upstream/dependency pin, UID, public API
or protocol changes; RunDebug remains unchanged. UID1000 behavioral RED/GREEN and
owning capture/preflight/identity race plus prepared vet pass in both owners,
including unchanged EPERM/EACCES/namespace-error propagation. Full build and fresh
native artifact/run verification remain pending; this issue is not yet archived.

The fresh pinned Go1.26.0 collector and released byte/OCI/negative verification
subsequently passed. Completion remains blocked: two full gates failed the separate
existing USER_NOTIF cold-network test (pre-direct-connect default-proxy deadline,
issue752); unchanged focused diagnostic passed, not a root-cause fix. No HotSpot
attempt3/helper has run, and its selected grant remains unused. Additional fixture
scope is proposed separately; no gate is bypassed and this issue remains open.

## Resolution — source correction verified and exercised

After the separately selected direct-loopback fixture correction, all15owning
integration cases pass and `./gradlew --offline build` completes0 in6m12s:
228tasks/33executed/1cache/194up-to-date. Existing warnings/skips and native opt-ins
disabled remain disclosed. The two prior failed gates are not relabeled passes.

Exactly one selected attempt3 then passed host initialization and reached OTel
tracer startup. It rejected before Ready on a different kernel-symbol restriction:
`unable to read kallsyms addresses - check capabilities`. Collector1/Gradle1,
XML1failure, target1886276 exited0/pending ownership false; both helpers removed
and file capabilities cleared. All81attempt3 evidence hashes and68+81older hashes
are retained in native-first private/docker-permission-3/reviewed-outcome.json.

This resolves only the UID-only initialization defect, not native qualification.
[Issue761](issue-20261023-232330-761-qualify-hotspot-kernel-symbol-access-under-kptr-re.md)
owns explicit kernel-symbol permission selection. No CAP_SYSLOG assignment, root
collector, sysctl change or attempt4 is authorized by this resolution. Independent
native outcome review, issue752's real retirement failures and other lanes remain.

---
<!-- id: issue-20261023-224745-810-support-capability-granted-hotspot-local-runtime-i  file: issue-20261023-224745-810-support-capability-granted-hotspot-local-runtime-i.md -->
