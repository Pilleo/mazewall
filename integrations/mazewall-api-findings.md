# MazeWall API usability and security-contract review

Review date: 1 October 2026\
Repository: Pilleo/mazewall\
Reviewed source snapshot: `98da39f7f71b64a14633ab3f19046f6bc2afc0e3`

## Executive recommendation

Consolidate the existing API around a small, predictable, fail-closed application path. Do not begin with a wholesale rewrite or another custom executor implementation. The repository already has scope types, runtime profiles, dedicated JDK-backed executor factories, read-only assessments, non-closeable installation receipts, profiling coverage gates, and diagnostic evidence classifications. The largest immediate problem is that public examples, convenience methods, and lower-level builders do not consistently preserve those contracts.

The product objective should be **correctly understood, demonstrably active restrictions**, not merely a shorter policy expression or successful installation call.

## Scope and confidence

This is a source and documentation review, not a complete vulnerability audit. Findings below are based on the pinned source and comparisons with primary documentation. No MazeWall build, Linux integration suite, packaged-artifact resolution, or end-to-end exploit tests were run. A minimal independent Java compiler probe reproduced the ambiguity caused by the Callable/Supplier overload pair; it does not constitute a MazeWall build. The probe used javac 21.0.11, whereas MazeWall documents JDK 22+.

“P0” means address before encouraging wider external adoption. It is a release-priority classification, not a claim that each item is an independently exploitable vulnerability.

## Findings register

### A01 — Builder defaults contradict their documentation — P0

**Evidence:** `JavaPolicyBuilder` documents a default-deny `ACT_ERRNO` action. It instantiates `PolicyBuilder()` without changing the action; `PolicyBuilder` defaults to `ACT_ALLOW`.

**Consequence:** A user can believe unspecified syscalls are denied when they are not. This does not mean the resulting compiled policy has no restrictions: argument filters and runtime rules are separate.

**Change:** Expose explicitly named allow-list and deny-list constructors. Do not simply change every raw builder to deny-all: runtime compatibility is a separate requirement. Fix the documented default immediately; make incompatible semantic changes explicit.

**Acceptance:** Test empty/default policy semantics for every public entry point, and verify generated documentation/examples use the same contract.

Sources: [JavaPolicyBuilder](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/enforcer/src/main/kotlin/io/mazewall/JavaPolicyBuilder.kt), [PolicyBuilder](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/enforcer/src/main/kotlin/io/mazewall/PolicyBuilder.kt).

### A02 — Mixed mutable and copying builder methods silently lose filesystem rules — P0

**Evidence:** Most `Policy.Builder`/`PolicyBuilder` methods mutate the receiver. Filesystem methods create a new thread-local snapshot. Discarding their return value loses the rule, including when starting with `threadLocalBuilder()`.

```kotlin
val builder = Policy.threadLocalBuilder()
builder.allowFsRead("/input") // Returned snapshot is discarded.
val policy = builder.build()
```

**Important distinction:** The current `JavaPolicyBuilder` privately retains the promoted builder, so that specific Java-facade bug has already been addressed; its prose is stale. Do not regress that fix.

**Change:** Use fixed-scope mutable builders with immutable results: a process builder never exposes filesystem methods; a worker builder never needs scope promotion. An alternative is uniformly immutable builders with enforced return-value checks, but mixing both models should end.

**Acceptance:** Fluent and imperative usage produce identical policies; Kotlin `apply` examples do not silently drop rules; process-scope construction cannot acquire thread-only state.

Sources: [Policy](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/enforcer/src/main/kotlin/io/mazewall/Policy.kt), PolicyBuilder and JavaPolicyBuilder above.

### A03 — Convenience classpath grants widen to parent directories — P0

**Evidence:** `allowJvmClasspath()` adds `java.home`; for classpath files, `addClasspathFile` adds the parent directory rather than only the file.

**Consequence:** With a classpath JAR at `/app/parser.jar`, this helper can grant access to other files in `/app`. A neighboring secret is not a classpath dependency, but receives the same directory grant.

**Change:** Resolve exact JAR-file grants where supported, retain explicitly intended class directories, and show expanded runtime-file grants before installation. Do not silently substitute a parent directory when a precise grant cannot be represented.

**Acceptance:** A JAR remains readable while an unrelated neighboring secret remains denied. Test exploded classes, packaged JARs, custom resource roots, and deployment changes separately.

Source: PolicyBuilder above.

### A04 — Boolean permission setters and composition are surprising — P0/P1

**Evidence:** `JavaPolicyBuilder.allowMmapExec(false)` does nothing; it does not undo an earlier enabling setting. `PolicyBuilder.base` overwrites/merges rules and broadens certain grants; `PolicyDefinition.combine`/`intersection` use a restrictive algebra. Named `restrictFurtherWith` and `intersection` already exist.

**Change:** Remove misleading Boolean setters or implement true setter semantics. Keep template selection distinct from restrictive composition. Prefer the existing explicit intersection names in examples. Model filesystem “unrestricted”, “deny all”, and “allow these resources” as distinct states.

**Nuance:** Empty filesystem sets on a fresh builder do not activate Landlock. An empty intersection of already active Landlock policies does preserve enforcement in `PolicyDefinition.combine`; do not incorrectly report that case as unrestricted.

**Acceptance:** Test false-after-true, base ordering, restrictive composition commutativity/idempotence, and empty-set semantics.

Source: [PolicyDefinition](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/enforcer/src/main/kotlin/io/mazewall/PolicyDefinition.kt), builders above.

### A05 — Executor ownership and offloading are hidden by the happy path — P0/P1

**Evidence:** `wrap` accepts arbitrary executors whose workers remain permanently restricted. Existing `newSingleThreadExecutor` and `newFixedThreadPool` helpers already create dedicated JDK-backed pools. The API design intentionally defers a custom owned executor product.

`Mazewall.runContained` documentation claims temporary current-thread installation and cleanup. Its implementation delegates to `SandboxDispatcher`, which uses cached policy-specific worker pools. That is an offloaded execution boundary, not reversible current-thread containment.

**Change:** Promote existing dedicated factories. Keep arbitrary wrapping advanced. Document thread-local/context consequences and nested-worker delegation. Reject or clearly exclude direct executors, shared pools, and CallerRuns rejection, which can install permanent restrictions on the submitting thread.

**Capacity:** The dispatcher limits cached pool count to 32, but uses cached thread pools; that is not a total worker-thread bound. Offer explicit bounded concurrency/queuing and predictable rejection. A timeout is not guaranteed task termination or heap isolation.

**Acceptance:** Exercise replacement workers, saturation, interruption, nested submission, virtual callers, and shutdown. No user task may start before that worker’s required restrictions are established.

Sources: [ContainedExecutors](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt), [SandboxDispatcher](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/enforcer/src/main/kotlin/io/mazewall/enforcer/api/SandboxDispatcher.kt), [Mazewall facade](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/enforcer/src/main/kotlin/io/mazewall/Mazewall.kt), [existing API design](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/docs/internals/designs/api/enforcer-public-api.md).

### A06 — Explicit bypass can disappear behind an ordinary ExecutorService — P0

**Evidence:** `Platform` defaults to FAIL, but reads WARN_AND_BYPASS/SILENT_BYPASS from a system property or environment variable. Installation can return `installed=false`. `ContainedExecutorWrapper` proceeds from an installation return to running the task without requiring `installed=true`.

**Classification:** This is intentionally configured fallback, not a discovered default fail-open behavior. The usability problem is that the application-facing executor does not make the weaker result visible in its type or task result.

**Change:** Production execution requires the requested guarantees. Put unprotected developer execution behind an explicit adapter/profile with unmistakable status. Freeze effective configuration at application initialization. Track per-feature results rather than only a single success Boolean.

**Acceptance:** Unsupported hosts and injected setup failures cannot report protected readiness. Partial Landlock-before-seccomp failure records the restrictions that remain; affected workers must not be treated as clean or reused for unrelated tasks.

Sources: [Platform](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/enforcer/src/main/kotlin/io/mazewall/Platform.kt), [wrapper](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapper.kt), [receipt](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/enforcer/src/main/kotlin/io/mazewall/InstallationReceipt.kt).

### A07 — Runtime applicability is not runtime safety — P0/P1

**Evidence:** `NO_EXEC` is typed `ProcessWideSafe` while its documentation warns that process-wide use can crash an active HotSpot JIT. The assessment emits a warning for this condition. Safer `ProcessPolicies` and runtime-specific presets already exist.

**Change:** Name scope types by applicability rather than broad safety. Make the normal process API require an explicit runtime profile and validate the final configuration. Keep process-spawning restrictions separate from executable-memory restrictions. Never install a global process baseline as an incidental library-import effect.

**Acceptance:** A known incompatible process/JIT combination cannot pass the ordinary required-install path with only a warning. Framework startup, native agents, and legitimate subprocess needs are explicit compatibility cases.

Sources: Policy above; [InstallationAssessment](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/enforcer/src/main/kotlin/io/mazewall/InstallationAssessment.kt).

### A08 — Examples discard profiling coverage and normalize incomplete exports — P0

**Evidence:** The quick starts use `result.behavior.toDsl(allowIncomplete = true)`. `ProfilingResult.toDsl()` and `.toPolicy()` already pass coverage and refuse incomplete results by default. The profiler README still makes exactness claims and mixes deprecated and recommended workflows.

**Change:** Lead with `result.toDsl()` and the owned profiling-session API. Preserve evidence through generation, review, serialization, merging, and deployment. Separate export of partial evidence from approval of an enforceable policy. Replace global incomplete overrides with specific, reviewed exceptions where appropriate.

**Semantic model:** observed behavior → candidate → reviewed policy → installed effective restrictions. Complete collection is not complete future-path coverage, desired authorization, or proof of an exploit-resistant boundary. A future reachability proof should carry its own assumptions, not share an ambiguous `complete` flag.

**Acceptance:** Dropped events, unresolved paths, noncontemporaneous logs, and uncovered asynchronous work cannot silently produce an approval-ready artifact. A newly observed network destination is a review item, not an automatic grant.

Sources: [ProfilingResult](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/profiler/src/main/kotlin/io/mazewall/profiler/ProfilingResult.kt), [MazewallProfiler](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/profiler/src/main/kotlin/io/mazewall/profiler/MazewallProfiler.kt), [profiler README](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/profiler/README.md).

### A09 — Diagnostics must preserve evidence strength — P0/P1

**Evidence:** The detector already distinguishes `OBSERVED_POLICY_DENIAL` from `INFERRED_PERMISSION_FAILURE`. Wrapping an inferred failure can still produce a `ContainmentViolationException`. The getting-started compliance recipe describes the exception itself as proof.

**Change:** Update documentation and summaries to use evidence strength, not exception class membership. Report policy identity, operation/resource when known, enforcement stage, original cause, and attribution confidence. An in-process diagnostic receipt is not tamper-resistant attestation or regulatory certification.

**Acceptance:** Ordinary OS permission errors are not labeled confirmed MazeWall denials. Task-caught exceptions and process-terminating actions are covered in diagnostic limitations. Logs redact sensitive resource details where necessary.

Sources: [detector](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/enforcer/src/main/kotlin/io/mazewall/enforcer/diagnostics/ContainmentViolationDetector.kt), wrapper above, [Getting Started](https://github.com/Pilleo/mazewall/blob/98da39f7f71b64a14633ab3f19046f6bc2afc0e3/GETTING_STARTED.md).

### A10 — Java lambda overload ambiguity — P0

**Evidence:** `runContained(policy, Callable<T>)` and `runContained(policy, Supplier<T>)` both accept a no-argument value-returning lambda. An uncast Java call is ambiguous. A minimal independent javac fixture reproduces this language-level issue.

**Change:** Prefer one canonical checked-exception-capable functional interface, or use different method names. Do not require casts in the first example. The Java facade already exists; this is a repair, not a request to invent one.

**Acceptance:** Compile all published Java snippets from clean consumer projects against the actual packaged artifact and supported JDKs.

Source: Mazewall facade above. See accompanying `OverloadProbe.java` and `javac-result.txt`.

### A11 — Distribution and documentation need release gates — P0

**Evidence:** Getting Started presents `com.github.Pilleo.jseccomp:enforcer:main-SNAPSHOT` as the easiest installation, despite this review targeting the renamed mazewall repository and master snapshot. Artifact resolution was not tested.

**Change:** Publish and test a pinned, attributable version; prefer a low-friction public repository such as Maven Central when available. Generate Java and Kotlin examples from tested consumer fixtures. Remove accidental legacy recipes and distinguish stable, experimental, and deprecated APIs.

**Acceptance:** A fresh consumer can resolve the documented dependency, compile the sample, run an allowed action, and observe a deliberately denied action on a supported Linux environment.

Source: Getting Started above.

### A12 — Make the execution boundary a taught and tested contract — P1

**Evidence:** The project already warns that worker restrictions do not isolate arbitrary hostile Java, shared memory, preexisting descriptors, or unrestricted sibling executors.

**Change:** Explain synchronous work, intentional delegation, background/async work, and resource inheritance in the primary recipes. Do not sell thread containment as per-method authorization or as isolation of hostile plugins. Keep stack-scoping research separate from the ordinary supported path. Stack attribution helps explanation; authorization and durable workload identity need their own validated contract.

**Acceptance:** Include examples where a library offloads to its own executor and where a preexisting socket remains outside the advertised new-connection restriction. Users must correctly identify those cases as outside the worker contract.

Source: Getting Started above.

## Comparative lessons

- **Java Security Manager:** Nonlocal permission reasoning and privileged boundaries were difficult, but abandonment was not solely a UI issue. Oracle cites rare server-side use and maintenance cost. Existing users also built real authorization systems and policy-generation tooling. Automatic discovery alone is not enough to avoid its adoption problems.
- **.NET CAS:** Compatibility APIs that still compile but no longer enforce are a dangerous failure mode. A successful API call must not masquerade as active protection.
- **OpenBSD pledge/unveil:** Semantic capabilities and explicit permanent transitions are easier to explain than syscall mechanics. Do not hide the irreversible transition.
- **Deno/Node:** Make resources and the intended threat model legible. Do not imply that runtime permission controls or worker restrictions isolate arbitrary malicious native/code execution.
- **GraalVM:** Presets should validate the final customized configuration, not merely provide initial defaults.
- **Rust Landlock:** Distinguish required guarantees from optional hardening, and surface compatibility outcomes explicitly.
- **AppArmor:** Learning generates reviewable proposals; enforcement is separate. Do not copy reload semantics onto irreversible per-thread filters.
- **Elasticsearch entitlements:** Reviewable resource manifests and applying the same policies in tests help maintainability.
- **Tink:** Remove security decisions the library can safely own; a high-level interface should not require low-level specialist choices.

Primary references:

1. Oracle, Java 24 announcement: https://blogs.oracle.com/java/the-arrival-of-java-24
2. Oracle, Java 17 security architecture: https://docs.oracle.com/en/java/javase/17/security/java-se-platform-security-architecture.html
3. Peter Firmstone, first-person policy-generation discussion: https://foojay.io/today/jep-411-what-it-means-for-javas-security-model/
4. Microsoft, CAS obsolescence and fail-open compatibility: https://learn.microsoft.com/en-us/dotnet/core/compatibility/core-libraries/5.0/code-access-security-apis-obsolete
5. OpenBSD: https://man.openbsd.org/pledge.2 and https://man.openbsd.org/unveil.2
6. Deno: https://docs.deno.com/runtime/fundamentals/security/
7. Node: https://nodejs.org/api/permissions.html
8. GraalVM: https://www.graalvm.org/latest/security-guide/sandboxing/
9. Rust Landlock: https://docs.rs/landlock/latest/landlock/enum.CompatLevel.html
10. Ubuntu AppArmor: https://ubuntu.com/server/docs/how-to/security/apparmor/
11. Elasticsearch: https://www.elastic.co/docs/extend/elasticsearch/creating-classic-plugins
12. Google, Tink design discussion: https://google.github.io/building-secure-and-reliable-systems/raw/ch06.html
13. Acar et al., controlled study of cryptographic API usability: https://teamusec.de/publications/conf-sp-acar0fgkms17/

## Suggested implementation order

1. Repair contradicted contracts and examples: A01–A04, A08–A11. Add regression tests first; verify actual packaged Java/Kotlin consumers.
2. Consolidate the default execution path: A05–A07. Reuse JDK executors and existing assessment/receipt infrastructure. Make application-owned process baselines explicit.
3. Add lifecycle and deployment support: versioned reviewed policy artifacts, capability diffs, environment-aware verification, and explanation using the same core model rather than parallel implementations.
4. Validate with developers who did not build MazeWall. Measure correct authorization, correct understanding of limits, and behavior after a failure—not only task completion time.
5. Expand advanced supervision/stack features only with separately specified guarantees and compatibility tests. Do not make the ordinary user learn kernel/JVM internals to obtain ordinary restrictions.

## Usability validation proposal

Ask a small initial group of Java and Kotlin developers to configure one synchronous parser, resolve an expected denial, detect an unexpected network request, handle an unsupported environment, and inspect an asynchronous escape case without help from the author. Check both the policy they produce and what they believe it guarantees. Repeat after a dependency/configuration change.

The release gate should prohibit false protected-readiness states and silent widening in these exercises. This initial study is an engineering check, not a statistically representative proof of usability or security.
