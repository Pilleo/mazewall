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

Run the vulnerable-application demonstration with the existing entry point:

```bash
./scripts/run_vulnerable_app_demo.sh
```

That is the standard workflow. It must follow profile-first order: identity-checked image build, empty project volume, profile and unprotected readiness, benign profile workload, orderly profile stop, validated complete generation, then protected start. Protected start before that validation is a failed run, not a containment result. Details and the optional `MAZEWALL_DEMO_MODE=isolated` path are in [the demo guide](../demos/vulnerable-web-app/README.md).

The standard matrix is admin control, upload control, zip-slip effect versus denial, project-network SSRF, and SQL disclosure as a known application-level limit. A successful known-limit row still discloses data; it is not an OS-boundary block. Inferred permission failure is not authenticated denial. A missing route, HTTP error, timeout, or ineffective payload is not kernel protection.

Evidence for one run is `demos/output/<run-id>/`: phase timestamps, service logs, inspect JSON, copied profile components, both scenario JSON files, `report.md`, and `status`. Keep the script stdout as well; the Gradle build log is not copied into that directory. Verified retirement prints `retired mazewall-web-<run-id>`. `cleanup-error` means disappearance was not confirmed.

Docker 29.9.0 and Compose 5.6.0 are installed here. Podman is absent, and the selected Docker endpoint `/run/user/1000/podman/podman.sock` is missing, so this document does not claim a live container acceptance. The genuine complete profile→enforcement JVM case is also blocked: collector[1] drain did not complete, and collector[2] CONNECT was observed without a parsed destination. Narrower prepared-policy and publication/refusal JUnit proofs remain, and they do not need Compose.

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
