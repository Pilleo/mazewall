# Native completion attribution evidence — 2026-10-17

## Decision and revision

The [parent investigation](../internals/backlog/issue-20261015-120935-494-diagnose-executable-mapping-eperm-during-enforcer-.md)
remains **open, HIGH**. The original pid88950 callable is still unknown. The new
owned diagnostic completed under both profiles on both inspected VMs; it did not
reproduce the historical crash or establish historical callable attribution,
arbitrary JVM safety, or permission to weaken executable-memory protection.

Evidence was inspected on branch `fix/native-completion-attribution` at Task 3
BASE/current implementation commit `49da8dcff79c2945e0a19199dde7ce6a61bfdb57`.
Task 1 phase-slot commit is `e69c59d69cc24e14314642d5bfc65bf1976812b9`.
This document's subsequent documentation-only commit is identified by Git history.
No production profile, policy, assertion, native interface or build gate changes
are authorized by these observations.

## Exact original reports (read-only)

The historical relative paths resolve against the **source checkout**, not the
linked execution worktree. Both originals were read at these absolute locations;
neither was edited or reconstructed:

| Original absolute path | SHA256 |
| --- | --- |
| `/home/leanid/Documents/code/java/jseccomp/.worktrees/repository-cleanup/enforcer/hs_err_pid88950.log` | `efee252f5b7c31a5d02d38e0b2b90613bc6fa04311776f890c964879c3d3a065` |
| `/home/leanid/Documents/code/java/jseccomp/.worktrees/repository-cleanup/enforcer/hs_err_pid199455.log` | `58e33e793f89901d795c3ab5354d1a7401cac0599000b95e4e7dd69f492c62b1` |

- **pid88950:** JRE `Java(TM) SE Runtime Environment Oracle GraalVM
  25.4.4.1.1+1.1 (25.0.4.1+1)`, build
  `25.0.4.1.1+1-LTS-jvmci-25.4-b23`; Java HotSpot(TM) 64-Bit Server VM,
  mixed mode, tiered/JVMCI compiler, G1, linux-amd64. Current Java thread is
  `pool-2-thread-1`, native TID **89026**. Java frames include
  `VarHandleGuards.guard_LI_V+45`, `FutureTask.set+22`, `FutureTask.run+68`.
  Native frames include `SharedRuntime::generate_native_wrapper` and
  `CodeHeap::expand_by`; the fatal header records a 65536-byte mmap commit failure.
  This is the completion path after callable return: the stack does not identify
  the callable, test or installing policy. Thread-name similarity cannot fill that gap.
- **pid199455:** JRE `OpenJDK Runtime Environment (25.0.4.1+1)`, build
  `25.0.4.1+1-1-26.04.4-Ubuntu`; OpenJDK 64-Bit Server VM, mixed mode,
  tiered, G1, linux-amd64. Current Java thread is `pool-2-thread-1`, native TID
  **199540**. The Java stack names `ContainedExecutors.applyBpfFilter+385` and
  `ContainmentDesignSpec$1$6$1.invokeSuspend$lambda$0+41`, with invokedynamic /
  MethodHandle linkage, native-wrapper generation and CodeHeap expansion for a
  65536-byte mmap commit. This is the **distinct, corrected callback case** in
  [the scoped bootstrap evidence](2026-10-17-containment-post-install-bootstrap-evidence.md),
  not a second unidentified FutureTask completion case.

The generic insufficient-memory text is not evidence of RAM exhaustion. These
fatal reports supply VM/thread/stack facts; the parent's retained EPERM run
observations remain separate from the new successful controls.

## Artifact provenance and actual new outcomes

Absolute execution root (abbreviated **W** below):
`/home/leanid/Documents/code/java/jseccomp/.worktrees/hotspot-review-remediation`.

- **R** = `W/enforcer/build/reports/native-completion`, the actual generated
  report root inspected before Task 3's commit gate.
- **A** = `W/.superpowers/sdd/2026-10-17-hotspot-native-completion-attribution/task-2-artifacts`,
  the retained Task 2 logs, XML/binary test results and raw `native-completion/` copy.
- Every `journal.txt`, `outcome.json`, `vm.txt` and `child.log` in R was compared
  byte-for-byte with `A/native-completion/`: all **40 files match**. The source
  checkout's `/home/leanid/Documents/code/java/jseccomp/enforcer/build/reports/native-completion`
  has no journals at inspection time; it was not used to invent additional runs.
- Task 1/2 reports and `progress.md` are retained alongside A. The pre-commit
  runs below were captured before Task 3's hook can append another UUID directory.
  A preserves the original evidence independently of mutable Gradle outputs.

Each UUID has `hot/` = HOTSPOT_JIT and `strict/` = NATIVE_IMAGE. Each journal
records `policy=denyList:<PROFILE>:no-explicit-blocks`, Java worker ID **25**,
worker name `mazewall-native-completion-probe`, and final phase **WORKER_RETIRED**.
Each outcome has `exit_code=0`, `completed=true`; all ten child logs are empty
and all ten directories contain **zero fatal reports**.

| UUID / capture | VM | hot PID / native TID | strict PID / native TID |
| --- | --- | --- | --- |
| `fc510ba4-6988-4e64-aa0b-795baa6b520f` / initial | OpenJDK | 4088099 / 4088126 | 4088128 / 4088162 |
| `23d00543-0547-4dbd-b267-9fe1958ea286` / initial | GraalVM | 4088556 / 4088582 | 4088585 / 4088608 |
| `7fd057bb-6727-4457-96a6-480e813d30e7` / corrected final | OpenJDK | 4102812 / 4102962 | 4102982 / 4103018 |
| `31106d8e-32dd-4b24-b8a6-f45da7308228` / corrected final | GraalVM | 4103389 / 4103413 | 4103423 / 4103458 |
| `913bd4ec-fc08-4a09-884c-d1af284077ef` / Task 2 full hook | OpenJDK | 4110821 / 4110843 | 4110845 / 4110867 |

Actual `vm.txt` runtimes: OpenJDK `25.0.4.1+1-1-26.04.4-Ubuntu`, VM
`OpenJDK 64-Bit Server VM`; GraalVM `25.0.4.1.1+1-LTS-jvmci-25.4-b23`, VM
`Java HotSpot(TM) 64-Bit Server VM`. Selected homes were
`/usr/lib/jvm/java-25-openjdk-amd64` and
`/home/leanid/.sdkman/candidates/java/25.4.4+1-graal`.
All children record native access, `-Xms128m`, `-Xmx256m` and their own absolute
`-XX:ErrorFile=.../hs_err_pid%p.log`. GraalVM also records
`-XX:ThreadPriorityPolicy=1`, `-XX:+UnlockExperimentalVMOptions`,
`-XX:+EnableJVMCIProduct`, `-XX:+EnableJVMCI`, `-XX:-UnlockExperimentalVMOptions`.
These are actual child inputs, not a claim that all Gradle worker flags forwarded.

### Synchronization correction and meaning of phases

The initial diagnostic used the brief's fixed `Thread.sleep(5)`. Task 2's first
full hook failed `TestSynchronizationPolicyTest`. The correction at the recorded
implementation commit uses `future.get(5, MILLISECONDS)` as the polling barrier,
catching only that wait's `TimeoutException`. It preserves the 5ms polling bound,
20s reporting deadline, final checked get and owned retirement; other exceptions
and interrupts propagate. Both corrected final native runs and the synchronization
policy check passed. The failed hook/XML remain retained alongside the successful
hook/XML; initial UUID results are not relabeled as corrected runs.

The platform worker binds its Java ID/native gettid before INSTALLING and alone
writes through RETURNING. Identity before INSTALLING is unknown. The uncontained
child main writes COMPLETED only after checked `future.get(1, SECONDS)` returns 3,
and WORKER_RETIRED only after executor close returns. Reporter snapshots, strings
and file writes stay outside the restricted worker. Phases are monotone progress
lower bounds, not exact failure locations. Closing the receipt does not remove
the installed filter. Parent process ownership uses the existing 30s owner;
ownership/cleanup failures propagate rather than becoming synthetic crash success.

### Raw artifact SHA256 manifest

Paths below are relative to both R and `A/native-completion/`. Shared hashes:

- Every `child.log` (zero bytes): `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
- Every `hot/outcome.json`: `031b1fd8c00bedaaec52457cd0d7724b4a9f532db4e6c69c0c8e80a66e140c67`.
- Every `strict/outcome.json`: `1114fca24b191ca4cac378db0f6f4f14c477ff3e655515f4c577819feb2d4014`.

| Relative child directory | journal.txt SHA256 | vm.txt SHA256 |
| --- | --- | --- |
| `23d00543-0547-4dbd-b267-9fe1958ea286/hot` | `1ea3cabfa47913b82f361728fb9586dba82c38169c481a894236303b2b26ea7e` | `dffa2afbeb859c4660525d866b3d34ae3d0a00779253933fdaf19220edf0313e` |
| `23d00543-0547-4dbd-b267-9fe1958ea286/strict` | `556758b1a2bfb15be31cd349636fb083bb280463af2c0408cfacb43e88fc884c` | `6c0f776b383aa484bbb3f376b8fdadc4210851d360275089a374075f8b0427cb` |
| `31106d8e-32dd-4b24-b8a6-f45da7308228/hot` | `2edea8503132827e3b5fb40f5722c6ae69cf43d6e38258cefe410e16da2c0ead` | `072f3ddce5c89e9a81e991080d730345281e810d95d02f525534aa5c78016141` |
| `31106d8e-32dd-4b24-b8a6-f45da7308228/strict` | `17326d3376b0376ab5c520f1cbdce1232e854de49f1dd725bcdff8efa3e8bd17` | `7716b0d8b02d48b2a0147fd78c41df8d763a03bb2e20642c51be050263489a3e` |
| `7fd057bb-6727-4457-96a6-480e813d30e7/hot` | `e82f9a8ace1b56955fc13cc58cd0447f5e9bd305cf42c46673273680b4e20c0e` | `e8e46fbc0f2e6c8d7d515b75510d6127f0950574795de1f0c520a1d844c69c3d` |
| `7fd057bb-6727-4457-96a6-480e813d30e7/strict` | `dbc0a1758845a8faac737582aa8f957971b1a243cb7ab8171442166a05f231f5` | `a09cf8b6864358e4ac255d2acc75228f479c285a72bbaab7ecf00160fdc7cf91` |
| `913bd4ec-fc08-4a09-884c-d1af284077ef/hot` | `438d1daa91b6c06ab42e39682ac521fadc76c97276c0f5e9db6a069a02918db8` | `d11713d88cb2ada2d4bfb55f852238f0e2d5027cc1062dfc8283e583bc557420` |
| `913bd4ec-fc08-4a09-884c-d1af284077ef/strict` | `94cb64be8f0d4218d46040b94da4d53950e562be95f4f9c70c2f805c3e571509` | `27a1d9917da232003efe0a45e06750f635e2e9fc3bd1b698751f7870d34bc298` |
| `fc510ba4-6988-4e64-aa0b-795baa6b520f/hot` | `9102f6a6813fdde7b9f47e72b9105c3daeaded53f05562f4716e4c64b1295580` | `861e77a7573d245392f069a1b13a7bb231226ecd9d41b962802e3e5008cb4869` |
| `fc510ba4-6988-4e64-aa0b-795baa6b520f/strict` | `43c38499dd108d6430ccf3d67a937f1e4a85fe8b9b58cb5972e9cb11eb377b11` | `4ceb32c344660a49eb2a40e2a0636429ba312cea4cd8f3139d39d9e9d8891f8c` |

## Independent protection receipts and corrected-run logs

Retained XML was parsed directly, and final stdout/stderr logs inspected. Each
VM's corrected diagnostic has **1 test, 0 failures/errors/skips**. Independently,
each VM's `MmapProtectionTest` has **17 tests, 0 failures/errors/skips**, and
`BpfHardeningTest` has **15 tests, 0 failures/errors/skips**. These unchanged
native suites retain genuine executable mmap/mprotect/pkey_mprotect EPERM
assertions and checked mapping cleanup. Completion outcomes are not denial proof;
modeled controls are not relabeled as native receipts.

Task 2's corrected commands (all exit 0), with JAVA_HOME selected separately for
each VM above, were:

```bash
# OpenJDK corrected diagnostic and synchronization/format check
./gradlew :enforcer:test --tests '*TestSynchronizationPolicyTest' :enforcer:ktlintIntegrationTestSourceSetCheck :enforcer:integrationTest --tests '*NativeCompletionProbeTest' --rerun
# GraalVM corrected diagnostic
./gradlew :enforcer:integrationTest --tests '*NativeCompletionProbeTest' --rerun
# Independent native protection, once per VM
./gradlew :enforcer:integrationTest --tests '*MmapProtectionTest' --rerun :enforcer:integrationTestFreshJvm --tests '*BpfHardeningTest' --rerun
```

Paths in this manifest are relative to A; XML filenames are included verbatim.

| Retained file | SHA256 |
| --- | --- |
| `openjdk-final-green.stdout.log` | `ebdbef9bc8270352068fb8c18940b8449e08cb39dd80ec1c955613db6aa8359f` |
| `openjdk-final-green.stderr.log` | `a7c2ff2a1e17615dc4fd3f920039377ad105c65e05508d5feab1923fffdd2753` |
| `graalvm-final-green.stdout.log` | `4a97d79358efbd8da8c11ecf941fb85be717c7c1ace0128bc197305248724262` |
| `openjdk-final-protection.stdout.log` | `52d55961b00cfb0b3d69a327590a06e5dffa6f73571685c1e7c8217b4f354c64` |
| `graalvm-final-protection.stdout.log` | `4aaebddf584a6c86e1d6ce0264aa307f31f6dfb8ce6943508de71644ee40c0ee` |
| `openjdk-final-diagnostic-results/TEST-io.mazewall.enforcer.diagnostics.NativeCompletionProbeTest.xml` | `3255a7cd9ea29f3e6828be19700e4a1922ee6ff556c3a536bbb99d620ef8ff48` |
| `graalvm-final-diagnostic-results/TEST-io.mazewall.enforcer.diagnostics.NativeCompletionProbeTest.xml` | `eab614fd05aa523899dfc80d3f1c1ccffa335e1804de810630c08703fe3ff32b` |
| `openjdk-final-protection-integrationTest/TEST-io.mazewall.seccomp.MmapProtectionTest.xml` | `b826d62a16749fee2e23e1c9ca33491f97557efbad4d8adfe2e07b2988aab31c` |
| `graalvm-final-protection-integrationTest/TEST-io.mazewall.seccomp.MmapProtectionTest.xml` | `01af4819a0b88d1eabad10c5b120b78e1ffefff93c1ddb4396d02ee0f763a0c1` |
| `openjdk-final-protection-integrationTestFreshJvm/TEST-io.mazewall.seccomp.BpfHardeningTest.xml` | `61aa4d8288837f121ff835bdc02468c950970c22b1a057b452492d1f464f913f` |
| `graalvm-final-protection-integrationTestFreshJvm/TEST-io.mazewall.seccomp.BpfHardeningTest.xml` | `5b29c8907a33fca990e61948806da7e16697ae927d56f195ce9dc5aa21fc95d9` |
| `synchronization-policy-red.xml` | `387002c3abc50e1809ab53911af3cc8c4e36bc17bc1bb05dfb168c50d8a732ee` |
| `synchronization-policy-green.xml` | `4459f8044775e9545d9887b49cfa9b78465f702977b35c20af8210e2669ff70f` |
| `task-2-commit-hook-attempt-1-failed.log` | `ca4f2b92ff606e13d51bb625b0126cfe5d32307e324421d6479c84985f76adfe` |
| `task-2-commit-hook.log` | `18736a1d15ce22a7ce4a56477edc88f1bd39c3065b2fc59063595f0d8b4b6371` |

`graalvm-final-green.stderr.log`, `openjdk-final-protection.stderr.log` and
`graalvm-final-protection.stderr.log` are empty, each SHA256
`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
The Task 2 successful full-hook log records `BUILD SUCCESSFUL in 6m 4s`,
242 actionable tasks (34 executed, 208 up-to-date). Existing unrelated
CET/non-Linux/Landlock-provider/delegated-cgroup skips are not capability proof.

## Generated decision and closure boundary

The Task 3 brief's exact decision generator ran against R, not a fixture or an
empty source-checkout directory. It read all ten actual journals/outcomes/logs
and enumerated actual fatal-report paths. The resulting `R/decision.json` has
SHA256 `07e0ee8ebe2905b820cf3ae7587cb8981bde997a59fae2c0d4392897d7522824`
and ten report entries, all with empty `fatal_reports` arrays. Its decision fields
are verbatim:

```json
{
  "original_callable_identified": false,
  "original_openjdk_path": "applyBpfFilter385_fixed_callback",
  "strict_arbitrary_jvm_safety_proved": false,
  "protection_assertions_changed": false
}
```

This decision describes the inspected pre-Task-3-hook artifact set. A subsequent
build may add reports; it does not retroactively change this snapshot. No missing
journal, failed owner or absent fatal log is treated as successful attribution.
Strict NATIVE_IMAGE-on-HotSpot may complete or fatally reject late code generation;
these successful samples do not guarantee either outcome for another workload.

Closure still requires actual matching evidence identifying the original callable
and restricting installation, or an independently justified disposition of that
remaining unknown. The new controls alone satisfy neither condition. The parent
is neither downgraded nor archived. Production remains pinned to
Temurin25.0.4.1+1-LTS, `-Xint -XX:+UseSerialGC`, synchronous
`openat_handler_entry` until a separately approved capability gate passes.
