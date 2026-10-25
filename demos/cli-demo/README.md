# mazewall CLI demonstration

This application runs executable positive and negative controls and exits non-zero whenever the observed result differs from the declared expectation. A kernel containment denial, successful operation, and infrastructure failure are distinct outcomes; an unavailable daemon or unrelated I/O error never counts as a successful block.

## Start here: an owned workspace read

Use JDK 25 on Linux with Seccomp and Landlock available to the JVM. This command
launches a fresh JVM because its explicitly installed process baseline is permanent:

```bash
./gradlew :demos:cli-demo:runWorkspaceExample --console=plain
```

It creates private synthetic files, reads `workspace/input.txt` on one restricted
platform worker, and proves that the same worker cannot open a sibling the host
can read. A subsequent approved read still succeeds. Expect these observations:

```text
RESULT: workspace/input.txt = approved input
BOUNDARY: worker sibling read denied; host sibling read and subsequent approved read succeed
CLEANUP: actual worker retired and private fixture removed; process restrictions are not uninstalled
```

The actual file-open failure is labeled `INFERRED_PERMISSION_FAILURE`, not
authenticated kernel provenance. Setup/permission failures fail the command;
there is no unprotected fallback. Shared heap, preopened descriptors and unrelated
executors remain outside the worker body contract.

Read [WorkspaceExample.kt](src/main/kotlin/demo/WorkspaceExample.kt) for the policy,
bootstrap and `readWorkspaceFile(runtime, domain, path)` call, then
[OwnedDemoRuntime.kt](src/main/kotlin/demo/OwnedDemoRuntime.kt) for retained owner,
cancellation and bounded drain handling. The runtime belongs to the application,
not to each read. One worker/one admission is a demonstration limit, not sizing advice.

**Try a change:** in a private copy, change the first approved read's `allowed`
argument to `sibling` without changing the policy. It must fail with the real denied
open, rather than read the host's private sibling. Keep the later explicit denial
control and never widen permissions merely to get a green result.

For executable assertions in an IDE, run
[WorkspaceExampleTest](src/test/kotlin/demo/WorkspaceExampleTest.kt) or
[EnforcementPresentationTest](src/test/kotlin/demo/EnforcementPresentationTest.kt).
Missing-file failures are checked separately from real denied opens. Continue with
the profiling and lifecycle lessons in the [feature tour](../README.md).

## Modes

- `unsafe` expects the simulated JNDI exploit to create a marker inside an owned private temporary directory.
- `safe` applies the combined `Policy.NO_EXEC` and `Policy.NO_NETWORK` contract and exercises both process execution and a loopback connection. The network control first verifies the destination is reachable without containment.
- `profile` profiles a synchronous configuration-file read, refuses incomplete evidence, and reruns the same named operation under the compiled policy. Configuration content changes between runs so cached content cannot satisfy the positive control. It also attempts an unprofiled path read.
- `both` and the default `all` run `unsafe` and `safe` only. Profiling requires the explicit `profile` command.

Incomplete profiles are printed as diagnostics and never installed for enforcement. A profile run that cannot produce complete evidence exits with code `2`. Profile artifacts are not written over existing repository files.

The profile demonstration reads files synchronously; it does not demonstrate asynchronous io_uring containment. Permission-looking exceptions retain inferred evidence and never count as authenticated policy-denial proof.

Required execution uses `ManagedRuntime.open/prepare/submit` and real invocation handles,
with one dedicated platform worker and one admission per domain. Safe mode explicitly installs
the permanent `NO_EXEC_HOTSPOT` process baseline. Profile mode starts its daemon before opening
a runtime with an explicitly launch-compatible process baseline. Worker policies retain the
demo's existing strict executable-mapping restrictions; these are not a general HotSpot recipe.
Results are awaited for at most ten seconds; timeout or interruption requests cancellation,
not physical termination. The application retains runtime owners through bounded incomplete
drain and retries drain before exit. `executionEnd()` and completed drain account for actual
body/cleanup and worker retirement respectively; closing never uninstalls kernel filters.
Unprotected comparison execution remains separate and runs before the safe-mode baseline.

All attack markers and configuration fixtures are created beneath fresh owner-only temporary roots and removed afterward. The demo does not replace pre-existing files or follow caller-selected symlink targets.

## Running

Run inside the nested-seccomp development environment when exercising kernel containment:

```bash
podman compose -f infra/dev/compose.yml up -d
podman compose -f infra/dev/compose.yml exec mazewall ./gradlew :demos:cli-demo:run
```

Individual examples:

```bash
./gradlew :demos:cli-demo:run --args="unsafe"
./gradlew :demos:cli-demo:run --args="safe"
./gradlew :demos:cli-demo:run --args="profile"
```

Exit code `0` means every scenario matched its expectation. Exit code `2` means an attack unexpectedly succeeded, legitimate work failed, or infrastructure prevented a trustworthy conclusion.
