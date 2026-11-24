# Java4Shell will happen again. How can we prepare?

It never happened before, yet here we are again... One out-of-bounds write and your app is working against you. In Java4Shell, malformed Base64 could corrupt the JVM heap when it reached a vulnerable HotSpot decoder on x86-64 CPUs with AVX-512 VBMI. Updating the affected JDK fixes this vulnerability. Have a closer look [here](https://java4shell.org/). Java4Shell is not the last one, as neither people nor agents are perfect. And one imperfection is all it takes to make a disaster.

While we must always try to prevent these bugs, we cannot really prevent them once and for all.

## Containment: Bulkheads for Code

If we cannot, what would be the next best thing?

Containment. Think of a warship or submarine. Imperfection might damage it, but likely will not sink it, as damage will be contained in one module. We already think about business logic in a modular way, but not about our security. Could we match logical and security scopes?

## Tightening the Container: From Fences to Straitjackets

How do we contain things today? Mostly with containers.

Could we improve containers themselves? Yes, we could. Meet Software Bill of Behavior (SBoB). [https://billofbehavior.com/bob/](https://billofbehavior.com/bob/)

It is an open specification in development for describing what your application is intended to do at runtime. For example, we could tighten our container policies with SBoB. Kubescape and similar tools already use eBPF to observe workload behavior and detect deviations from the declared profile. At some point it will be able to enforce these policies as well, giving similar result to a container with an enforced SBoB. Tetragon, Falco, KubeArmor can also enforce things in a similar way.

You may ask why would it be better with SBoB if all the tech under the hood stays the same. At this point containers give your apps a fence that is larger than your app's need to operate.  The smaller your app is, the bigger the attack surface that could be removed. And with SBoB you could make a perfect list of all operations that are needed to operate. Anything outside of this list - that is not your business logic, should be blocked and alerted immediately and deterministically(given you can prove you have a perfect profiling coverage of all executable code of your app). SBoB turns this fence into straitjacket.

But it’s still early days for SBoB, and we cannot really enforce things with it yet. But we already have mature tools that can isolate our apps by wrapping our command in terminal. [Bubblewrap](https://github.com/containers/bubblewrap) and [nsjail](https://nsjail.dev/) apply the launcher’s restrictions before loading the target program, so the policy has to permit that initial launch.

## Bringing Containment Into Code (`@Contained`)

That would help enormously, but a container boundary still surrounds the whole app. Code that is compromised inside it may still reach data and resources the rest of the app can access. To reduce that exposure, we could divide logic into more microservices. Milli-services? Ouch. Could we instead contain things right in our code and make logical and security scopes match? Could we, for example, have a `@Contained` annotation that runs a method with fewer OS permissions than the rest of the app?

Yes, we can. Here is how.

How do containers contain? They (mostly) ask the OS to do that. Linux gives us seccomp and Landlock to restrict system calls and filesystem access, namespaces to isolate process views of system resources, and cgroups to control resource use. How can we use them to implement a `@Contained` annotation?

These mechanisms do not see your application the way you do. They do not care much about methods and classes; they operate on processes and threads. Namespaces and cgroups are useful for process- or container-level isolation, not for assigning a method its own OS identity. Seccomp and Landlock can restrict an individual thread, but those restrictions are irreversible: you cannot lock down the caller for one method and restore its permissions when the method returns. A process-wide seccomp baseline can be synchronized across existing threads with `TSYNC`, and future threads inherit it; a parser thread can then receive tighter restrictions. Let’s say our parser receives its input as a byte array. We can dispatch it to a dedicated Java platform thread—not a virtual thread—and deny that thread permission to open files or make new network connections while allowing the system calls the JVM needs for parsing.

We can give a logical module its own restricted process or thread, bringing security and logic scopes closer. But a restricted thread still shares memory with other threads in the process. If a vulnerability lets it corrupt that heap, other threads can be affected too. A separate process is therefore the stronger isolation boundary. Browsers use processes for isolation for the same reason. We could do something similar in the backend by moving processing of untrusted data into a separate process. To make that practical, we would need language-specific tools that make working with processes almost as easy as working with threads, as right now that is not straightforward at all. Namespaces and cgroups fit naturally into this separate-process model.

## Replaying Java4Shell: Single Process vs. Isolated Worker

So, let's replay Java4Shell under two models—a hardened single-process JVM and an isolated-worker design—to see what actually survives.

Let's first assume that we have our app in one process - a regular case today.

Suppose an attacker has already gained code execution inside our JVM. If their payload depends on running a shell or another executable, we can block that step. Elasticsearch and OpenSearch do this on Linux for many years by installing seccomp filters during startup. The denied launch returns a permission error. We can alert on that failure if we monitor it. BubbleWrap or nsjail can give you similar results for any language, along with more features. And Kubescape, Tetragon and alike will tell you about Linux side of your app.

But what if the attacker’s goal is to read a secret already in the JVM heap? We are out of luck in that case. Dead code elimination, thread, process, stacktrace (to let only limited app methods execute particular syscalls) restrictions may increase our chances, but that is only luck at this point, as an attack might not even use syscalls. And even if it does, it can likely escape to unprotected platform threads.

Now, let's assume we have divided an app into several processes. All the parsing is done in one process, and business logic is in another. The parser can receive input and return results over a bounded channel, with no need for direct access to application secrets, arbitrary files, or the network. A process policy can also block it from launching other processes. If we manage to run all parsing only in a restricted parsing process, then the vulnerable decode with the heap overwrite occurs in the subprocess’s address space, which has no secrets. RCE in a restricted parser subprocess no longer hands over your main application's database credentials, signing keys, network access, or shell execution — blast radius shrinks drastically. Sadly that is easier to say than do today, but that is  a tooling/libraries issue, not some fundamental problem.

If you would like to explore these concepts, my in-app sandboxing research project [MazeWall](https://github.com/Pilleo/mazewall/) experiments with syscall supervision based on Java stack traces, threads, processes and with process “portals” that make calling a separate worker easier.

This article is not a recipe, but a review of things we can do as industry only in scope of sandboxing. As developers, we ignore more security topics that can be practical and useful, so do look into it, we have a lot of work to do to make them practical.  Do you check out Kubescape and SBoB to understand your apps better; nsjail, bubblewrap to restrict your apps better than your current containers can today; and of course consider contributing to these projects.
