# JUnit feature tour

## Choose one independent example

Use JDK 25 on Linux with the required Seccomp/Landlock permissions. Each native
entry point launches a fresh JVM and fails if required support is unavailable.
These examples create owned synthetic fixtures, not arbitrary operator files.

| Example | Start with | What to copy |
|---|---|---|
| Core, no framework | `./gradlew :demos:cli-demo:runWorkspaceExample --console=plain` | [WorkspaceExample](cli-demo/src/main/kotlin/demo/WorkspaceExample.kt): explicit baseline, fixed workspace grant and synchronous owned call |
| Koog, no Spring | [Thread and Portal commands](koog-contained-tool/README.md) | A bounded `read_note` Tool on an owned worker, or the separately launched generated process service |
| Spring, no agent | [Independent Spring quickstart](spring-boot-sandbox/README.md#start-here-ordinary-report-generation) | Ordinary report code, retained transaction advice and explicitly approved finished-JAR binding |

The core command demonstrates an approved read, a real denied sibling open, an
unaffected host and physical cleanup. The Koog commands use the existing two-stage
producer bootstrap; the Spring quickstart explicitly stages local publications
and uses external **known-fixture** review. Neither is a hidden production approval
service. Read their prerequisites before copying a command.

Each owning README includes expected observations and one guided modification.
Keep the policy fixed when trying a forbidden path; do not widen it to make the
exercise pass. Thread containment does not isolate shared heap, helper executors
or already-open descriptors. Koog's tools are not a complete agent loop; the
Spring example remains non-web and does not advertise Security linkage.

## Inspect the complete feature tour

Open the tests in your IDE, read the input and assertions, and run one example at a
time. Each case creates its own fixtures and runs permanent native restrictions in
an owned fresh JVM; teaching order does not create test dependencies.

| Stage | Test source | Question answered |
|---|---|---|
| 01 | [EnforcementPresentationTest](cli-demo/src/test/kotlin/demo/EnforcementPresentationTest.kt) | What can unrestricted input do, and how do syscall/path contracts narrow it? |
| 02 | [ProfilePresentationTest](cli-demo/src/test/kotlin/demo/ProfilePresentationTest.kt) | How do I discover a candidate, and when must I refuse to enforce it? |
| 03 | [OwnedRuntimePresentationTest](cli-demo/src/test/kotlin/demo/OwnedRuntimePresentationTest.kt) | How do virtual callers, context, admission, cancellation and physical cleanup fit? |
| 04 | [AgentPresentationTest](agent-sandbox-demo/src/test/kotlin/io/mazewall/demo/agent/AgentPresentationTest.kt) | How can an approved analysis tool use a capability while another tool is denied? |
| 05 | [ThreadReadPresentationTest](koog-contained-tool/src/test/kotlin/io/mazewall/demo/koog/ThreadReadPresentationTest.kt) / [PortalPresentationTest](koog-contained-tool/src/test/kotlin/io/mazewall/demo/koog/PortalPresentationTest.kt) | How does a Koog tool use an owned worker or cross into a distinct protected child? |
| 06 | [BootPresentationTest](spring-boot-sandbox/src/test/java/demo/boot/BootPresentationTest.java) | How does a finished Boot singleton retain review, real JDBC and lifecycle semantics? |
| 07 | [SpringSecurityPresentationTest](../tests/consumers/spring-jdbc/src/test/java/consumer/spring/SpringSecurityPresentationTest.java) | How do principal restoration and post-authorization SQL rollback work? |
| 08 | [WebPresentationTest](vulnerable-web-app/src/test/kotlin/demo/vulnapp/WebPresentationTest.kt) | What does the existing real web service comparison actually prove? |

Use JDK25 on Linux with supported Seccomp/Landlock. Native prerequisites fail these
showcase tests rather than silently skipping. Prepare dependencies/artifacts before
offline rehearsal. The test output names observed vs inferred evidence; a green
test proves its stated assertions, not universal arbitrary-code containment.

```bash
./gradlew presentationTest --console=plain
./gradlew :demos:cli-demo:presentationTest --tests '*OwnedRuntimePresentationTest' --console=plain
./gradlew :demos:agent-sandbox-demo:presentationTest --console=plain
```

The root task orders the included CLI → agent → optional Koog modules. Koog uses its
existing explicit bootstrap/packaging workflow; finished Boot and Security retain
independent published-only consumer builds. Web is a separate optional task. See the
[presenter guide](../docs/presentation/PRESENTER_GUIDE.md) for exact commands and limits.
There is no additional shell presentation launcher or model-account requirement.
