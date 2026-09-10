# mazewall CLI demonstration

This application runs executable positive and negative controls and exits non-zero whenever the observed result differs from the declared expectation. A kernel containment denial, successful operation, and infrastructure failure are distinct outcomes; an unavailable daemon or unrelated I/O error never counts as a successful block.

## Modes

- `unsafe` expects the simulated JNDI exploit to create a marker inside an owned private temporary directory.
- `safe` applies the combined `Policy.NO_EXEC` and `Policy.NO_NETWORK` contract and expects the exploit to be denied without creating the marker.
- `profile` profiles synchronous file/socket work, compiles the observation, verifies the legitimate workload, and executes negative controls.
- `both` runs only `unsafe` and `safe`; `all` also runs `profile`.

The profile demonstration’s native file probe is a direct synchronous `open(2)` call. It does not initialize an io_uring queue, submit an SQE, or demonstrate asynchronous io_uring containment. The broader Seccomp/Landlock io_uring threat model is documented elsewhere in the project, but it must not be inferred from this executable.

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
