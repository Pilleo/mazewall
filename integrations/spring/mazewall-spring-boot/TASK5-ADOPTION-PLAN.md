# Task 5 typed-owner adoption plan — gated, not implemented

Authority: task-5-report.md public contract for core revision `d0f91001`, which is
UNDER REVIEW. Coordinator approval/integration of the reviewed core revision is a
prerequisite. This plan does not copy source, add reflection shims or merge core.
S1/S2 host fixes do not close production acceptance.

## Public contract to consume after approval

```text
PreparedOperationResources.resolve(
    RuntimeProfile, Policy<ThreadLocalOnly, Uncompiled>, Class<?>, Set<Path>)
PreparedOperationResources.{runtime, implementationClass, implementationSha256,
    resourceGeneration, validate(), close()}
ManagedRuntime.prepare(ReviewedOperationArtifact, DomainLimits, PreparedOperationResources)
```

The digest identifies a pinned **finished ordinary JAR**, not a `.class` resource.
Resource generation is an opaque deployment identity incorporating actual objects
and runtime closure, not a portable artifact version. The typed owner/core supplies
class/loader/JAR/member agreement and resource lifetime. Trusted immutable deployment
without redefinition remains required; this is not defined-bytecode attestation.

## Next separately reviewed adapter slice

1. Inspect the existing registered singleton/static target without invoking it.
   Select its actual implementation `Class`/defining loader and bridged public
   instance JVM method. Reject required non-public/static/unmatched declarations.
   Resolve using the explicitly configured fixed worker policy/runtime and strict
   read-only `Set<Path>`. Constructors/init/trusted resolution remain outside body
   coverage; no hidden baseline or collection-as-approval.
2. Match EXTERNAL approval: expected binding includes actual bean name/object,
   class, descriptor, owner's finished-JAR SHA and deployment generation. Load the
   external document with independently configured `TrustedPolicyReview` and this
   binding. Fresh hashes/documents do not establish reviewer authority. Keep phase,
   object, loader and policy/runtime expectations in trusted application configuration.
3. Add a typed production `DomainLifecycle` path retaining this exact owner and
   calling `owner.prepare(reviewed, limits, resources)` on explicit start. Do not
   route it through metadata-only two-argument preparation. Missing/mismatched/
   inactive owner fails before target entry. Keep S0/host-characterization semantics
   explicit rather than relabeling the old path exact resource readiness.
4. Bind actual target `Class` identity to `resources.implementationClass` and its
   defining loader to the external expectation. Compare reviewed SHA/generation to
   the owner's finished-JAR identity. Preserve descriptor/registered proxy/advisors.
   The current `implementationSha256(Class)` class-resource helper must not be the
   production JAR verifier or fallback. Core revalidates actual resources at
   preparation/dispatch; metadata equality is not a replacement.
5. Successful preparation consumes one owner once. Close unconsumed owners on
   abandoned resolution/review/preparation, preserving original/fatal failures.
   Verify the APPROVED failure/transfer contract before implementing cleanup;
   caller close after transfer must not revoke workers. Runtime physical drain/
   retirement owns transferred descriptors; incomplete drain retains/reports exact
   owners. No adapter FD duplication, fabricated token or second ruleset/compiler.
6. Gate common-domain origins. The supplied contract selects one actual Class per
   owner; matching policy/generation labels do not own another Class/JAR. Initially
   admit only bindings proved by that selected class/loader/member origin. Different
   implementation Classes/origins must reject on the stronger production path unless
   the reviewed core contract can validate/include EVERY origin in the SAME prepared
   domain. Do not prepare separate domains and claim inline nesting, combine permissions
   or close secondary owners and claim their objects remain pinned. Current host
   grouping tests remain metadata/placement evidence.
7. Ordinary finished JAR scope stays explicit. Boot nested/exploded/versioned
   origins and unsupported loaders reject until a separate reviewed resolver supports
   them. Never substitute class hashing or allow an artifact's parent directory.

## Required verification after the reviewed API is present

- Compile against integrated public ABI, without unreviewed sibling source/stubs
  or compiler metadata-skip flags.
- Actual finished fixture JAR/loader binds with JAR SHA; class-resource SHA,
  changed JAR/member/loader/generation, wrong bean/review and unsupported origins
  reject with zero target entry. A fixture JAR is not packaged Boot consumer proof.
- Genuine unconsumed-owner cleanup, once-only transfer, incomplete drain,
  cancellation and physical end preserve/release the exact owner under the approved
  core contract; Future completion is not physical-end evidence.
- Production-dispatcher actual JDBC in disposable JVMs: allowed work, required
  filesystem denial, beforeCommit evidence, fatal identity, caller delivery/physical
  end separation and honest transaction/savepoint effects. Generic NIO/EACCES remains
  inferred without structured library-observed verdict. Required native skips fail.
- Retain host declaration/bridge/interface, all-seven propagation, savepoint-fault,
  ancestor/descendant and committed-inner/afterCommit regressions. No mock transaction
  assurance substitutes for actual JDBC.

## Required coordinator/root update

1. Review task-5 and task-6 fixes, then integrate the APPROVED core ABI/owner revision
   and scoped Spring commits into a common baseline through the serial review path.
   Supply that baseline for the typed adoption slice. No writer cherry-pick of
   unreviewed core is requested.
2. `includeSpringIntegration=true` wiring and BOM4.1.1 already exist at this base;
   no new settings/catalog/version patch is needed. Coordinator must include the
   optional Spring flag in its owning merge/release checks: the default commit hook
   does not run the optional adapter suite.
3. Coordinator owns backlog index/count for the open task-6 issue
   `issue-20261008-033416-558-bind-approved-synchronous-spring-singleton-operati.md`
   and task-5 issue. Retain X1/native/JAR/Boot blockers in the ledger; host greens
   do not justify archive. Root/index were not edited by this writer.
4. Coordinator owns combined/native/packaged and later full-build/container gates.
   Actual Gradle/commit commands remain under the shared lock. S3 starts only after
   reviewed S1/S2. No native/JAR/Boot acceptance is claimed by this plan.
