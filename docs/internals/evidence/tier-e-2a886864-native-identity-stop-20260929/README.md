# Tier E exact-source native acceptance: phase 1 passed, plan stopped at binary identity — 2026-09-29

**Overall `INCOMPLETE`.** A separately predeclared qualification attempt
used a clean clone of `fast-master`
`2a886864a2a0d09ffdc564609fde30e45a480fa7` and runtime-source digest
`a4219212fd5185cb73a42f6eaeaa47f2e00113f660ca9c21e3ffeb95d6e1e7df`.
The plan fixed three independent six-sample native phase runs before the
original five-pair, 20-fork exact-operation gate and one pinned-ID rootful
aggregate. It also fixed the Debug native agent SHA-256 at
`9dafc1495eb42e7c554637b748087fcb7cc42e286d69fa8165776565aa923e76`,
the diagnostic-built binary measured on the preceding source. The plan,
five-row conditional schedule and source fingerprint were hashed before
phase 1. Kernel `7.0.0-34-generic`, config SHA-256
`e75c0adab6020e72844d7431cf801a02b94ed32efc251c3b0fb80c021d065180`,
image ID
`sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56`,
CPU set `0,2`, workload-class hash and the unmodified historical
native baseline were independently checked.

**Native phase runner 1 genuinely `COMPLETE` on its own compiled binary.**
Three 100-iteration full-stream and three unique-mode CTests executed
successfully. Both version-two comparable verdicts were `PASS`:
full `GetStackTrace` median **1,626,989 ns**, unique median
**1,574,099 ns** versus the unchanged unique limit **2,307,381 ns**;
the original raw-key and combined key/lookup/logical bounds passed as
well. All six logs reported 514 captures, zero failures and 504/10
canonical cache hits/misses. Each host log and the phase/native identity
matched its owned Docker export byte for byte; the phase runner and
volume retired after its successful export.

**Predeclared binary identity nevertheless failed.** The Gradle native
build produced a Debug library with full SHA-256
`15914500bf5ecffa8c248aebdebee796dcc1ebc4349f5b54d57544fd7ddf36bd`,
not the plan's `9dafc149...`. The package manifest confirms `Debug`,
`usdt=false`, exact image/compiler/JDK, native-source digest and unchanged
workload class. Offline inspection of the separately retained diagnostic
library and this phase package found **identical 204,058-byte `.text`
sections** (SHA-256
`b503e884ab32ec3e23f6618b27a53450d913ffbdfe766077b8532fb8df9519f8`),
but Debug strings record diagnostic source/build paths under
`/source/profiler-native` and `/workspace/profiler-native/build` versus
Gradle build paths under `/workspace/profiler-native` and
`/workspace/native-build`. This explains a full-ELF provenance mismatch
without proving byte identity of every non-code section or allowing
the plan's explicit full-SHA requirement to be waived.

Per the first-mismatch stop rule, **native phase runs 2–3 were not run**;
the fresh same-source five-pair correctness gate and pinned-image
rootful aggregate were **not run**. The phase's own `COMPLETE` terminal
is preserved unaltered; it does not upgrade the enclosing source-bound
plan. A differently built diagnostic agent, even with identical `.text`,
cannot be substituted for the fixed full-binary identity after acquisition.
The earlier `8a0d6d32` failed phase and `3775ab65` diagnostic stop remain
independent `INCOMPLETE` receipts. This run does not establish generic
kernel support or change the unchanged acceptance threshold,
`policy_eligibility=false`, `REJECT_DEFAULT_PROMOTION`, or deferred
Podman parity.

`preflight-raw.tar.gz` contains the original plan, schedule, clean
fingerprint, baseline and source-selected profiles. `phase-raw.tar.gz`
contains all six raw CTests, their verdicts and export, the Gradle
build log, the decision receipt and both Debug binaries with `.text`
comparisons and native package manifest. Originals remain under
`/home/leanid/Documents/tier-e-generic-2a886864-qualification-20260929/`;
its 37-file `SHA256SUMS` passed. The separate old stopped phase and
diagnostic runners/volumes were not touched. Another acceptance attempt
would require a **new**, separately fixed source/binary plan rather than
resuming or reinterpreting this terminal.
