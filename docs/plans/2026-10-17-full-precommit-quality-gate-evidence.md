# Full pre-commit verification gate

## Operator request and diagnosis

Operator asked whether the commit hook ran build/verification and required that
it do so. Active `core.hooksPath` is `scripts/hooks` in the checkout. The tracked
hook previously invoked `test :portals:runtime:integrationTest :portals:runtime:test`
with `-x check`, excluding the complete verification lifecycle. It did not enforce
the repository's documented full build gate.

Gradle9.6.1 documentation was retrieved for this project's exact wrapper version.
It documents check as the verification aggregate and -x as excluding a task and
otherwise-unneeded dependencies. Repository build/check wiring and prior actual
full-build execution establish that build is the complete included-module gate.

## Scoped change

The hook now runs:

```bash
./gradlew build :portals:runtime:integrationTest :portals:runtime:test --no-configuration-cache --no-parallel
```

The explicit portal tasks are retained only when that module is included, with
the existing loud exclusion diagnostic. Gradle deduplicates tasks already reached
through build. There is no -x check or reduced test-only alternative. Existing
serialization, lock timeout and full log path stay intact; failure returns hook
exit1 and prevents the commit. Security audits still see the original staged Git
context, and only the Gradle subshell drops Git's own repository-local variable
names. Credential/environment handling is unchanged.

The existing audit wrapper delegates to this same tracked hook. No new dependency,
build task, opt-out, approval flow or operator Git configuration is introduced.
The already-active hook path picks up the change on local integration.

## Behavioral regression

- Red `run-9867962205a2`: the actual copied hook invoked test/-x check rather than
  the required build arguments, failing a real disposable Git commit regression.
- Green `run-c97fd98b0daa`: all5real-Git cases pass. Success selects build plus
  preserved portal arguments; a Gradle verification failure exit23 rejects the
  commit with hook exit1 and unchanged HEAD. Portal exclusion stays visible.
  Forbidden staged content rejects before Gradle, including an alternate index;
  both indexes and original repository state remain intact. Foreign Git operations
  affect only the foreign fixture, with Git-local variables absent from Gradle.
- Shell syntax checks pass for the hook, regression and audit wrapper
  (`run-3b0ba4577dc1`). The test replaces only the external Gradle boundary; actual
  hook code, Git exports, indexes, commits and failure propagation are exercised.

## Actual commit boundary

The commit containing this change executes the updated active hook against the
real repository. Its mandatory full Gradle build is the completion gate; a failed
build cannot produce that commit. The committing command's exit status and
`/tmp/mazewall-precommit-test.log` provide actual execution evidence in addition
to the controlled failure-path regression. Native opt-ins remain explicitly
selected by the operator's environment; the hook does not elevate privileges or
rewrite build settings.

The first actual GraalVM commit attempt (`run-2f8bff6c9276`) demonstrates the
stronger gate rejecting a real native failure, not just the controlled exit23.
The log begins with build plus portal tasks, and enforcer:integrationTest exits
nonzero after executable CodeHeap mmap is denied on pool-11-thread-1 inside
MmapProtectionTest's pkey_mprotect probe. Git aborts with the new full-build
diagnostic and HEAD does not advance. Fatal report
`enforcer/hs_err_pid3623280.log` and
`build/reports/precommit-full-build/graal-native-failure.log` retain that exact
invocation. This newly identified FFM adapter path is recorded under the existing
executable-mapping investigation; the failure is not waived or called a passing
GraalVM gate. The complete check set remains required on the selected runtime.
