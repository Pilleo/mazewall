# Post-install callback bootstrap correction

## Scope and cause

Continuation starts from integrated fast-master8e677ced in the existing authorized
worktree on `fix/containment-post-install-bootstrap`. The retained fatal report
`enforcer/hs_err_pid2602181.log` identifies ContainedExecutors.applyBpfFilter
bytecode offset385: a cold invokedynamic bootstrap resolves a native wrapper,
expands the executable CodeHeap by65536bytes and receives EPERM after seccomp.
This is executable-memory denial, not evidence of exhausted physical RAM.

The unsupervised path returned `AutoCloseable {}` after native installation and
self-verification. Its caller only recognizes a real SupervisorSession; the dummy
callback owns nothing and is ignored. Returning absence instead removes unnecessary
post-install linkage without adding a prewarm cache, changing a policy or inventing
a resource token. Only the private return becomes nullable. Supervised acquisition,
receipt ownership, state publication and error handling remain unchanged.

The containing executable-mapping issue also records different FutureTask,
VarHandle and error-adapter paths. Fixing this exact library callback does not prove
arbitrary HotSpot execution is safe under strict NATIVE_IMAGE PROT_EXEC denial.
The broader issue remains open; the precise callback defect has its own issue.

## Regression evidence

- The isolated original native design class currently passes on an actual fresh
  GraalVM worker (`run-dd30bed9499c`); the older fatal report remains a valid
  timing/code-cache-pressure observation. A previous FROM-CACHE result is not used
  as fresh native evidence.
- New InstallationBootstrapTest uses the existing owned fresh-child fixture and
  mock native engine to mark SECCOMP_SET_MODE_FILTER commit. Actual JDK JFR
  ClassLoad events observe cold ContainedExecutors callback linkage afterward.
  The child is isolated so earlier tests cannot pre-link the call site; the test
  observes runtime behavior, not source text or a bytecode spelling.
- Red `run-e4e0403443c7` records an actual
  `io.mazewall.enforcer.api.ContainedExecutors$$Lambda...` after that boundary.
  The earlier ClassDefine experiment did not detect this hidden lambda and was
  replaced by ClassLoad before accepting the regression.
- Green `run-f8905e1187df` passes the cold regression, actual native design control,
  receipt/failure matrices and Kotlin/legacy ABI checks. Final narrowed operation
  marking, supervised receipt coverage, ktlint and main/test Detekt also pass
  (`run-6f6a409c05ba`).
- Actual BpfHardeningTest and MmapProtectionTest execute uncached on GraalVM
  (`run-048e40f4f4cd`). Strict executable mmap remains denied with EPERM, ordinary
  non-executable mappings remain usable, protection/cleanup assertions remain.
  No syscall floor, argument inspection, runtime admission, fallback or test
  assertion was relaxed.

JFR/native callbacks are complementary evidence: the new host regression models
the native boundary safely; existing integration probes establish actual kernel
denial. This does not claim deterministic reproduction of every historical VM crash.

## Review and remaining gates

The private applyBpfFilter has one actual caller. Its existing null/no-session
branch is already used when no new filter is required. An unsupervised null result
selects the same receipt or existing supervised-registration acquisition as the
old non-SupervisorSession dummy. The actual supervisor-session path is unchanged.
Codanna impact output is partial; direct function/caller/receipt inspection supplies
scope evidence. Public ABI and native layouts are unchanged.

The full native-enabled `./gradlew build` passes on actual installed
GraalVM25.0.4.1.1 in8m43s (`run-cd6d19f0910a`), including formatting, static analysis,
configured coverage and all included modules. Final XML independently confirms
the cold-boundary test1/0skips/failures/errors, actual BpfHardening15/0/0/0,
MmapProtection17/0/0/0 and public HotSpot7/0/0/0 (`run-0a48ae736c74`). Kotlin and
legacy ABI checks pass. This is an actual passing GraalVM revision, not an OpenJDK
substitution, cached-only focused execution or a weakened protection condition.

The precise callback issue is archived on this evidence. The broader executable-
mapping investigation stays open for its distinct historical paths and documented
strict-profile/runtime boundary. Canonical counts are137active (136open/1deferred)
and705resolved. The scoped revision is ready for the operator-requested local
integration; no remote publication is implied.

Post-archive ADK and Gradle canonical-index validation pass
(`run-d6d9ad2ee69a`, `run-dee2110644f3`);233local documentation references and
recursive issue totals pass (`run-96b2e2b5cbec`). Two inherited references in the
touched design now point to their already-resolved issue locations. Whitespace
checks pass; no executable source changed after the full quality gate.
