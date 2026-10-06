# Integration API review reconciliation — 6 October 2026

Review snapshot: `98da39f7f71b64a14633ab3f19046f6bc2afc0e3`.
Execution baseline: `0edb3d79c3e62b5b2f03b9ff9754d40e6cb0a871`.
Consumer baseline base revision: `d3e11915`, with the scoped publication corrections
recorded in `issue-20261006-152422-003-establish-packaged-java-and-kotlin-integration-con.md`.
The review snapshot is not an ancestor of the execution baseline; their common
ancestor is `e5c9046dd4e220bfdc41e0247e6c40aec832aa43`.

Paths below are under `enforcer/src/main/kotlin/io/mazewall/enforcer/` unless
qualified otherwise. Source-confirmed findings are not native exploit proofs.
Task IDs refer to the revised integration sequence in the execution ledger.

| Finding | Reconciled status | Current owner and preserving/required regression |
|---|---|---|
| A01 defaults | Source-confirmed documentation mismatch; explicit policy modes already exist | `api/JavaPolicyBuilder.kt`, `api/Policy.kt`; C1/C2 default and fixed-scope construction tests |
| A02 copied FS builders | Source-confirmed Kotlin copy trap; Java facade already retains promoted state | `api/PolicyBuilder.kt`, `api/Policy.kt`, `api/JavaPolicyBuilder.kt`; C2 imperative/fluent/apply parity and negative scope compiles; packaged Java/Kotlin fixtures preserve Java retention |
| A03 classpath breadth | Source-confirmed parent-directory grant in `addClasspathFile` | `api/PolicyBuilder.kt`; C4 exact JAR/sibling-secret isolated-kernel fixture |
| A04 setters/composition | Source-confirmed false-after-true setter issue; restrictive algebra already exists; active-empty reconstruction and immutable views fixed with tests | `api/PolicyBuilder.kt`, `api/JavaPolicyBuilder.kt`, `api/PolicyDefinition.kt`; `PolicyIntentContractTest`, `PolicyIntentNativeTest`, commit `9bd2306e`; C2 genuine setters and canonical FS intent remain |
| A05 owned offload | Source-confirmed cached-pool ownership/capacity and misleading facade prose; dedicated JDK factories already exist | `api/SandboxDispatcher.kt`, `api/ContainedExecutors.kt`, `internal/ContainedExecutorWrapper.kt`; C7–C9 saturation/replacement/nesting/lifetime and caller-placement fixtures |
| A06 explicit bypass | Source-confirmed wrapper target entry does not require installed receipt; default fallback remains FAIL | `internal/ContainedExecutorWrapper.kt`, `api/Platform.kt`; C3 required-install fault injection and partial-install retirement, then C7 worker preparation |
| A07 runtime safety | Source-confirmed applicability/safety distinction; runtime-specific presets already exist | `api/InstallationAssessment.kt`, `api/Policy.kt`; C3 final-policy/runtime rejection; C5 profile conversion regression passed in `PortalWorkerPolicyTest`, commit `d3e11915` |
| A08 coverage export | Result-level coverage gates and versioned behavior/coverage document already exist; guidance and reviewed approval model still require reconciliation | `profiler/.../api/ProfilingResult.kt`, `ProfileDocument.kt`, profiler README; C5 guidance/profile-backed acceptance, C6 reviewed-artifact round trips |
| A09 evidence strength | Existing observed/inferred taxonomy retained; transaction-boundary classification and guidance remain | `diagnostics/ContainmentViolationDetector.kt`, getting-started guidance; C8 invocation evidence, S2 evidence-before-commit; generic EACCES must remain inferred |
| A10 Java lambda | Reproduced against the actual staged enforcer JAR on JDK 25 | `api/Mazewall.kt`; `tests/consumers/java-api:verifyCallableAmbiguity` requires javac's ambiguity diagnostic; C9 replaces it with successful uncast canonical callable compilation |
| A11 distribution | Reproduced missing platform publication and absent public compile dependency; staged graph repaired, Java/Kotlin consumers passed | `platform/build.gradle.kts`, `enforcer/build.gradle.kts`, `tests/consumers`; C0 records exact local repository and compiler; R0/R1 still require advertised repository/native consumer/compatibility release gates |
| A12 boundary guidance | Existing shared-memory/descriptor/delegation limitations retained; adoption fixtures still required | Primary docs and C7–C9/R1 fixtures for unrestricted helper delegation, already-open resources, cancellation without termination |

## Evidence boundaries

The preceding four implementation commits passed host-unit/build and host/container
kernel gates recorded in `integrations/2026-10-06-inline-execution-status.md`.
Those runs predate the publication changes. The new consumer actions inspect policy
construction without installing kernel filters; the callable bodies are compile-only.
No Koog/Spring integration or release is established by dependency resolution.

The reviewed proposal's description of receipts as non-closeable is superseded by
the current lifecycle implementation: `InstallationReceipt.close()` owns supervisor
registration retirement. Preserve that real owner; closing it does not remove a
seccomp or Landlock restriction.

Breaking API migration is approved for this execution. Replace obsolete builders,
overloads, aliases, and global dispatcher facades after migrating callers; do not
retain deprecated bridges from the older additive roadmap.
