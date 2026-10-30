# A document agent with a smaller OS permission surface

## Start here: explain a failed deployment

The incident application asks a real Koog agent to inspect a supplied synthetic
support bundle and return a hypothesis, evidence locations and next steps. It prints
the validated result—not a canned diagnosis or `script-complete`. Two bundles under
`fixtures/incidents/` cover reduced pool capacity and an incorrect database endpoint.

Choose a provider and model explicitly. A hosted model avoids local inference load:

```bash
: "${VERSION:?Select the approved artifact version}"
: "${REPOSITORY:?Select its Maven repository}"
: "${MODEL:?Select an available tool-capable model}"
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" \
  -PincidentBundle=fixtures/incidents/pool-exhaustion \
  -PincidentProvider=openai -PincidentModel="$MODEL" triageIncident
```

Configure `OPENAI_API_KEY` through existing operator secret management. Live hosted
selection discloses the supplied document contents to that provider; use only
synthetic evaluation data unless you have the appropriate approval. No API key is
printed or stored in this project. `ollama` is an alternative only when the operator
already prepared a tool-capable model on 127.0.0.1:11434. Neither command starts a
server, pulls a model, selects a fallback or silently becomes a fixture run. A slow
provider fails at the existing 10-second agent deadline; Ollama is not required.
Both use Koog's locked, cancellation-aware Ktor HTTP adapter. Packaged-classpath
tests construct/close the real clients and hold an acquired loopback HTTP server
until the unchanged ten-second agent deadline cancels the actual request. These
checks never contact an inference server and do not qualify model quality or timing.
CLI failures exit nonzero with a safe category, never raw provider/parser diagnostics
that may contain untrusted terminal controls. The internal runner still propagates
the original exception; this is terminal presentation, not success or fallback.

The installed launcher accepts `triage <bundle-directory> openai|ollama <model-id>`.
The three supported document IDs are `application.log`, `deployment.md` and
`pool-settings.txt`: each at most 8192 strict UTF-8 bytes/256 lines (24576 bytes total).
Successful reads are snapshotted, with at most 8 attempts/24 graph iterations.
Final raw JSON is at most 16384 bytes, with 1–8 evidence references and 1–5 next steps. Only successfully
read documents/existing lines are valid citations. This does not verify that the
model's hypothesis is correct. The provider, prompts and report parsing are host
work, not inside the reader's OS boundary.

For deterministic engineering/rehearsal checks, run the explicitly labelled fixture
executor through the same actual graph, tools and native boundary:

```bash
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" \
  nativeTest --tests '*IncidentAgentTest' --tests '*IncidentBoundaryTest'
```

The boundary probe creates its own synthetic sibling canary: ordinary bounded-reader
control opens it, the identical reader action on an owned worker receives actual
permission refusal, and legitimate incident work still completes under the same
fixed policy. It withholds canary contents and proves physical retirement. The
ordinary control is test-only, not an unprotected mode for arbitrary user data.
Fixture answers prove wiring/evidence handling, not actual model reasoning or
injection resistance. The legacy small qualification commands below remain available.

This is an independent Kotlin 2.4.20 / Koog 1.3.0 / JDK25 application. It has no
Spring dependency, included mazewall source build or injected producer plugin
classpath. The legacy `demoThread`/`runPortal` model is explicitly **scripted and
offline**; `triage` uses only the explicitly selected live provider. In both cases
the upstream agent, tool registry, codecs, owned workers and native restrictions are real.

**Try a useful change:** run the same live command with
`-PincidentBundle=fixtures/incidents/wrong-endpoint`. Inspect the actual hypothesis
and its cited source lines; do not assume the model must agree with a canned answer.
The named fixture tests exercise both bundles without inference and label their
answers accordingly. Adding a new document ID requires updating the bounded
contract and tests, not letting model text grant a new filesystem permission.

## Run the thread example

Use Linux with the required Seccomp/Landlock support and JDK25. Select the exact
approved artifact version/repository supplied with your evaluation kit:

```bash
: "${VERSION:?Select the approved artifact version}"
: "${REPOSITORY:?Select its Maven repository URL or absolute file path}"
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" demoThread
```

For GitHub Packages, configure Gradle's normal `PasswordCredentials` properties
for repository name `mazewall` through existing operator secret management, not
project source. The illustrative SNAPSHOT defaults do not identify immutable
released bytes. Use reviewed locks/checksums for the chosen version; never bypass
verification or silently substitute a sibling source build. Remote download
qualification remains a release gate when package access is unavailable.

Expected observations:

```text
MODEL: scripted (offline); no model account or prompt-injection-resistance claim
ALLOWED: approved document read through the real AIAgent and owned tool
DENIED: actual sibling open refused; evidence=INFERRED_PERMISSION_FAILURE
RECOVERED: approved document read succeeded under the same fixed policy
RETIRED: actual owned platform worker stopped; process baseline stays permanent
```

Read `DocumentReadTool.kt` for the bounded business action and `DocumentAgent.kt`
for explicit bootstrap, fixed grants and ownership. The application binds the
document directory separately from the model's path argument. Arguments are
nonblank/NUL-free and at most4096 UTF-8 bytes; reads consume at most65537 bytes,
reject more than65536 and decode strict UTF-8. Runtime/classpath reads remain
available. One worker/four admissions and ten-second caller/runtime budgets are
demonstration settings, not measured capacity. The model sees a failed-tool
observation and then requests permitted work; the example does not claim that a
model detected or resisted an injection. No permissions are widened or work replayed.

`SafeToolFailures` replaces failed tool observations **before the prompt executor
boundary**, using a category and fresh local receipt ID correlated to the exact
tool-call ID. The original Throwable remains available to local agent failure
handlers; it is not replaced by a success value or discarded. Unknown correlation
refuses the provider request. Host tests inject a secret-bearing exception and
require both a safe model observation and unchanged original failure identity.
Known completed worker argument-validation failures become `INVALID_INPUT`, including
the document heading/output limits; unknown remote errors remain `INTERNAL_FAILURE`
and uncertain completion remains `OUTCOME_UNKNOWN`. No exception-message parsing or
remote diagnostic text is used in that classification.
This does not redact successful document contents, model arguments, previous
user messages or unrelated callbacks. Choosing whether any such data may be sent
to a real provider remains an application/operator decision; this offline model
does not qualify disclosure safety for a remote provider.

**Try a change:** in a private copy, change the first normal request's `note.md`
to `../private.txt` without changing the policy. The actual open must fail; do not
change the grant to manufacture success. Keep the later explicit denial/recovery
controls. The fixture contains only synthetic data in an acquired private directory.

## Verify

### Adoption seam

Keep the ordinary reader; change which tool you register, after trusted bootstrap:

```diff
- register(ordinaryReadTool)
+ register(documentReadTool(runtime, preparedDomain, trustedDocumentRoot))
```

This is an application-local factory, not automatic protection. Its source retains
argument/result validation and delegates only the bounded read to the owned worker.
The runtime/domain, permanent process baseline and fixed grants are still explicit.

### Local distributions (not remote publication)

```bash
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" distZip sourceZip
```

`build/distributions/koog-document-agent.zip` contains the ordinary application and
its actual runtime JAR closure, this guide and the two synthetic incident bundles.
Extract into a new private directory; from its `koog-document-agent` directory run
`bin/koog-document-agent` for the legacy offline thread path. For live analysis run
`bin/koog-document-agent triage fixtures/incidents/pool-exhaustion openai "$MODEL"`.
Live mode still requires operator credentials or a prepared local server/model.
In a separate fresh JVM, run
`bin/koog-document-agent outline fixtures/incidents/pool-exhaustion/deployment.md`
for an actual-file outline. No Gradle or model account is needed for outline or the
legacy offline path; live analysis is not silently substituted for either.
`koog-document-agent-sources.zip` contains the independent wrapper, source, tests,
locks, checksum approvals and the same six incident inputs, not a sibling producer tree. Test-only boundary and
measurement/profile entries are source-kit development controls, not normal binary
runtime tools. An operator must separately approve any upload/publication or
installation replacement and identify exact released bytes before distribution.

```bash
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" test
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" nativeTest verifyPublishedClosure
```

Host tests verify the ordinary reader and scripted-provider validation, not native
containment. `nativeTest` launches the actual packaged application in a fresh JVM,
requires each observation once and fails on unsupported native setup or incomplete
retirement. `verifyPublishedClosure` rejects project substitution and prints actual
resolved mazewall artifact hashes. No assumptions, unprotected fallback or live
provider account is used.

Closing the runtime does not remove permanent process restrictions. Thread-backed
containment is for trusted synchronous code processing untrusted data: shared heap,
unrelated executors, already-open descriptors and arbitrary Java execution are not
isolated. Run process-backed examples in a separate launch-compatible JVM, never
by weakening this example's no-exec baseline.

## Generated process outline

To process an actual Markdown file and print the worker's actual result:

```bash
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" \
  -PoutlineDocument=/absolute/path/to/document.md outlineFile
# Installed launcher, in a separate fresh JVM from triage/thread mode:
bin/koog-document-agent outline /absolute/path/to/document.md
```

Input selection and bounded file acquisition happen in the trusted host; only
parsing is process-backed. The guest class stays absent from the host classpath,
the actual child handle must be dead after close, and only then is the returned
outline displayed. Confirmed retirement also permits cache cleanup when parsing
fails; the original parser error remains the command failure. Terminal controls in headings are rejected before display. If
retirement cannot be established, the command fails and retains its private cache
instead of reporting success or removing live resources.

```bash
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" runPortal
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" demoPortal
```

`runPortal` is the ordinary application: an actual offline AIAgent calls the
published-plugin-generated `DocumentOutlineService` in a distinct protected JVM.
It extracts supported ATX heading lines outside fenced code as plain text, without
full CommonMark parsing, rendering or following links.
Input is at most65536 strict UTF-8 bytes, output at most16384 bytes and128 headings;
empty headings become `(untitled)`. The normal registry has only `outline_document`.
Only its generated service API is shared; `MarkdownOutline` stays inside the
opaque verified worker ZIP and cannot be loaded by the normal host classloader.

`demoPortal` adds a clearly labelled **test-only** fixture from the nested
`boundary-fixture` project. It proves a real denied sibling open, legitimate
outline recovery, UNKNOWN_COMPLETION followed by terminal STATE_LOST after a
controlled child crash, and an independent service/host that stays usable. This is
not an exploit in the ordinary outline parser. Fixture APIs, fault implementations
and the presentation entrypoint are not normal application runtime dependencies
or advertised production tools. Both worker bundles are generated through the real
published plugin marker, not manually edited output or injected classpaths.

The process app explicitly installs a launch-compatible host baseline before
worker setup. Its static required worker baseline is separate from resource
profiles. It retains actual acquired child handles and requires them dead after
canonical close. Run it in its own JVM; the thread app's permanent no-exec baseline
cannot be reused for child launch. First-party process separation is not a claim
of a generic hostile-code/MCP sandbox or automatic inherited-secret isolation.

## Profile, compare, review, enforce

Development-only tasks use the existing published profiler and the **same actual
bounded reader member** used by the tool. They are absent from the application
distribution and must run in fresh JVMs before a no-exec baseline blocks helpers.

```bash
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" \
  -PprofileDocument=/absolute/path/to/synthetic-note.md profileRead
```

Retain `PROFILE_DOCUMENT` as measured coverage evidence. `CANDIDATE_DOCUMENT`
appears only after the existing `toPolicy` completeness gate succeeds. Missing
coverage refuses conversion; never edit the coverage marker or auto-approve gaps.
The candidate is a development-only observed-scope filesystem policy plus explicit
JVM/classpath support, not a claim of an exact universal syscall allow-list. The
named trace does not cover future paths, arbitrary tools or unrelated threads.

Save only the candidate JSON in a **new private operator-owned** file. Compare it
with the prior operation document using the repository's development `:profiler:policyDiff`
task (`-PoldOperationDocument=... -PnewOperationDocument=...`). Review added reads,
writes, syscall/action/argument changes, actual artifact/resource identity and gaps.
Neither comparison, measurement nor hashing supplies approval authority.

A separate reviewer must inspect actual bytes and supply `trustedReview` properties
`reviewer`, `documentSha256`, `jarSha256` and `resourceGeneration`. Then:

```bash
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" \
  -PprofileDocument=/absolute/path/to/synthetic-note.md \
  -PoperationDocument=/absolute/path/to/reviewed-candidate.json \
  -PtrustedReview=/absolute/path/to/trusted-review.properties enforceReviewedRead
```

The independent launch validates external review, exact actual reader JVM member,
finished application bytes and pinned resources, prepares the fixed reviewed policy,
performs the real read and requires actual worker retirement. It never writes or
updates review properties. Changing application/input/classpath bytes requires new
measurement and review, not a fallback. The main agent remains a simple static-policy
demonstration; this development flow does not silently replace its policy.

### Maintain the policy when the application changes

1. Keep the existing approved operation document and source/JAR identity. Copy a
   synthetic incident input into a **new** private development bundle; never overwrite
   an operator deployment or modify its grants in place.
2. Run `profileRead` above with `profileDocument` pointing to that bundle's actual
   `application.log`. This measures the shared bounded reader only—not the model,
   all three incident documents, Spring, or arbitrary future tool actions. Exercise
   each newly required operation separately; incomplete coverage remains a refusal.
3. Retain the profile/coverage evidence and save its new candidate. From the producer
   checkout compare old/new documents with `:profiler:policyDiff`. A content change
   can change resource identity without adding a permission; a new root can add a
   read grant. Inspect both, rather than accepting every diff as harmless.
4. Obtain independent review of the candidate, exact application bytes and resources.
   Run `enforceReviewedRead` in a fresh JVM with those reviewed inputs, and require
   matching real content and physical retirement. An old review against changed
   resources must fail, not trigger remeasurement or self-approval at runtime.
5. Keep the incident agent's explicit static bundle policy separate. This recipe
   does not auto-install a profiler candidate into the agent. Any changed normal
   policy/bootstrap is a source change with its own review and native regression.

For the independent Spring application, a template/deployment change similarly
requires a new candidate and separate deployment review before `serve`; its
candidate is not a syscall profile. Neither sample broadens permissions, rewrites
review files or automatically replays failed work to make a demo pass.
