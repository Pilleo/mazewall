# Mazewall Lockdown

Lockdown is for Linux JVM services that need to launch external programs during
startup, if at all, but have no reason to do so while serving requests. After
startup, the service can ask the kernel to deny external program execution
across the entire JVM process, including existing and future threads. That
removes one capability compromised code could otherwise use.

`:lockdown` exists to deliver this protection as a standalone library. It is
the only artifact planned for `0.1.0` (`io.mazewall:mazewall-lockdown`), with
`kotlin-stdlib` as its only runtime dependency. You do not need the experimental
worker sandbox, Landlock, profiler, or Portal runtime.

## Install at startup

Follow the [installation guide](../GETTING_STARTED.md#gradle-dependency) for
GitHub Packages or a local snapshot. The `0.1.0` qualification targets are
HotSpot JDK 22 and 25 on Linux x86_64. Grant native access to `io.mazewall.lockdown`
on the module path or `ALL-UNNAMED` on the classpath.

```java
import io.mazewall.lockdown.Mazewall;
import io.mazewall.lockdown.ProcessPolicy;
import io.mazewall.lockdown.RuntimeProfile;

// On the startup platform thread, after required helper commands and before readiness:
Mazewall.installOnProcess(ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT));
```

Adding the dependency does not activate lockdown. Let installation failure abort
startup. Once installed, the filter cannot be removed from the running process;
restart to change to a less restrictive policy.

## Know the boundary

The default preset denies `execve`, `execveat`, `memfd_create`, `io_uring_setup`,
and `io_uring_enter`. Despite its name, `denyProcessCreation()` still permits
`fork` and `vfork`; those children inherit the filter. Programs started before
installation are not retroactively restricted.

This is not a sandbox for arbitrary JVM code. Lockdown does not prevent code
execution inside the JVM, restrict ordinary file or network access, or stop
requests to an external service that can launch programs on your behalf.

- [Startup placement and runtime profiles](../GETTING_STARTED.md#startup-placement--runtime-profiles)
- [Support and preset contracts](../STABILITY.md)
- [Public API](../docs/public-api.md)
