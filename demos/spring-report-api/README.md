# A report HTTP API with reviewed worker permissions

Independent Java25 / Spring Boot4.1.1 application. No Koog dependency. A retained
interface proxy sends the business method, real JDBC transaction and upstream
pre/post authorization to one owned platform worker. The request returns the
actual template text and commits a request-specific H2 audit row. **No filesystem
report is written.** A denied read and post-authorization failure both roll back.

## Build and approve a disposable evaluation deployment

Use Linux with Seccomp/Landlock support and JDK25. Select the approved Maven
version/repository; configure named `mazewall` Gradle credentials externally when
required. The illustrative SNAPSHOT default is not immutable released-byte proof.

```bash
: "${VERSION:?Select the approved artifact version}"
: "${REPOSITORY:?Select its Maven repository URL or absolute file path}"
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" test bootJar
```

In a **new operator-owned private directory**, copy `build/libs/application.jar`,
create `templates/approved.txt` and a synthetic `templates/forbidden.txt`. Run:

```bash
java --enable-native-access=ALL-UNNAMED -jar application.jar measure "$PWD"
```

This prints a deployment candidate, not a syscall profile or approval. Save it as
`candidate.json` without overwriting existing files. A separate trusted reviewer
must inspect its fixed policy and finished-JAR/resources and provide
`trusted-review.properties` containing `reviewer`, `documentSha256`, `jarSha256`
and `resourceGeneration`. Values must match the actual reviewed candidate and
deployment. Do not approve a candidate simply because it was generated or hashed.
The `nativeTest` harness writes its own explicitly labelled synthetic test approval;
that is not production-review authority. Normal `serve` never creates approval.

## Serve and observe

Supply evaluation-only passwords of at least12 characters through JVM properties
`report.approverPassword` and `report.readerPassword` via your operator launch
configuration. This sample uses two fixed in-memory accounts and `{noop}` passwords
for local demonstration, **not production authentication or secret management**.
Do not put real credentials in this checkout or command history.

```bash
java --enable-native-access=ALL-UNNAMED -jar application.jar serve "$PWD" 8080
# From another terminal; curl prompts for the evaluation password:
curl -i -u approver http://127.0.0.1:8080/reports/approved
curl -i -u approver http://127.0.0.1:8080/reports/forbidden
curl -i -u reader   http://127.0.0.1:8080/reports/approved
curl -i -u approver http://127.0.0.1:8080/reports/approved
```

The listener is loopback-only; port0 acquires an ephemeral port for tests.
Unauthenticated reports return401 before protected body entry. Approver success
returns200 and COMMITTED; forbidden sibling reads return422 and ROLLED_BACK; reader
post-authorization returns403 and ROLLED_BACK. Headers carry a server-generated
request identity, body-entry observation and that invocation's transaction outcome.
Failures return categories, never exception text, filesystem paths or private bytes.
Original causes remain in local logs. UNKNOWN is not rollback or exactly-once proof.

Read `ReportService.java` for the ordinary business method; `ReportBootstrap.java`
for explicit approval, whole-deployment resources and pre-refresh binding; and
`ReportRequestContext.java` for metadata/security restoration. Receipt capture
samples the newly produced completion **on this dedicated single worker**, not
global `lastCompletion()` later on an HTTP thread. It is not a general multi-worker
audit API. Normal Spring infrastructure stays active on unrelated beans.

`/actuator/health/readiness` includes both Boot readiness and the protected domain.
Type `drain` on the server's own stdin to refuse readiness/admission permanently;
type `stop` to close gracefully. The application retains the runtime through drain
and requires actual worker retirement before closing its database. An incomplete
drain, cancelled delivery or timeout must not be represented as physical termination.
This synchronous HTTP sample does not infer worker cancellation from a disconnected
client, and it does not expose a public cancellation or shutdown endpoint. SIGKILL
is not graceful-retirement evidence.

```bash
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" nativeTest
```

The finished-JAR tests require actual HTTP authentication, native denial, real H2
effects, alternating principals, readiness refusal and physical shutdown. They do
not skip unsupported native setup. Host tests only qualify ordinary business code
and context/outcome handling. One worker/four admissions and the existing10-second
budget are demonstration bounds, not measured production capacity.

Thread-scoped enforcement is for trusted synchronous Java operating on untrusted
data, not arbitrary-code/shared-heap isolation. Construction, controller parsing,
unrelated executors, preopened descriptors and model/provider disclosure are not
protected by this method proxy. Process-wide restrictions remain permanent.
