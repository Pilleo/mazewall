# Evidence and editorial notes

## Provenance

Local checkout HEAD at inspection: `8f0ba6bc05425e40e08289e2fd8f33214b089017`. The checkout had extensive existing uncommitted changes, including design documentation and implementation files. HEAD alone does not identify all inspected content. This draft does not claim an experiment against that exact commit.

Local source documents inspected:

- `README.md`: APIs, scope, prototype status, policy-generation caveats.
- `docs/REPRODUCING_RESULTS.md`: required environment and experimental records.
- `enforcer/AGENTS.md`: platform-thread restriction, Landlock installation ordering, runtime coordination invariants.
- `docs/internals/designs/core/security-considerations.md`: shared-memory, executor, descriptor, JIT and profiling limitations. Some historical sections conflict with newer guidance; broad guarantees were not copied.
- `demos/vulnerable-web-app/results_report.md`: historical eight-scenario report, not independently reproduced in this task.

## Claims deliberately excluded

No “first JVM syscall sandbox,” universal RCE protection, seven-of-eight prevention rate, negligible overhead, production readiness, complete profile, or safe execution of arbitrary hostile Java code. Such statements are not established by the reviewed evidence.

## Why the demo report is not a publication-quality effectiveness result

| Observation | What it supports | What remains missing |
|---|---|---|
| Protected SSRF returns an operation-not-permitted error | A denial consistent with the intended restriction for the reported request | Same destination/input in both configurations, syscall attribution, benign controls, pinned environment |
| XXE response omits tested file content and returns an error | Tested response differs | Proof that the vulnerable operation was reached, actual effective policy and denial evidence |
| Deserialization and archive scenarios change HTTP status | Application behaviour changes | A marker proving attempted side effect and actual denial |
| Template-injection scenario returns an error in both cases | No demonstrated successful uncontained control | A working vulnerable control and operation-level evidence |
| SQL injection reveals the marker in both cases | A recorded negative control | Reproduction with exact inputs and environment |

An HTTP 500 can arise from unrelated application failure. A missing callback can arise from networking or test setup. A no-exec policy prevents covered process execution; it does not prevent all effects of code execution.

## Minimal next experiment

Use separate fresh JVM processes for irreversible policies. Compare uncontained, baseline-only, and baseline-plus-worker cases using identical inputs. Preserve successful benign controls and expected negative controls. Record policy, source snapshot, Linux kernel, CPU architecture, JDK vendor/version, container runtime and outer seccomp profile, Landlock ABI, and fallback settings. Correlate attempted syscalls with independent filesystem/process/network effects. Repeat and report actual denominators. Performance work needs warm-up, repetitions, workload sizes, and uncertainty; do not infer performance from functional tests.

## Literature checked

Added on 8 September 2026: the official Software Bill of Behavior specification at https://billofbehavior.com/bob/docs/spec/ identifies itself as version 0.0.3, stage Proposed, with its first implementation being tested. The paper cites this published proposal without asserting a final standard release or complete mazewall conformance.

- Oracle Security Manager documentation: https://docs.oracle.com/en/java/javase/24/security/security-manager-is-permanently-disabled.html
- Kernel Seccomp documentation: https://docs.kernel.org/userspace-api/seccomp_filter.html
- Kernel Landlock documentation: https://docs.kernel.org/userspace-api/landlock.html
- Elastic bootstrap checks: https://www.elastic.co/guide/en/elasticsearch/reference/current/bootstrap-checks.html
- Confine (RAID 2020), author/publisher record: https://www.usenix.org/conference/raid2020/presentation/ghavanmnia
- SYSPART, author abstract/version record: https://arxiv.org/abs/2309.05169
- SysComb, 27 August 2026 preprint abstract/version record: https://arxiv.org/abs/2608.26871

The comparison is a focused positioning review, not an exhaustive systematic literature review. The draft describes these systems at the level supported by the consulted records and does not claim implementation-level equivalence or comparative performance.
