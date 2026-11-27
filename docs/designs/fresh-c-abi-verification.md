# Fresh C ABI verification for Mazewall

- **Author** — Tier E Fixture (configured Git author)
- **Status** — Approved
- **Date** — 2026-11-25

## Overview

Mazewall's Platform tests compare JVM foreign-memory layouts with selected sizes, offsets, and constants reported by a small C probe built from host headers. A developer or CI job should be able to rely on a passing comparison for those values on the current host compiler target. A previously passing comparison can serve that purpose when the newly built probe and other test inputs match.

## The Problem

Before this change, Gradle tracked the probe source and one repository header when deciding whether to compile the probe. The installed headers and compiler could change independently. The test task depended on compilation but omitted the executable from its own inputs. A compiler or header update could therefore leave a previous passing test result in place even when a fresh probe would report a different ABI value. [Backlog issue](../internals/backlog/resolved/issue-20261103-235258-396-invalidate-the-c-abi-oracle-when-effective-native-.md) · [Gradle wiring](../../platform/build.gradle.kts#L73-L119)

## Goals

- Rebuild the probe from the current host compiler and headers whenever Platform tests or standalone ABI verification request it. If compilation fails, fail verification even after an earlier successful run.
- Invalidate the prior Gradle test result when the executable changes. Allow reuse when the executable and the other test inputs match. Preserve these outcomes for targeted tests and when Gradle reuses its Configuration Cache.
- Preserve both existing ABI comparisons, including the required fields and optional Landlock values. Keep `:platform:verifyCAbi` available to execute the current probe and print its observations. [Layout comparison](../../platform/src/test/kotlin/io/mazewall/ffi/CAbiLayoutOracleTest.kt) · [Stack-context comparison](../../platform/src/test/kotlin/io/mazewall/ffi/internal/RealNativeStackContextsTest.kt#L981)

## Non-Goals

- Production FFM binding generation and changes to the public API.
- Qualification of compiler targets beyond the host target used by the verification run.
- Expanding the set of C ABI fields compared by the existing tests.

## Technical Plan

The ordering that matters is between probe compilation and Gradle's decision to run or reuse `:platform:test`. Keep `:platform:compileCAbiOracle` as a required dependency of both consumers. In a single task graph Gradle runs it once, before either consumer. Targeted `--tests` runs and Configuration Cache replay use the same dependency. [Platform task wiring](../../platform/build.gradle.kts#L73-L108) · [Gradle input and output tracking](https://docs.gradle.org/current/userguide/incremental_build.html)

```text
Current host cc, headers, and existing flags
    |
    v
:platform:compileCAbiOracle (always runs when requested)
    |
    +-- compilation fails -> fail the build before either consumer
    |
    v
Successfully published layout-oracle executable
    |
    +-- :platform:test -> fingerprint the bytes, then run or reuse comparisons
    |
    +-- :platform:verifyCAbi -> execute the probe and print observations
```

### Build and publish the probe

Keep the current command's compiler lookup, C flags, and include directory. Resolve `cc` through the task process's current `PATH` when the task runs. Keep the executable declared as the compile task's output, disable up-to-date reuse for that task, and exclude its output from build-cache storage and restore. Those rules apply even if Gradle reuses its saved task graph. [Gradle task output controls](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/TaskOutputs.html)

Compile in a unique temporary directory beside the existing executable and replace the published file only after the compiler succeeds. Clean up the temporary path if compilation or publication fails. The same-filesystem replacement prevents a reader from opening a partially written executable.

### Consume the probe

Declare the executable as a file input to `:platform:test` and use its bytes in the input fingerprint. Keep the task's explicit dependency on compilation so Gradle fingerprints the file after successful publication. Gradle makes one reuse decision for the entire test task, including its current test selection. Different bytes invalidate the prior result, and normal reuse rules apply for matching inputs. When the tests execute, the two existing Kotlin readers launch the probe from the current output path. `:platform:verifyCAbi` runs the probe directly and propagates a failed exit. [Gradle file input normalization](https://docs.gradle.org/current/userguide/best_practices_tasks.html)

## Risks and Mitigations

- **Compile or publication failure.** Preserve the failure status and compiler diagnostics. The task dependency blocks both consumers even if a previous executable remains on disk.
- **Independent builds sharing outputs.** Serialize invocations in the same checkout. Run concurrent invocations in separate checkout copies, each with its own outputs, even if the compiler environments match. Atomic replacement cannot prevent a second build from replacing the binary between the first build's fingerprint and test execution. Both Kotlin readers resolve the probe under the checkout's Platform `build` directory.
- **Test cache scope.** A binary change can trigger a test rerun even when its reported values match. A reused comparison applies to the selected ABI values and skips execution of the probe. Use `:platform:verifyCAbi` when the current executable must run.

## Alternatives Considered

### Use the probe's observations as the test input

Compile and run the probe on every verification request, then give both Kotlin comparisons the resulting values. This guarantees a fresh probe execution and lets Gradle reuse test results when the binary changes but its observations match. It also introduces an output file with a publication and failure contract, and both readers need to change. In a direct compilation sample on Linux x86_64 with Ubuntu GCC 15.2.0, three clean builds in separate directories produced the same executable SHA-256 and the same 137 observation lines. This sample covers one host and compiler. Revisit the observations file if binary changes cause frequent reruns without ABI changes, or if every verification request needs to execute the probe.

### Fingerprint the native build environment

Fingerprint the effective headers, compiler, linker inputs, and environment before deciding whether compilation can be reused. This can save a native build on repeat runs. It requires maintaining the fingerprint as compiler behavior and include resolution change. Missing one effective input would allow the original defect to recur. The approved design recompiles a small probe and keeps the cache key tied to the executable the tests actually use. [Existing CET fingerprint](../../scripts/cet_fixture_fingerprint.sh)

### Generate layouts with jextract during verification

jextract can generate layouts from C declarations and provide a way to compare sizes, alignments, and offsets. Using it during verification adds jextract and libclang to the test environment. Verification would still need to select current headers and target settings and ensure generated outputs are refreshed before Gradle reuses a result. The existing probe already supplies the measurements this issue needs. [OpenJDK jextract guide](https://github.com/openjdk/jextract/blob/master/doc/GUIDE.md#structs--unions)

### Ship production bindings generated by jextract

Generated bindings could reduce manual FFM layout maintenance across the library. Shipping them would require decisions about supported targets, generated source ownership, and compatibility with current consumers. This work addresses freshness of the existing ABI check. Production binding generation remains a separate design decision. [Repository ABI validation decision](../internals/backlog/resolved/issue-013-manual-ffm-layout-maintenance-and-abi-drift-risk.md)

## Detailed Implementation Plan

1. Reproduce the stale pass in a disposable checkout copy using the actual Platform Gradle tasks. Run the baseline and header-change cases against the pre-fix wiring and retain the logs and probe hashes. Repeat with the corrected wiring to record the expected mismatch. Use copies of checkout files and keep each run's outputs separate.
2. Update [platform/build.gradle.kts](../../platform/build.gradle.kts#L73-L108). Keep `compileCAbiOracle` as an `Exec` task, with its existing inputs and published executable as its declared output. Set its up-to-date predicate to false and explicitly disable build-cache reuse and storage for that task. In the existing shell command, create a unique temporary directory beside the published binary, compile into it with the existing flags, and move the executable over the published path only after success. Clean up the temporary directory on exit while preserving the compiler or publication failure status. Keep the explicit dependencies from `test` and `verifyCAbi`. Register the published binary as a named `test` input with `PathSensitivity.NONE` so Gradle compares file content across runs. The Kotlin readers and `verifyCAbi` keep using the published path.
3. Add `scripts/tests/c_abi_oracle_cache_test.sh` for the permanent regression. Follow the disposable-fixture pattern in the [CET regression](../../scripts/tests/cet_fixture_cache_test.sh). Copy the repository's tracked working files into a fresh temporary checkout copy, excluding build and Git metadata. Use its Gradle wrapper and actual `:platform` tasks. In the copied `settings.gradle.kts`, set the local build-cache directory to a fresh fixture-owned directory. Keep the project outputs and cache for the entire sequence. Capture one log per invocation. On failure, print the violated assertion and relevant task output and retain the logs and their paths. Keep the fixture launcher and headers in the test's temporary area.
4. Update [the CI workflow](../../.github/workflows/ci.yml). Add an unconditional `Run C ABI oracle cache regression` step to `core-quality` after `Run Host-Safe Unit and Reproducibility Gates`. Run `bash scripts/tests/c_abi_oracle_cache_test.sh` and let failures fail that job. The standalone script is also the local regression entry point. The existing Gradle `build` continues to exercise the normal verification graph.
5. Update [the testing strategy](../internals/designs/core/testing-strategy.md) beside the host-test cache guidance. Describe why the probe recompiles, the reuse rule, how to run the standalone probe and permanent regression, and how to run builds independently.
6. After the focused checks and full gate pass, archive the issue with basename `issue-20261103-235258-396-invalidate-the-c-abi-oracle-when-effective-native-.md` in `docs/internals/backlog/resolved/`. Record the regression and gate evidence and update the [backlog index](../internals/backlog/README.md) and references to the [issue](../internals/backlog/resolved/issue-20261103-235258-396-invalidate-the-c-abi-oracle-when-effective-native-.md). Run `./scripts/adkw check-backlog` and `git diff --check`.

### Verification plan

Resolve the real `cc` before putting the fixture launcher first on `PATH`. Keep the launcher and its path stable. Forward other compiler calls unchanged. For calls compiling `src/test/resources/abi/layout_oracle.c`, log one invocation and add the fixture directory first in the include path. Keep the header, launcher controls, and invocation log outside the project copy. The fixture `fcntl.h` uses `#include_next <fcntl.h>` to include the host header. In changed mode, add `#undef O_PATH` followed by `#define O_PATH 0`. Assert that the normal probe reports a nonzero `O_PATH` as a fixture precondition.

Use a single marker file with no file for normal compilation, `compile-fail` for an immediate exit 41 with the diagnostic `fixture cc: forced ABI compile failure`, and `probe-fail` to replace the oracle source argument with a temporary C source whose `main` returns 42 and prints nothing. Keep the real compiler, include flags, and output argument for `probe-fail`. Empty stdout allows the Kotlin readers to reach their exit-code checks. These controls leave installed headers and the checked-in C file intact.

Use `--build-cache --configuration-cache --configuration-cache-problems=fail --console=plain` for the focused Gradle runs. Repeat the same command and filter for the stale-result sequence so changes in filtering cannot invalidate the test as a side effect. Use the following commands for focused verification.

```bash
./gradlew :platform:test --tests 'io.mazewall.ffi.CAbiLayoutOracleTest'
./gradlew :platform:test --tests 'io.mazewall.ffi.internal.RealNativeStackContextsTest.abiOracle matches actual header every member alignment and frame stride'
./gradlew :platform:verifyCAbi
```

| Case | Action | Expected result |
| --- | --- | --- |
| Baseline and unchanged repeat | Run the layout selection in normal mode with the pass-through header. Run it again to prime the task graph after the cold build has created inputs observed by PlantUML. Run one more time and compare compile counts and probe hashes. | The first comparison executes and passes. The measured repeat logs Configuration Cache reuse and one new oracle compilation. Matching hashes permit `:platform:test` to be `UP-TO-DATE` or `FROM-CACHE`. |
| Effective header change | Override `O_PATH` and run the same layout selection with prior outputs intact. Restore the pass-through header and repeat. | The build reuses the configuration, compiles a probe with different bytes, and fails at `:platform:test`. The current JUnit failure names `C ABI mismatch for flag.O_PATH` and reports actual value 0. Restoring the header restores a passing result. |
| Compiler failure after success | After the restored pass, run the layout selection and standalone check with `compile-fail`. | Each build fails at `:platform:compileCAbiOracle`, records the launcher invocation and the fixture diagnostic, and has no task outcome for its consumer. |
| Probe failure after success | Set `probe-fail` and run the standalone check and the layout selection. | Compilation succeeds in both runs. The standalone task fails with exit value 42. The layout selection executes and fails with `C ABI oracle failed` and actual value 42 in the current JUnit result. |
| Normal entry points | Remove the marker and restore the pass-through header. Run the standalone check and both Kotlin selections. Outside the fixture, run `:platform:unitCheck`, root `:unitCheck`, and `./gradlew build` once at the completion checkpoint. | The standalone check prints current observations and both selections pass. The production verification gates retain their coverage. |

During implementation, verify the permanent regression against two disposable copies of the fixed wiring. Remove only the `test` dependency in one copy and require the missing compiler invocation or wrong task outcome to fail the regression. Remove only the `test` file input in the other copy and require the altered-header case to reject a stale pass. Restore the original wiring before using the script as completion evidence.

*Drafted with EGM*
