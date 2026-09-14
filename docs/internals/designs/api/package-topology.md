# Domain-first package topology

`0.1.0-SNAPSHOT` has no production declarations in `io.mazewall` or
`io.mazewall.profiler`. Packages communicate ownership and stability, not just
source-directory placement.

| Module | Supported domains | Implementation domains |
| --- | --- | --- |
| enforcer | `enforcer.api`, `enforcer.policy`, `enforcer.installation`, `enforcer.diagnostics`, `enforcer.runtime` | `enforcer.internal.{compiler,seccomp,landlock,ffi,supervisor}` |
| platform | none | `platform.{native,fd,process,syscall,socket,lifecycle,ffi,seccomp}` |
| profiler | `profiler.{api,options,session,result,evidence}` | `profiler.internal.{runtime,engine,attribution,collector,tierE}` |
| portal | none | `portal.{runtime,protocol,service,artifact,cgroup,bootstrap}` |
| portal-worker | none | `portal.worker.{bootstrap,dispatch,protocol,runtime,service}` |

Public signatures may reference only supported enforcer or profiler domains and
JDK/Kotlin types. Platform, native, FFM, BPF, daemon, protocol, collector,
attribution, and replay domains are implementation-only. Production code must
not recreate a retired root or broad package; `verifyPackageTopology` enforces
that invariant in the normal `check` gate.
