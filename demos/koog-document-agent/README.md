# A document agent with a smaller OS permission surface

This is an independent Kotlin 2.4.20 / Koog 1.3.0 / JDK25 application. It has no
Spring dependency, included mazewall source build or injected producer plugin
classpath. Its model is explicitly **scripted and offline**; the upstream agent,
tool registry, codecs, owned workers and native restrictions are real.

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

**Try a change:** in a private copy, change the first normal request's `note.md`
to `../private.txt` without changing the policy. The actual open must fail; do not
change the grant to manufacture success. Keep the later explicit denial/recovery
controls. The fixture contains only synthetic data in an acquired private directory.

## Verify

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

```bash
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" runPortal
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" demoPortal
```

`runPortal` is the ordinary application: an actual offline AIAgent calls the
published-plugin-generated `DocumentOutlineService` in a distinct protected JVM.
It extracts ATX headings as plain text without rendering or following links.
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
