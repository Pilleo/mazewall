# Owned delegated-cgroup native fixture

`RequiredWorkerLaunchProfileTest` contains a positive native case selected by
`MAZEWALL_TEST_DELEGATED_CGROUP_ROOT`. An absent variable is the named prerequisite
“fresh owned delegated cgroup v2 unit with cpu and memory controllers”; any explicitly
supplied unsupported root fails. The existing ordinary-directory refusal stays active.
The test also requires its own JVM to be in the supplied root's `runner` subgroup.

Run only in a fresh operator-owned user service. This needs cgroup v2 cpu/memory
delegation and systemd supporting `DelegateSubgroup=runner`. Acquire a unique unit,
set `Delegate=yes`, `DelegateSubgroup=runner`, `RuntimeMaxSec=15min`,
`TimeoutStopSec=30s`, and `RemainAfterExit=yes`. Inside that service derive its own
root from `/proc/self/cgroup` (the process must be in `<unit>/runner`), check an empty
root `cgroup.procs`, then write `+cpu +memory` to **that root's**
`cgroup.subtree_control`. Export that root as the variable above. Never configure a
shared user-manager/app.slice root or move existing processes into this service.

The owning controller holds the shared verification lock across unit acquisition,
root setup, Gradle/native commands and final unit retirement. Acquire it in the
controller with `exec 9>/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/verification.lock`
and `flock -w 600 9`, retaining that descriptor until cleanup finishes. Do not
reacquire it inside the service payload.

Example service payload, with the worktree as working directory and the controller
already holding that lock:

```bash
set -eu
membership=$(sed -n 's/^0:://p' /proc/self/cgroup)
test "${membership##*/}" = runner
root=/sys/fs/cgroup${membership%/runner}
test ! -s "$root/cgroup.procs" || test -z "$(cat "$root/cgroup.procs")"
printf '+cpu +memory\n' > "$root/cgroup.subtree_control"
export MAZEWALL_TEST_DELEGATED_CGROUP_ROOT="$root"
export JAVA_HOME=/home/leanid/.sdkman/candidates/java/25.4.4+1-graal
export PATH="$JAVA_HOME/bin:$PATH"
# Use the established Gradle cache; set GRADLE_USER_HOME at the owning command.
timeout --signal=TERM --kill-after=30s 600s \
  ./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  :portals:runtime:integrationTest --rerun --tests io.mazewall.portal.RequiredWorkerLaunchProfileTest \
  --offline
```

Acquire with `systemd-run --user --unit=<unique-name> --property=Delegate=yes
--property=DelegateSubgroup=runner --property=RuntimeMaxSec=15min
--property=TimeoutStopSec=30s --property=RemainAfterExit=yes
--working-directory=<worktree> /bin/bash <owned-payload>`. Keep the actual unit
name, ControlGroup, MainPID, invocation ID, start/end timestamps, ExecMainStatus,
log, test XML, and source hashes. Observe its kernel membership/controllers, then
stop only this acquired unit after completion and verify its cgroup directories
and acquired PIDs are absent. `Result=success` without execution/exit/test and
physical-end receipts is insufficient.

The fixture applies `memory.max=268435456` and `cpu.max=50000 100000` to its actual
child before returning any peer to the broker. It observes the pending parent
authorization diagnostic, checks kernel PID membership, exercises genuine worker
ACK/readiness membership validation and a real generated guest dispatch, then
asserts reap, pump end, exact socket retirement, endpoint and worker cgroup removal.
The Gradle single-use daemon/compiler/test JVMs stay in the owned service; no global
tool configuration is changed. This is local native fixture evidence, not production
launch approval, a CPU/RAM exhaustion test, or generalized descendant containment.
