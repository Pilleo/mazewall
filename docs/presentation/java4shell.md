# Java4Shell will happen again. How can we prepare?

One imperfection, a single out-of-bounds write, and your app is working against you. In Java4Shell, malformed Base64 could corrupt the JVM heap when it reached a vulnerable HotSpot decoder on x86-64 CPUs with AVX-512 VBMI. Updating the affected JDK fixes this vulnerability. Have a closer look at [the Java4Shell write-up](https://java4shell.org/). Java4Shell is not the last one, as neither people nor agents are perfect. And one tiny issue is all it takes to make a disaster.

## Containment: Bulkheads for Code

If we can't prevent every such bug, what is the next best thing?

Containment. Think of a warship or submarine. A defect might damage it, but likely will not sink it, as damage will be contained in one compartment. We already think about business logic in a modular way, but not about our security. Could we match logical and security scopes?

## Tightening the Container: From Fences to Straitjackets

How do we contain things today? Mostly with containers. Could we improve containers themselves? Yes, meet [Software Bill of Behavior (SBoB)](https://billofbehavior.com/bob/).

It is an open specification in development for describing what your application is intended to do at runtime. For example, we could tighten our container policies with SBoB. Kubescape already uses eBPF to observe workload behavior and alert on deviations from the profile, while Tetragon and KubeArmor show that similar policies can be enforced inline.

You may ask why it would be better with SBoB if all the tech under the hood stays the same. A container’s fence can be much larger than the service needs. A thumbnail worker that reads and writes local files has no reason to open an outbound connection. If its policy allows one, compromised code can send the original photos elsewhere without escaping the container. And with SBoB you could make a perfect list of all operations that your app requires. Anything outside of this list is not expected behavior and should be blocked and alerted on immediately and deterministically (given you can prove you have perfect profiling coverage of all executable code of your app—a hard problem by itself, but that is exactly what MazeWall is about). SBoB turns this fence into a straitjacket.

But it’s still early days for SBoB, and we cannot really enforce things with it yet. We already have mature tools that can isolate our apps, though. [Bubblewrap](https://github.com/containers/bubblewrap) and [nsjail](https://nsjail.dev/) wrap the command that starts your app and apply restrictions before it runs, so their seccomp policy must still allow the startup itself (`execve`, dynamic linker, JVM bootstrap). An important benefit is that they are language agnostic.

## Bringing Containment Into Code (`@Contained`)

That would help enormously, but a container boundary still surrounds the whole app. Code that is compromised inside it may still reach data and resources the rest of the app can access. To reduce that exposure, we could divide logic into more microservices. Milli-services? Ouch. Could we instead contain things right in our code and make logical and security scopes match? Could we, for example, have a `@Contained` annotation that runs a method with fewer OS permissions than the rest of the app?

Yes, we can, at least partially. Here is how.

How do containers contain? They (mostly) ask the OS to do that. Linux gives us seccomp and Landlock to restrict system calls and filesystem access, namespaces to isolate process views of system resources, and cgroups to control resource use. How can we use them to implement a `@Contained` annotation?

These mechanisms do not see your application the way you do. They do not care much about methods and classes; they operate on processes and threads. Namespaces and cgroups are useful for process- or container-level isolation, not for assigning a method its own OS identity. Seccomp and Landlock can restrict an individual thread, but those restrictions are irreversible: you cannot lock down the caller for one method and restore its permissions when the method returns.

A process-wide seccomp baseline can be synchronized across existing threads, and future threads inherit it; a parser thread can then receive tighter restrictions. Let’s say our parser receives its input as a byte array. We can dispatch it to a dedicated Java platform thread—not a virtual thread, because a restriction applied there would permanently stick to the carrier thread it runs on and affect unrelated tasks—and deny that thread permission to open files or make new network connections while allowing the system calls the JVM needs for parsing.

We can give a logical module its own restricted process or thread, bringing security and logic scopes closer. But a restricted thread still shares memory with other threads in the process. If a vulnerability lets it corrupt that heap, other threads can be affected too. A separate process is therefore the stronger isolation boundary. Browsers use processes for isolation for the same reason. We could do something similar in the backend by moving processing of untrusted data into a separate process. To make that practical, we would need language-specific tools that make working with processes almost as easy as working with threads, as right now that is not straightforward at all. Namespaces and cgroups fit naturally into this separate-process model.

## What could a compromised parser reach?

Let's first assume that we have our app in one process—the usual case today.

Suppose an attacker has already gained code execution inside our JVM. If their payload depends on running a shell or another executable, we can block that step. Elasticsearch and OpenSearch have done this on Linux for years by installing seccomp filters during startup. The denied launch returns a permission error. We can alert on that failure if we monitor it. Bubblewrap or nsjail can give you similar results for any language, along with more features.

But what if the attacker’s goal is to read a secret already in the JVM heap? Most likely we are out of luck in that case. Stripping unused code or restricting syscalls by process, thread, or even Java stack trace (allowing only specific methods to invoke sensitive syscalls) raises the bar—but against heap theft, that still relies on luck. The attacker may not need any new syscalls at all. If they can make another platform thread act on their behalf, they may gain its broader file and network permissions, still subject to the process-wide baseline.

Now, let's assume we have divided an app into several processes. All the parsing is done in one process, and business logic is in another. The parent passes undecoded bytes to the parser over a bounded channel and treats its reply as untrusted. The worker can see those bytes, but the application's signing key stays only in the parent's heap. The worker has no permission to inspect the parent or ask it to sign arbitrary data, and its policy blocks arbitrary file access, new network connections, and program launches. The vulnerable decode and heap overwrite now occur in the worker's address space. RCE there no longer hands over the application's signing key—blast radius shrinks drastically. Sadly that is easier said than done today, but that is a tooling/libraries issue, not some fundamental problem.

One caveat: pooled workers that are reused across requests can still leak data between requests, so use one-off or per-tenant workers and validate their output.

For this comparison, the plain container permits program launches. Both hardened policies block launches after startup, and the parser worker also blocks arbitrary file access and new network connections. Here is what the attacker can still do.

| After gaining code execution, can the attacker… | Plain container | Hardened single process | Isolated parser worker |
|---|---|---|---|
| launch a shell or another executable? | Yes | No | No |
| open arbitrary files or new network connections? | Within its policy | Within the process-wide baseline, possibly through another thread | No |
| read a signing key held only in the main JVM's heap? | Yes | Yes | No |

This isn't a turnkey recipe yet—it's a map of the gap between the OS primitives Linux already gives us and the developer ergonomics our languages still lack. Prevention will keep missing bugs; bulkheads determine whether the next Java4Shell floods one parser worker or sinks your whole service.

If you want to start closing that gap today, explore Kubescape and SBoB to see what your workloads actually do at runtime, look into Bubblewrap or nsjail to tighten container boundaries, and consider contributing to the tooling that makes least-privilege practical.

---
If you would like to explore these concepts, see what they look like in code, my in-app sandboxing research project [MazeWall](https://github.com/Pilleo/mazewall/) experiments with syscall supervision based on Java stack traces, threads, processes and with process “portals” that make calling a separate worker easier.

---
