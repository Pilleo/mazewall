# Generating an SBoB for Java: Where We Are and What's Missing

> **Series overview:** This is Part 4 of a 4-part series on behavioral security for cloud-native applications.


Parts 1–3 established the *enforcement* side: what a Software Bill of Behavior is, how the kernel primitives enforce it, and what attacks they concretely stop.

Now the harder question: **how do you actually create the contract?**

This is where honesty is required. The generation tooling is immature, the standards are forming, and the current best path is narrower than you'd like. Let's map it precisely.

---

## Static vs. Dynamic: The Honest Trade-off

The first instinct is static analysis. For an isolated, well-defined library — a pure JSON parser, a cryptography primitive — static analysis works reasonably well. We can analyze bytecode, trace call graphs, and assert that `org.json:json` never calls `java.net.Socket`.

For a full-scale Java application, static analysis runs into a wall. Modern frameworks rely on:
- **Reflection** for dependency injection and dynamic dispatch
- **Dynamic proxies** for AOP (Spring transactions, interceptors)
- **JNI** for native bridge libraries
- **Configuration-driven dispatch** where the active beans depend on environment variables at runtime

A static analyzer cannot definitively determine what a Spring Boot application will do at startup, because the behavior is constructed dynamically from classpath scanning and configuration files. The set of active endpoints, enabled features, and loaded providers isn't known until the context is initialized.

**Dynamic analysis** — observing the application as it runs — avoids these problems but introduces its own: coverage. No test suite exercises every possible state. Kafka consumers, scheduled jobs, error-handling paths, and admin endpoints are routinely absent from integration test coverage.

The conclusion is unavoidable: generating a meaningful SBoB requires combining static analysis, dynamic observation, and manual review. No single approach is sufficient.

---

## The Three-Step Blueprint

Here is the most practical path available today:

### Step 1: Generate Per-Dependency SBoBs

At the library scope, behavioral analysis is tractable. A library has a finite, testable API surface that doesn't depend on the caller's configuration.

The approach: instrument each library through a comprehensive test harness, forcing execution down all branches, while tracing at the syscall level with tools like **Inspektor Gadget** (`trace_exec`, `trace_open` gadgets). The resulting syscall profile is the library's BoB baseline.

To maximize branch coverage of libraries: use feedback-directed fuzzing (**JQF**, **Jazzer**), random test generators (**Randoop**), and evolutionary API fuzzing (**EvoMaster**). The goal is to exercise every code path through the library's public API under adversarial and edge-case inputs.

This generates a per-dependency BoB: *"This JSON parser calls `openat`, `read`, `mmap`, `brk`. It never calls `socket`, `connect`, `execve`, or `memfd_create`."*

### Step 2: Dynamic Coverage for the Application

Layer dynamic observation on top of the library baselines to capture framework-level behavior:

- **Integration tests:** Known business flows under real framework initialization
- **Chaos inputs:** Malformed and unexpected data to exercise error-handling paths
- **Production shadow traffic:** Replay real-world requests in a controlled staging environment against a BoB-instrumented instance
- **API fuzzing:** EvoMaster or similar tools that use evolutionary algorithms to generate complex inputs and maximize endpoint coverage

**Acknowledge the blind spots explicitly.** REST fuzzers don't trigger:
- Kafka/RabbitMQ consumer paths
- Scheduled jobs (`@Scheduled`, Quartz)
- File watchers and inotify-driven processing
- JMX and admin endpoints
- Shutdown hooks

These require dedicated, workload-specific observation campaigns separate from API coverage.

### Step 3: Merge, Then Prune

Combine the library baselines (Step 1) with the application observation profile (Step 2). This merge is where most real-world BoB implementations fail.

---

## The Merge Fallacy

The most common mistake in BoB generation is the naive merge.

If you concatenate the SBoBs of all dependencies, you produce a bloated super-permission set. Consider the AWS SDK. The SDK's BoB correctly declares that it *may* open outbound connections to S3, DynamoDB, SQS, Kinesis, and dozens of other services.

If your application only uses DynamoDB — and DynamoDB is the only endpoint ever observed in production — but you naively merged the full SDK BoB, you have just granted your application permission to talk to S3, SQS, and Kinesis. You have re-introduced exactly the broad, permissive permissions that BoB was supposed to eliminate.

**A useful SBoB must represent the application's *actual* behavior, not the theoretical maximum behavior of its dependencies.** The merged profile must be aggressively pruned to what dynamic observation actually confirmed.

This pruning is technically hard. It requires correlating library-level call sites with application-level execution paths, then computing which capability grants are reachable given the pruned call graph. This tooling does not exist as a production-ready product today.

---

## The Limits: Lazy Init, Hot Reload, Dynamic Config

Making BoB enforcement practical requires establishing a stable steady-state. Kubernetes readiness probes give a clean boundary: behavior during initialization is broad and complex; after the first successful health check, behavior should be narrow and predictable.

But several common Java patterns break this assumption:

**Lazy initialization:** Deferring startup of heavy components (connection pools, cache warmup) until the first tenant request — potentially hours after startup — means the "steady-state" BoB must still include the initialization-phase behaviors indefinitely. In a strict BoB model, lazy initialization is an anti-pattern.

**Dynamic configuration and hot reload:** Feature flags, runtime config pushes, and hot deploys break the steady-state assumption. If behavior can change arbitrarily without a restart, creating a restrictive static behavioral contract is nearly impossible.

These are real constraints that require architectural discipline. **For the PoC work ahead, the answer is GraalVM** — it sidesteps most of these problems by making the binary static. Standard JIT-compiled applications are the harder next step.

---

## GraalVM: The AOT Paradigm Shift

If standard JIT-compiled JVM applications render static analysis practically useless, **GraalVM Native Image represents a fundamental paradigm shift that restores the feasibility of static analysis.**

Standard Java security is completely reactive: it relies on dynamic observation to see what the application does, which is forever vulnerable to test coverage gaps. GraalVM changes the mathematical model by introducing the **Closed-World Assumption**. 

Under this closed-world model, the compiler requires that all classes, methods, and fields that will exist at runtime must be known and reachable at compile time. Any dynamic features—such as reflection, dynamic proxies, resources, or native JNI loading—must be explicitly declared ahead of time in **Reachability Metadata**. 

This constraint allows the static native image builder to construct a **provably complete call graph** of the entire application before compilation. Unreachable code paths are not just skipped; they are physically removed from the binary via Dead Code Elimination (DCE).

## The Counter-Argument and Its LimitsThe obvious objection: *"Most Java applications don't run on GraalVM Native Image."*

True. GraalVM has real costs: longer build times, more complex debugging (no runtime reflection, more explicit configuration), ecosystem compatibility issues (libraries that don't yet provide reachability metadata), and cold-start trade-offs in some workloads.

These are legitimate engineering objections, not security denialism. Many organizations have rational reasons to stay on JIT-compiled JVMs.

The point is narrower: for the *PoC problem* of "can we generate a high-confidence, pruned SBoB automatically," GraalVM is currently the clearest path because it eliminates the hardest sources of noise. Standard JIT-compiled SBoB generation is the harder next problem — not a reason to abandon the idea, but a reason to sequence the work correctly.

---

## What Comes Next

While the underlying Linux kernel primitives (Seccomp and Landlock) are production-ready, the JVM-level integration in `jseccomp` is entirely experimental and untested. The entire codebase is a research proof-of-concept.


If you are working on:
- Syscall attribution tooling for GraalVM native binaries
- Automated per-dependency BoB generation using Inspektor Gadget + fuzzing pipelines
...

...the author is interested in collaborating on these engineering challenges.

- **Do not deploy process-wide lockdowns with this library yet:** The process-wide lockdown capability (`installOnProcess`) in `jseccomp` is **not production-ready** and has not been tested sufficiently. For now, restrict the library's use to thread-scoped containment of untrusted worker pools in staging/non-production environments.
- **Instrument your CI pipeline with Inspektor Gadget** and start profiling your application's syscall footprint — regardless of whether you run GraalVM. Every application benefits from knowing its actual runtime footprint.
- **Watch and contribute to the emerging Software Bill of Behavior (BoB) specification:** Visit [billofbehavior.com](https://billofbehavior.com) and follow the open specification work at [github.com/k8sstormcenter/bob](https://github.com/k8sstormcenter/bob).