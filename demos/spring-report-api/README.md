# A report HTTP API with reviewed worker permissions

## Start here: a useful report and its actual request effects

```bash
: "${VERSION:?Select the approved artifact version}"
: "${REPOSITORY:?Select its Maven repository URL or absolute file path}"
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" presentationTest
```

This tagged JUnit tour uses the finished application, real loopback HTTP/authentication,
the actual retained proxy and same-datasource H2 effects. It prints the returned
operational report, actual request IDs/status/transaction headers, refusal/recovery,
and the observed audit/end summary. The harness supplies explicit known-synthetic
fixture review, not production approval; normal `serve` still requires separate review.
Every printed outcome comes from a verified response/log, not expected literals.

The guide reuses the Koog demo's useful-result-first and controlled-boundary-change
patterns, not its code or model runtime. There is no Koog dependency, template
language or new response DTO. To adapt it, change a template in a new private
deployment and remeasure/review. Stale deployment review must fail before traffic.

Independent Java25 / Spring Boot4.1.1 application. No Koog dependency. A retained
interface proxy sends the business method, real JDBC transaction and upstream
pre/post authorization to one owned platform worker. The request returns the
actual template text and commits a request-specific H2 audit row. **No filesystem
report is written.** A denied read and post-authorization failure both roll back.

## Build and approve a disposable evaluation deployment

Use Linux with Seccomp/Landlock support and JDK25. Select the approved Maven
version/repository; configure named `mazewall` Gradle credentials externally when
required. The illustrative SNAPSHOT default is not immutable released-byte proof.
This sample requires the additive typed inactive-domain API from the current SDK;
an older SDK used by the earlier minimal example cannot compile this new sample.

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

### Adoption seam and local distributions

Business code remains an ordinary interface/service with explicit transaction and
method-security metadata. Bootstrap changes the published bean, not its business body:

```diff
- Reports published = initializedTarget;
+ Reports published = binder.createJdbcProxy(Reports.class, initializedTarget,
+     SpringSecurityMethodAdvice.authorizationAdvisor());
+ beans.registerSingleton("reportService", published);
+ binder.bind(explicitApprovalForThatExactRegisteredProxy);
```

The actual sample also resolves reviewed resources, installs the compatible process
baseline, starts the domain and validates required bindings **before** context refresh.
Keep normal Boot infrastructure; do not globally disable its processors or expose
the raw target as a second protected entry. Binding is deliberately more explicit
than an annotation or dependency alone.

```bash
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" evaluationZip sourceZip
```

`build/distributions/spring-report-api.zip` contains the finished `application.jar`,
guide and synthetic templates. Extract only into a new private directory, then do
the separate measurement/review/serve steps above. `spring-report-api-sources.zip`
contains this independent wrapper, source/tests, locks/checksums and fixture inputs.
Neither archive publishes itself, replaces an installation, provides production
review or establishes remotely downloaded release identities.

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
Before refresh, the application checks the effective Spring configuration, not just
its defaults: the address must be exactly `127.0.0.1` and the configured port must
match the CLI argument. Conflicting system/environment/config-file settings fail
startup rather than silently changing the listener. A separate management port or
nonmatching management address is rejected too; unrelated Boot settings remain active.
Leave the management port unset for an ephemeral CLI port: explicit management
port `0` creates a second listener in Boot and is rejected, not treated as shared.
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
client, and it does not expose a public cancellation or shutdown endpoint. A
separate test-only finished-JAR held-cleanup service exercises virtual-caller
interruption, retained incomplete drain/resources, eventual actual SQL rollback and
physical retirement using the same request/Security bridge and qualified advice.
It is absent from `application.jar`; it is not a hidden fault mode in `ReportService`.
That test does not claim that an HTTP disconnect automatically cancels work. SIGKILL
is not graceful-retirement evidence.

```bash
./gradlew -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" nativeTest
```

The finished-JAR tests require actual HTTP authentication, native denial, real H2
effects, alternating principals, readiness refusal and physical shutdown. They do
not skip unsupported native setup. Host tests only qualify ordinary business code
and context/outcome handling. One worker/four admissions and ten-second preparation/
close budgets are demonstration bounds, not measured production capacity. The
synchronous HTTP adapter has no automatic per-request execution deadline; a client
timeout is not proof that its worker stopped or its transaction rolled back.

Thread-scoped enforcement is for trusted synchronous Java operating on untrusted
data, not arbitrary-code/shared-heap isolation. Construction, controller parsing,
unrelated executors, preopened descriptors and model/provider disclosure are not
protected by this method proxy. Process-wide restrictions remain permanent.
