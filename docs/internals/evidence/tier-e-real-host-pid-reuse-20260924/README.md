# Tier E real guest-host PID reuse, 2026-09-24

The disposable Multipass `mazewall-pidtest` Ubuntu 26.04.1 guest (kernel
`7.0.0-31-generic`) ran a rootful Docker, registered marker-v2 hybrid collector.
Guest snapshot `before-pid-reuse` preserved the toolchain and original
`kernel.pid_max=4194304`. Only inside the guest, the limit was temporarily set
to 4096 and restored to 4194304 after this run. The normal host's PID limit
remained 4194304. The collector's Docker host PID namespace is the **guest**
host PID namespace, not the target container's PID namespace.

Command run inside the guest, from the archived source tree:

```bash
TIER_E_SPLIT_REUSE_BUILD=1 TIER_E_SPLIT_MARKER_VERSION=v2 \
  TIER_E_SPLIT_RECOVERY_MODE=recover TIER_E_SPLIT_COLLECTOR_BENCHMARK_MODE=1 \
  TIER_E_SPLIT_REAL_PID_REUSE=1 \
  TIER_E_SPLIT_ARTIFACT_DIR=/home/ubuntu/mazewall/build/pid-reuse-qualified-final \
  bash scripts/run_tier_e_split_privilege_fixture.sh
```

The non-root target attached and reported an active collector. The fixture
paused the collector after that admission, killed the target (exit 137), and
spawned at most 8192 short-lived **guest-host** processes within 75 seconds.
The bounded probe found its original registered PID 3514 again after 3121
attempts: `/proc/3514/stat` start time changed from 133817 to 134936. It held
the real replacement alive, verified its `/proc` identity before resuming the
collector and again after the terminal report. The collector rejected the
replacement with `PROCESS_IDENTITY_REUSED`, `INCOMPLETE`,
`lifecycle_validity=false`, `transport_completeness=false`, zero resolved
syscalls, and `policy_eligibility=false`. The agent-written marker proof records
the original incarnation and v2 selection. The distinct unprivileged user
could not access the collector control socket.

`real-pid-reuse-probe.json`, `terminal-report.json`, `marker-version-proof.txt`,
`target.log`, `collector.log`, `probe.log`, `control.log`, `unrelated-user.log`,
and `verdict.env` retain the raw result. `SHA256SUMS` binds those files and this
README. This is a controlled watcher pause to force the real replacement into
the observation window; it does not measure how often an unpaused collector
would encounter this race, nor make experimental hybrid evidence eligible for
policy generation or default promotion.

The guest was prepared from committed revision
`6f4be1b635f0254178689030867091dbaa765022`, with only these fixture inputs
transferred afterward (SHA-256 in guest and local worktree matched):

| Input | SHA-256 |
|---|---|
| `scripts/probe_tier_e_real_pid_reuse.py` | `e43d345bfee6f6ce263789a63caa1d58c31848118afc467763a5378cc139b6d7` |
| `scripts/run_tier_e_split_privilege_fixture.sh` | `f0b9be8ef6319d22a7292db66097d2f9dc05f033bebae4aad3cf66047575b4f5` |
| `profiler-native/tests/AgentWorkload.java` | `b14f4a705c221dcff4424ffa5546ce1c0b953237bb71403f09f3029bd451a2d9` |

The guest's profiler distribution JAR SHA-256 was
`b054fc36ccd04dc5072ef649954850eb4f7fe3c3033251f23f1903baad3c6690`;
native agent SHA-256 was
`7d6459afeaa340b3a53510709946c6333d9d6d4e662a299d8ce0ec265af9ed5d`;
Docker image ID was
`sha256:00f840c947b3d56a45cc1d94d4d2e9e1a79d5ef45057932e577c9a0bcc8a1afa`.
