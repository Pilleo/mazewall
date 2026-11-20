---
title: "Tier E hardening and stack-aware enforcement roadmap"
document_type: execution_plan
status: "proposed"
base_revision: b2c9b335720fd0491a8d86917a3b0593b818a4ec
---

# Tier E: reliable diagnostics, then stack-aware enforcement

**Goal:** Make Tier E a maintainable exact-attribution diagnostic/reference backend,
then reuse its pre-call Java-stack capture to further restrict selected operations
through the existing USER_NOTIF supervisor.

**Status:** Planning only. Source was inspected in a dirty checkout on 2026-11-03;
the base revision is an orientation point, not a claim that the checkout matches
that commit. No current runtime qualification follows from this plan. API names
marked *proposed* below are not available APIs.

## Delivery order

| Order | Deliverable | Implementation plan | Release gate |
|---|---|---|---|
| 1 | Correct lifecycle, terminal identity and usable diagnostic workflow | [A: diagnostic hardening](2026-11-03-tier-e-diagnostic-hardening.md), A1–A4 | Retryable retirement, truthful terminal reports, packaged operator smoke |
| 2 | Smaller maintained surface and scoped agentless reference | Plan A, A5–A6 | One documented path; no loss of evidence checks; qualified overlap only |
| 3 | Live pre-call snapshot foundation | [B: owned stack scoping](2026-11-03-tier-e-owned-stack-scoping.md), B1–B3 | Bounded snapshot reads with no stopped-thread stack walk or profiler dependency |
| 4 | Resource-safe, synchronous read-only file-open enforcement | Plan B, B4–B7 | Base-policy intersection, fresh code identity, fail-closed negatives, liveness |
| 5 | Measured optimization and a qualified opt-in release | Plan A A6 and Plan B B8 | Source-pinned correctness and workload-specific overhead budgets |
| 6 | Broader operations, asynchronous ownership, optional BPF-LSM research | Conditional work below | Each extension passes its own resource, lifecycle and qualification gates |

A is independently useful if B proves too expensive. B's capture prototype can
start once A1–A2 settle shared lifecycle assumptions; tooling consolidation need
not block it. B's release requires the relevant diagnostic regressions to pass
after shared native capture changes. Do not couple either release to BPF-LSM.

Execute through scoped backlog items and the repository's orchestrator review
path. This plan authorizes no worktree, delegation, commit, merge or publication.
Reconcile pre-existing edits before implementation; declare each delivery's files
and tests, including generated API dumps if affected.

## Existing work to reuse

- The [September production/boundary plan](2026-09-19-tier-e-production-readiness-and-boundary-selection.md)
  already covers the collector owner and configurable capture profiles. Inspect
  implemented components before scheduling residual work; do not recreate them.
- The [September handoff plan](2026-09-29-tier-e-poc-handoff-hardening.md) already
  specifies finalization failures, opt-in cross-UID evidence access and the
  operator helper. Plan A orders and extends those deliveries.
- Existing issues own [cleanup retention](../../internals/backlog/issue-20260926-121446-405-preserve-tier-e-resource-ownership-until-cleanup-c.md),
  [terminal shutdown](../../internals/backlog/issue-20260926-122439-817-make-tier-e-shutdown-terminal-across-service-and-c.md),
  [terminal snapshot identity](../../internals/backlog/issue-20260926-122447-716-bind-cached-tier-e-terminal-results-to-admitted-sn.md),
  [delayed artifact coverage](../../internals/backlog/issue-20261019-084828-251-investigate-tier-e-delayed-artifact-coverage-dropp.md),
  and [tooling migration](../../internals/backlog/issue-20260925-211445-014-move-active-tier-e-experiment-controllers-and-rema.md).
- The new [live-context foundation issue](../../internals/backlog/issue-20261103-085251-359-provide-live-pre-call-stack-context-before-user-no.md)
  owns B1–B3. Resource authorization, integration and later extensions need
  separate bounded implementation items before execution.

## Architecture and authority contract

### Two consumers of one capture boundary

```text
Owned platform worker, inside an admitted JDK native proxy
  clear previous live context
  capture current logical Java frames before the original native call
  publish bounded immutable live snapshot
       |
       +--> optional Tier E markers / ring / dictionaries --> diagnostics
       |                                                    policyEligibility=false
       v
  original native function issues open/openat
       v
  seccomp USER_NOTIF stops this syscall
       v
  existing supervisor prepares an immutable typed open request
       v
  JVM validation listener reads the already-published native snapshot
  validates worker + task + invocation + code generation + call path
       v
  existing authority helper checks resource capability and opens read-only
       v
  reactor validates notification and performs its one FD-injection reply
```

The live snapshot describes a call context, not an allow token. Every notification
gets a new resource decision and its own reply lease. Profiling ring delivery,
dictionary publication, marker attachment and terminal artifacts are outside the
authorization path. A stopped syscall cannot wait for its own Tier E event:
[Linux v6.12 entry code](https://github.com/torvalds/linux/blob/v6.12/kernel/entry/common.c)
calls `__secure_computing()` before `trace_sys_enter()`. Recheck this assumption on
each qualified architecture/kernel path.

### First supported envelope

- Linux HotSpot/OpenJDK, initially the existing qualified JDK 25 build and x86_64
  fixture; other JDK builds and aarch64 get independent qualification. JDK 22 is
  the library minimum, not evidence that every internal native binding is stable.
- Trusted bootstrap, agent loaded at JVM startup, `ManagedRuntime`-owned platform
  workers, synchronous task bodies and one frozen policy/resource generation.
- NIO regular-file reads reaching `UnixNativeDispatcher.open0/openat0`; accept only
  the kernel open-family routes actually demonstrated for those bindings.
  `java.io`, asynchronous I/O and unobserved bindings are not implied covered.
- Absolute paths beneath explicitly prepared read-only directory capabilities;
  a bounded allowlist of read flags. Initially reject relative/dirfd-relative
  requests, symlinks, magic links, mount crossings, creation, mutation, `O_PATH`,
  and nonregular targets. `openat2` is denied until a captured binding/route is
  separately demonstrated; using it inside the broker is a different matter.
- Same qualified PID/mount/user-namespace and credential envelope for worker and
  broker. Require USER_NOTIF, the existing safe FD-injection path, Landlock and
  constrained `openat2`; absence is a preparation failure, not a weaker fallback.

### What a stack decision means

The effective decision is:

```text
static syscall permission
AND broker-side filesystem/resource permission
AND current owned-task and code-generation identity
AND approved call path
AND optional existing ScopingHandler approval
```

An explicit static denial must never become `ACT_NOTIFY` followed by allowance.
The compiler must preserve the JVM coordination floor and all argument-sensitive
checks. Stack rules cannot replace the mandatory Tier 1 process-wide baseline.

Match a prepared, ordered call corridor from the native boundary through the
declared operation entry, using actual class/method identity. Reject unexpected
frames within that corridor. A package prefix, method name, line number, or
"trusted frame appears somewhere" is insufficient. Frames below the declared
entry are owned-executor plumbing and confer no additional authority. Retain the
full bounded capture so truncation cannot be disguised as a match.

Prepare class identities from actual loaded `Class` objects, defining loader,
module, method descriptor and the enrolled code generation; use deployment
artifact identities where already available. Pin enrolled classes for the owner
lifetime. Runtime/JDK corridor identity is tied to the qualified JDK build.
Unexpected redefinition/retransformation invalidates the prepared generation;
restarting/repreparing is required for the first release. Existing cooperative
symbolization resynchronization must not silently reauthorize old rules.
The supported deployment finishes instrumentation before admission and drains
owned work before intentional code replacement. Unexpected invalidation prevents
subsequent authorization; it cannot revoke a broker effect already committed.

### Snapshot and ownership rules

Use a fixed-capacity native slot per registered owned worker, allocated before
restrictions. A snapshot binds session/installation epoch, worker registration,
OS task identity, owned-task lease generation, invocation/parent identity, native
binding, code generation, completeness status and bounded frame tokens/locations.
Reuse existing typed PID/TID and ownership identities rather than inventing a
second process registry. Namespace translation must be established, not guessed.

The writer clears authority before capture and publishes only a complete stable
snapshot. The reader copies into its own bounded buffer using a nonblocking slot
acquisition; contention returns unavailable. No lock spans the original native
call, no reader waits on the tracee, and no racy C++ seqlock reads of non-atomic
storage are permitted. Failed capture, overflow, exhausted IDs, wrong generation,
unregistered thread or slot contention produce explicit unavailable reasons.

The read path performs no JVMTI stack walk, symbol resolution, class loading,
terminal-file read or callback into the stopped thread. Native snapshots expose
prepared frame tokens, not unguarded `jmethodID` pointers or borrowed memory.
`StackTraceElement` values for legacy callbacks are diagnostic projections of
that prepared registry, never the identity authority.

First-release live scoping admits leaf synchronous native calls. Nested capture
and Java callbacks suppress the outer live context and require a new supported
boundary; they cannot restore an old permit. Profiling retains its existing
separately tested nesting behavior. Later nesting support requires explicit
parent/execution validation. Sequential syscall retries within one still-active
leaf invocation may use its context, but each has a distinct notification and
fresh authorization; cancelled/late replies cannot authorize the next attempt.

Task exit invalidates its slot before the worker can admit another task. Physical
worker retirement precedes slot/class-reference release. Cancellation is not
retirement. Failed cleanup retains the owner and prevents replacement; retry only
outstanding releases. Agent/JVM shutdown invalidates authority before teardown.

### Resource authorization is a prerequisite

The current broker does not automatically inherit a tracee thread's Landlock
restrictions. A stack allow cannot fix that. For the first release:

1. Capture explicit read capabilities from the same prepared filesystem policy
   used for the worker. Transfer genuine owned directory FDs to the appropriate
   helper; bind them to installation/resource generation. String prefixes alone
   cannot establish a grant.
2. Establish the helper's filesystem confinement before it performs application
   opens, under the same deployment security envelope and a policy no broader
   than the worker's read contract. A helper narrowed to one domain cannot be
   recycled for a broader domain. Account for preopened descriptors and its
   bootstrap/runtime support resources explicitly.
3. Resolve the copied absolute request to a capability-relative suffix and acquire
   the object beneath its pinned directory using constrained `openat2` with
   internal `O_PATH`. Validate flags and require a regular object before opening
   it for reading. Reopen that exact helper-owned pinned object through a qualified
   descriptor-based path, under the helper's confinement, then verify identity
   before injection. This internal acquisition is distinct from accepting tracee
   `O_PATH` requests. Retain owners on failure; do not re-resolve the original path
   or open a device/FIFO first and rely on a later `fstat` to undo its effects.
4. Execute the copied request and inject its FD; do not authorize a pathname and
   then `CONTINUE` the original pointer-bearing syscall. Enforce notification
   identity checks before decisions/effects and dispose late results.

Known additional worker-only confinement cannot be bypassed by a more privileged
helper. The supported deployment must establish matching inherited security
constraints; arbitrary externally installed per-thread Landlock/LSM state is not
generally reconstructible. If equivalence cannot be established, preparation is
unsupported. B4 is an explicit feasibility/release gate, not an assumption that
existing path handling already supplies this property.

Bootstrap JVM-home reads need a separate, explicit read-only runtime capability
route so cold class loading does not demand a stopped-thread stack walk. It must
use pinned resources and the same constrained broker checks. Do not grant access
by `.class`/`.jar` suffix or a broad mutable application-classpath directory. Such
reads are recorded as runtime-support decisions, not stack-authorized application
operations.

### Trust limits

The JVM/agent supplies the stack; native compromise can forge or corrupt it.
Legitimate methods can also be called with malicious arguments. This design
restricts trusted synchronous application code processing untrusted input; it is
not an ACE boundary, hostile-plugin sandbox, or descriptor-use confinement after
an authorized open. Threads share memory and descriptors. Use the existing
process/portal boundary for hostile code. BPF task storage would not change this
trust limit. Tier E evidence always retains `policyEligibility = false`.

## Conditional follow-on deliveries

### C1. Expand one operation at a time

After B8, choose from real workload misses, not the native manifest's size.
First candidate: synchronous `connect` to a configured endpoint, using the
existing `net_connect0` binding and socket broker route. Scope socket identity,
address bytes, IPv4/IPv6/Unix semantics, DNS responsibility, credentials/network
namespace, timeout/cancellation and FD ownership before implementation. Allow a
stack only when the endpoint policy also allows the copied request.

Writes/creation/unlink need separate side-effect and race contracts: cancellation
after a broker effect does not undo filesystem mutations. Accept/read/write
coverage must account for already-open/shared descriptors. No expansion may
block JVM coordination calls or intercept `clone` indiscriminately. Each delivery
adds positive, wrong-path, wrong-resource, missing-context and late-reply tests,
then earns a support-matrix row; incomplete coverage remains explicit.

### C2. Asynchronous work and virtual threads

Do not inherit a stack through `ThreadLocal`, a package name or an arbitrary
executor. Design an owned, typed submission capability binding issuer/domain,
resource-policy generation, task lifecycle and revocation. The receiver checks
its own fresh synchronous stack plus the propagated authority's intersection;
the original stack is provenance only. Test pooled-thread reuse, cancellation,
double use, stale generations and nested submission before adding an API.

Virtual-thread enforcement is a separate worker/carrier ownership design. Never
install seccomp from a virtual thread. Tier E's virtual-thread attribution is
useful evidence, but does not solve carrier contamination or async authorization.
OpenJ9 and native-image capture similarly need separate support designs; preserve
existing unsupported-mode reporting rather than claiming JVMTI portability.

### C3. Optional BPF-LSM experiment

**Entry:** B8 is correct; profiles show the supervisor/broker is a material cost;
a supported Linux deployment can enable the needed BPF-LSM hooks and privileges.
Start with one file or socket hook and a written hook/object coverage matrix.

- Use the [kernel BPF-LSM contract](https://docs.kernel.org/bpf/prog_lsm.html).
  Seccomp tracing programs cannot return LSM authorization verdicts.
- Capture before the operation and install task/incarnation/invocation-bound,
  expiring context. The LSM hook independently applies resource policy to the
  actual kernel object, preserves prior LSM denial and denies missing/stale
  context for the selected application operation.
- Solve reentrancy, fork/exec/TID reuse, task exit, policy-generation changes and
  hook-specific semantics. A userspace-writable label remains untrusted after
  JVM compromise. Tracepoint/ring delivery is never the authorization handoff.
- Explicitly prove fail-closed lifecycle if the loader/daemon dies. Last-FD
  detach must not silently lift restrictions. If protected program ownership
  cannot maintain the contract, retain USER_NOTIF and report the experiment as
  unsuitable for enforcement; do not inherit Tier E's observability detach rules.
- Compare the same operation, resource constraints, fault matrix and matched
  workload against B8. Promote only with zero correctness regressions, a frozen
  deployment/SLO contract and a measured benefit large enough to justify the
  additional privileged backend. Otherwise stop the experiment.

This is a research deliverable first: hook prototype, evidence and go/no-go
report. Production BPF-LSM code needs a separately scoped plan after those results.

## TDD cases

- Failed finalization plus retryable cleanup retains one owner and one terminal outcome.
- Missing/stale pre-call context denies the selected application operation.
- An approved call path cannot override a static denial or read outside broker capabilities.
- Cancelled notifications, task reuse and code invalidation cannot reuse an earlier verdict.

Detailed regression steps and the red/green checks belong to Plans A and B.

## Completion evidence

Each implementation delivery records source/configuration fingerprints, focused
test output, relevant native/kernel evidence and unresolved limitations. Run the
root `./gradlew build` once at that delivery's completion checkpoint after focused
checks; use the configured container lane for required kernel evidence. Historical
receipts qualify their recorded configurations only.

For this planning change, validate local references, `git diff --check` and
`./scripts/adkw check-backlog`. No application build or benchmark is required to
validate Markdown. The plan was authored in the current Astra session; it has
not received an independent Sol planner/reviewer pass.
