# Clean executable Boot report sandbox

This **standalone Java 25 / Boot 4.1.1 non-web** application consumes the normal
published `mazewall-spring-boot-starter` library, Spring 7.0.9 and H2 2.4.240.
It is an explicit synchronous singleton integration example and disposable native
acceptance fixture. It does not modify the vulnerable web demo.

`ReportService` reads one externally reviewed template, writes one **existing exact
report file**, and performs real same-datasource H2 JDBC work. `ReportConfiguration`
creates one registered singleton JDK proxy, frozen with the supported dispatch,
transaction, diagnosis and retained ordinary advice. Bootstrap binds that exact
singleton and preserves its original advisors. Calls use the registered interface;
there is no raw-target invocation or permission union.

## Explicit operator bootstrap and review

The executable accepts `measure|verify <deploymentDirectory>` and must run as a
disposable `java --enable-native-access=ALL-UNNAMED -jar application.jar ...`.
The deployment contains the finished executable, `template.txt`, `sibling.txt` and
the existing `reports/quarterly.txt`. The output **file**, not its parent, is granted
write access. The executable and template are explicit strict read-only recipes;
the sibling is not granted. Native support/permission failures fail the process.

1. Package the final Boot executable from **published Maven artifacts**.
2. Deploy that finished JAR and fixed resources in a private, stable deployment.
3. `measure` resolves genuine core `PreparedOperationResources` with the actual
   `ReportService.class`/Boot launcher. It emits candidate JSON and deployment generation
   only. `implementationSha256` is the **whole finished outer JAR**; neither a selected
   class digest, manifest field, nested library digest nor a candidate supplies authority.
   Both candidate filenames must be absent (including dangling symlinks); existing
   operator files/aliases are rejected and preserved. Measurement rejects symlink directory
   topology. In an operator-controlled **stable directory**, it acquires exclusive genuine
   staging channels in a private directory, fully writes/forces their bytes, then
   atomically publishes each complete file using a no-replace hardlink. A second-file failure
   cleans only exact owned inode identities; independent cleanup failures are suppressed
   onto the original failure. A replacement name is preserved. This is per-file atomic
   visibility with the genuine staging acquisitions retained through identity-sensitive
   cleanup (preventing inode-key recycling) and then closed, not a crash-atomic two-file
   transaction: review begins only after successful
   measurement completion. Hostile concurrent same-UID directory mutation is outside this
   stable-deployment contract; output-name collisions still cannot replace existing files.
4. An **external trusted reviewer** approves the exact candidate, final JAR and genuine
   resource generation. Supply `trusted-review.properties` with `reviewer`,
   `documentSha256`, `jarSha256` and `resourceGeneration`. This file belongs to operator
   configuration; arbitrary candidate files are not automatically approved by the app.
5. `verify` loads that external authority, compares actual core identities, and explicitly
   opens application-owned `ManagedRuntime` with permanent process-wide
   `Policy.NO_EXEC_HOTSPOT`. It prepares the genuine typed domain and starts Boot, then
   binds and validates the required scope before the acceptance calls.

The starter does not install a baseline, choose a review/policy, discover arbitrary
protected beans or create an ambient native owner. No opt-in generic auto-configuration
is necessary for this application-owned construction. Production must arrange its own
trusted reviewer and stable deployment; the accompanying external private-fixture
review harness is **test authority only**, not a production approval service.

## What is actually protected and observed

- `@MazewallOperation("quarterly-report")` is required, not an unprotected fallback.
  Unbound readiness and invocation both reject before body entry. An externally reviewed
  `UnsupportedReportService` final required method rejects with zero body entries.
  Other shapes/flows must satisfy the adapter contract; arbitrary async, reactive,
  streaming, self-invocation, nested extra proxies or transaction managers are not enabled.
- The real non-web **virtual request caller** offloads to a dedicated owned **platform**
  worker. `WorkerMetadata` is an application observation record capturing the actual
  Thread, ID/name, platform flag and current core invocation generation. It is not a core
  ownership token or bytecode/kernel attestation. No virtual thread installs a filter.
- Construction and JDBC schema/init/warmup run on trusted platform bootstrap under the
  explicit process baseline, **outside the narrower protected body**. Logs separate these
  phases; removed warmup rows cannot masquerade as protected JDBC effects.
- Body work has real JDBC commit/rollback receipts and externally observed rows/report
  bytes. Template truncate and sibling open produce actual JDK `AccessDeniedException`.
  Library evidence is `INFERRED_PERMISSION_FAILURE`, not confirmed kernel provenance.
- Heap entry counters and an allowed report write survive a subsequent business-error
  JDBC rollback. Files, network effects and independently committed SQL are not
  retrospectively undone. The final approved report is restored by a protected call.
- Virtual-caller interruption ends caller delivery while deliberately held lexical cleanup
  keeps the actual worker/resources live and drain `Incomplete`. Release, actual captured
  worker join, `Complete` runtime drain and closed genuine resource validation establish
  physical retirement separately. Shutdown never removes the process baseline.

This executable advertises **Security absence** only and checks no forced Security linkage.
It does not advertise authentication, principal restoration, post-authorization or HTTP
acceptance. Those optional Security semantics have their separate adapter/ordinary-JAR
evidence. There is no complete ACE/shared-heap/tenant isolation, immutable-content,
preopened-descriptor, redefinition or helper-executor protection claim.

## Build and verify

See [the external consumer instructions](../../tests/consumers/spring-boot/README.md)
for locked local publication, standalone packaging and fresh-deployment proof. The root
settings/catalog are untouched; standalone settings intentionally prevent project
substitution. No Docker, remote publication or credentials are used.
