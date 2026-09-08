# Reproducing mazewall Results

This guide records the evidence needed to evaluate a mazewall demonstration. It does not certify an application for production use.

## Record the Environment

For every result, record the repository commit, Linux distribution and kernel, JDK vendor and version, CPU architecture, container runtime and version, outer container Seccomp profile, and the detected Landlock ABI. Capture the effective mazewall fallback setting as well.

mazewall relies on Linux Seccomp-BPF and Landlock. Results from a different kernel, JVM, container runtime, or outer security profile may differ.

## Run the Checks

Run host-side unit tests first:

```bash
./gradlew test
```

Run the containerized integration suite on a host with Podman:

```bash
./scripts/run_tests.sh
```

Run the vulnerable-application demonstration:

```bash
./scripts/run_vulnerable_app_demo.sh
```

The expected result is that permitted workload operations complete and the demonstration's configured-forbidden operations are denied by the kernel. Preserve the command output, test reports, policy used, and the exact attack input.

## Interpret the Result

A successful demonstration supports only the policy and workload that were exercised. It does not prove that all future code paths, dependency upgrades, lazy class loading, error handling, inherited file descriptors, or external process interactions are covered.

Thread-scoped policies restrict direct operations made by the contained worker. They do not isolate arbitrary hostile Java code from unrestricted executors or shared JVM memory. For a full account of the boundary, read the [security considerations](internals/designs/core/security-considerations.md).

## Suggested Result Record

Keep one record per scenario with:

- the workload and attack input;
- the process-wide baseline and worker policy;
- the declared expected allowed and denied operations;
- the observed result, exception, and kernel/JVM logs; and
- the environment details from this guide.

Re-run the scenario after a policy, application, dependency, JDK, kernel, or deployment change.
