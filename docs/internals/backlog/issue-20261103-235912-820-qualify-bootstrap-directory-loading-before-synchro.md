---
title: "Qualify bootstrap-directory loading before synchronous profiler stack capture"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt"
target_symbols:
  - "ProfilerTraceListener"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Qualify bootstrap-directory loading before synchronous profiler stack capture

**Context:**
Repository-wide review, 2026-11-04: [PN-E2](../reviews/2026-11-04-repository-review-findings.md#pn-e2). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Priority/qualification:** P2, conditional source-confirmed stall/session-failure under the concrete configuration below. Independent reconciliation: `/tmp/opencode/mazewall-review-2026-11-04/21-profiler-liveness-escalation.md:373–485`. No bootstrap-directory runtime reproduction or permanent JVM hang is claimed; daemon deadline/failure settlement limits the supported consequence, not a guaranteed exactly-30-second wall-clock bound. A valid JVM launch configuration is not itself proof of mazewall support or vendor/runtime qualification.

**Exact conditional JVM configuration**

Linux OpenJDK HotSpot using an appended **directory**, e.g. `-Xbootclasspath/a:/absolute/boot-extra`, containing a not-yet-loaded fixture class. The directory is outside `java.home` and all daemon `java.class.path` bypass roots. Use normal stack-capturing USER_NOTIF profiling on the registered workload thread, including `processWide=true`; have that thread call `Class.forName("fixture.BootOnly", true, null)` for the first time. The [official JDK 25 launcher documentation](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html) explicitly supports directories in `-Xbootclasspath/a:`. This does not require invalid FFM usage, a custom blocking Thread override, native instrumentation, or altering a JVM invariant.

**Connected source schedule**

1. The profiling worker is registered before install: `profiler/src/main/kotlin/io/mazewall/profiler/api/ProfilerSession.kt:57–79`; its TID resolves directly through the adjacent `ProfilerSessionState.kt:10–21` and `ProfilerSession.kt:85–96`.
2. Class.forName enters [`Class.c:98–137`](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/native/libjava/Class.c#L98-L137) → [`JVM_FindClassFromCaller:803–821`](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/hotspot/share/prims/jvm.cpp#L803-L821). `JVM_ENTRY` establishes **`_thread_in_vm`** through `ThreadInVMfromNative` ([`interfaceSupport.inline.hpp:170–180,382–389`](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/hotspot/share/runtime/interfaceSupport.inline.hpp#L170-L180)).
3. Resolution uses the VM bootstrap loader for the null loader: [`systemDictionary.cpp:695–698,1157–1239`](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/hotspot/share/classfile/systemDictionary.cpp#L1157-L1239). [`ClassLoader::load_class:1083–1099`](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/hotspot/share/classfile/classLoader.cpp#L1083-L1099) searches appended entries and calls `open_stream` without a native/blocked transition.
4. [`ClassPathDirEntry::open_stream:256–294`](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/hotspot/share/classfile/classLoader.cpp#L256-L294) invokes `os::open` while still in VM state. [`os_linux.cpp:4971–5008`](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/hotspot/os/linux/os_linux.cpp#L4971-L5008) calls libc open without changing that state. The directory path also lacks a transition in OpenJDK 22+36 (`classLoader.cpp:229–268`). **Do not generalize this directory proof to ZIP entries:** the adjacent ZIP path does use `ThreadToNativeFromVM`.
5. The default profiling policy converts OPEN/OPENAT denials to NOTIFY (`enforcer/src/main/kotlin/io/mazewall/enforcer/api/PolicyPresets.kt:16–31`; `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/seccomp/BpfFilter.kt:58–66,83–86`). The worker stops in seccomp at that open, still `_thread_in_vm`.
6. The daemon does not classify this path as loader noise: `profiler/src/main/kotlin/io/mazewall/profiler/internal/engine/ProfilerSessionHandler.kt:483–517,531–549` only supplies java.home/classpath roots. It publishes and awaits ACK (`:157–193`).
7. `profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt:436–480,523–529` calls the registered thread's `stackTrace` **before** `sendAck`. The targeted handshake cannot execute on behalf of a target stuck `_thread_in_vm` (`handshake.cpp:618–627`). The target cannot process the handshake itself until its open is released. The daemon offers CONTINUE only after ACK.

```text
tracee (_thread_in_vm, bootstrap-directory open)
  waits for kernel reply
daemon waits for listener ACK
listener waits for tracee to become handshake-safe
```

This cycle survives complete preloading of the listener's known classes: the blocked VM operation is loading an arbitrary workload bootstrap class. It also does not require TSYNC; TSYNC does not remove it.

**Consequence and qualification:** the normal path stalls until independent failure settlement. `SeccompSessionHandler.kt:156–161,218–229,266–289,397` starts a 30-second budget and then attempts EPERM and terminates the session. A healthy separate daemon can therefore release the target with an error, after which the handshake may finish and the late ACK may fail. Expect failed class loading/incomplete profiling and bounded disruption, **not a proven permanent JVM hang**. The Java API has no acquisition deadline, and OpenJDK's default `HandshakeTimeout` is zero; a nonzero diagnostic handshake timeout aborts rather than yielding a normal empty stack. The external daemon deadline remains the meaningful escape in this schedule.

**Smallest bounded correction:** define and enforce the stack-capture runtime boundary before irreversible installation. Reject an appended bootstrap directory outside the supported loader-noise roots while synchronous stack capture is enabled, with an explicit unsupported-configuration result. If this configuration is intended to be supported, it needs an explicit extension of the profiler's loader-noise contract and a real qualification test; simply prewarming `Thread.getStackTrace`, lengthening deadlines, or adding a Future timeout around it does not remove this target-state cycle. Do not silently switch to post-CONTINUE stacks: that would change attribution semantics tracked by resolved issue-142.

**Regression/qualification requested:** an owned fresh JVM with a unique unwarmed bootstrap-directory class outside the bypass roots, on the supported JDK/vendor builds. Include a successful unfiltered load control and the existing `captureStackTraces=false` route as a diagnostic comparison; retain the exact open notification, registered TID, publish/ACK/reply/deadline sequence and workload result. No attach or signals are needed for the trigger. A corrected runtime-boundary check must reject before installing a permanent filter, or an explicitly supported implementation must complete without relying on the terminal deadline and account honestly for any omitted stack. Mock preload-membership tests cannot prove this.

**Tracking:** this is an expanded concrete JVM-state/boot-directory condition, distinct from the fixed missing-ACK-class mechanism in `docs/internals/backlog/resolved/issue-143-process-wide-classloader-deadlock-on-profiler-result-state-t.md` and supervisor issues `issue-007-...`/`issue-149-...`. The source schedule supplies the kind of missing qualification requested by resolved `issue-20260808-025042-profiler-design-contradicts-implemented-ack-protocol.md`. No bootstrap-directory-specific item was found in the scoped active/resolved search. Coordinator should link or reopen the relevant ACK qualification tracking. It is **not a diagnosis or resolution** of active `issue-20261003-131111-752-investigate-intermittent-process-wide-profiler-int.md`.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235912-820-qualify-bootstrap-directory-loading-before-synchro  file: issue-20261103-235912-820-qualify-bootstrap-directory-loading-before-synchro.md -->
