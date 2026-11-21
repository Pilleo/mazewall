# Portal code-generation hardening review

**Scope:** Contract resolution, DTO shapes, generated codecs, schema identity,
Gradle output publication, and directly connected broker/worker validation.
Reviewed the current checkout at committed anchor
`b2c9b335720fd0491a8d86917a3b0593b818a4ec`, including its existing local changes.
This is a review and proposed remediation sequence; application code was not changed.

**Conclusion:** Keep the existing generator and owners. Six finding groups were
reproduced with compiled fixtures; one connected lifecycle finding is source-confirmed
and needs a real-worker regression. Fix these before expanding the generated API.
Existing sealed boundary kinds, copied schema collections, stable method IDs,
capability-placement checks, noninitializing contract loading and staged output
publication are useful foundations.

## Findings and work items

| ID | Finding | Evidence | Issue |
|---|---|---|---|
| CG01 | Accepted byte-array/list signatures produce uncompilable Kotlin | Compiled generated sources | [Type agreement](../backlog/resolved/issue-20261103-095305-625-make-accepted-portal-byte-array-and-list-contracts.md) |
| CG02 | DTO validation loses nested type information and admits unconstructible shapes | Compiled generated sources and resolution probe | [Complete validated shapes](../backlog/issue-20261103-095306-294-validate-complete-portal-dto-shapes-before-source-.md) |
| CG03 | Schema identity does not describe the actual wire layout | Same-name, separate-classloader probe | [Versioned full-schema identity](../backlog/issue-20261103-095306-992-fingerprint-the-complete-versioned-portal-wire-sch.md) |
| CG04 | Generated decoding accepts malformed/extra data | Compiled dispatcher with service-entry counter | [Strict decoding](../backlog/resolved/issue-20261103-095307-699-reject-noncanonical-and-incompletely-consumed-gene.md) |
| CG05 | Worker checkout ends before generated response validation | Connected source path; native reproduction pending | [Validation-owned checkout](../backlog/resolved/issue-20261103-095308-453-keep-portal-worker-checkout-owned-through-generate.md) |
| CG06 | Encoding bounds are checked after traversal and allocation | Compiled oversized reply probes | [Pre-allocation bounds](../backlog/issue-20261103-095309-136-bound-generated-portal-encoding-before-allocation-.md) |
| CG07 | Overlapping output directories can silently lose generated files | Direct publisher filesystem probe | [Disjoint publication ownership](../backlog/resolved/issue-20261103-095309-827-reject-overlapping-portal-generated-output-destina.md) |

All seven are open, high-priority corrective tasks. These findings concern the
generated contract and resource/ownership guarantees; the review did not demonstrate
a bypass of process containment.

### CG01 — Make accepted types actually compile

**Evidence:** [PortalStubGenerator.kt:265–292](../../../portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt#L265)
maps Java `List` declarations to `MutableList` and delegates primitive arrays to
KotlinPoet's general class conversion. [The decoder at lines 432–450](../../../portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt#L432)
returns `buildList`, whose type is read-only `List`.

Two accepted Java contracts reproduce compile failures:

```java
byte[] echo(byte[] value);       // generated signature uses Array<Byte>
List<Integer> many();            // MutableList<Int> signature, List<Int> result
```

The byte-array encoder/decoder already use `ByteArray`. The disagreement is in
source type emission, not the intended wire representation. An application can
therefore pass schema validation and fail only in generated code compilation.

**Correction:** Make declaration and codec types agree for every accepted shape,
including primitive arrays and Java/Kotlin collection signatures. Preserve existing
wire bytes and public compatibility. Compile and execute generated host and worker
sources, rather than proving support through source-substring assertions.

### CG02 — Resolve a complete, position-aware DTO model

**Evidence:** [BoundaryTypes.kt:171–247](../../../portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/BoundaryTypes.kt#L171)
does not reject abstract DTOs, reads record/constructor members through erased
`.type`, and stores no component nullability.
[PortalServiceSchema.kt:80–98](../../../portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalServiceSchema.kt#L80)
resolves nullability only at the method parameter/result level.

Reproduced:

- A public abstract DTO with one public constructor is accepted, then generated
  decoding attempts to instantiate it and fails compilation.
- `data class Dto(val name: String?)` is accepted, then emitted encoding passes
  `String?` to `encodeString(String)` and fails compilation.
- `record Dto(List<String> values)` loses its member generic type and rejects as
  raw `java.util.List`. This is an expressiveness/diagnostic gap, not silent data loss.

**Correction:** Resolve member generic types and nullability before emission; validate
concreteness, enclosing visibility, constructor/read access and supported positions.
Initially reject unsupported nullable/nested shapes with an exact field path rather
than quietly claiming support. Adding wire representations for new shapes requires
explicit compatibility tests and the schema version policy from CG03.

Use distinct value, inbound-capability/snapshot and result alternatives. `Unit` should
be a result shape; a returned or nested capability should not be expressible as a
validated copied-value shape. Keep the validated model immutable and its construction
internal. A constructor/getter can still execute trusted application code: validating
its shape does not prove that code pure or sandbox it in the host.

### CG03 — Hash the actual schema, with a defined encoding version

**Evidence:** [PortalManifestGenerator.kt:46–63](../../../portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalManifestGenerator.kt#L46)
hashes service name, method ID/name and top-level type names. It does not walk the
resolved boundary tree or record its nullability/codec choices.

Two separate classloaders loaded the same binary names for this contract:

```java
interface Service { Dto echo(Dto value); }
record Dto(int value) {}         // first compilation
record Dto(long value) {}        // second compilation
```

Generated codecs differ, but both produce
`sha256:db0fdb5911558a4dff6d30812ab991053f8eb10da5ae04028c563b4d1f966821`
in the review fixture. The existing generic-fingerprint test uses different service
names, so it cannot isolate whether generic shape affects the hash.

**Impact:** `schemaId` is not reliable compatibility evidence for DTO evolution.
Artifact digests and generation-bound readiness still protect their separately
measured inputs; they do not make this shallow schema hash complete. No mixed-package
end-to-end containment bypass was tested.

**Correction:** Canonicalize the complete ordered value tree, nullability, collection
members, capability positions, adapter limits and explicit codec/schema version.
Include enum/exception contract metadata under a documented rule. Resolve once and
derive source, fingerprint and diagnostics from that same validated model. Preserve
method IDs; define how regenerated artifacts reject incompatible old metadata.

### CG04 — Reject malformed data before accepting a generated call

**Evidence:** [Generated host decoding](../../../portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt#L148)
and [worker dispatch](../../../portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt#L194)
do not require full payload consumption. `Reader.exhausted()` exists but is used
by remote-error decoding rather than ordinary generated values.
[PortalCodec.kt:74,108–116](../../../portals/runtime/src/main/kotlin/io/mazewall/portal/PortalCodec.kt#L74)
accepts every nonzero Boolean byte and replaces malformed UTF-8.

An unmodified generated dispatcher was compiled and executed with a service-entry
counter. It accepted all of these inputs and called application code:

- An extra byte after a valid integer.
- A nonempty payload for a zero-argument method.
- Boolean byte `2`, normalized to `true`.
- UTF-8 bytes `C3 28`, normalized to `U+FFFD` followed by `(`.
- Two map entries with the same key, silently collapsed into one entry.

**Correction:** Require exact payload and capability consumption, canonical Boolean
bytes, strict UTF-8 and an explicit duplicate-key rejection policy. Validate empty
request/Unit-response shapes too. Keep malformed-wire failures distinguishable from
completed business exceptions. Test requests before service entry and results before
delivery, with truncation/extension and malformed nested-value cases.

### CG05 — Keep response validation within checkout ownership

**Evidence:** [ProcessBroker.kt:297–307](../../../portals/runtime/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt#L297)
returns the worker to the pool as soon as the frame passes broker validation.
Generated decoding happens afterward in the host stub. An ordinary decoder
`IllegalArgumentException` therefore bypasses the broker's failed-checkout path.
[PortalStatefulInvocationGate.kt:39–49](../../../portals/runtime/src/main/kotlin/io/mazewall/portal/PortalStatefulInvocationGate.kt#L39)
loses state for unknown-completion `PortalCallException` or fatal `Error`, not such
an ordinary decoder failure.

**Impact:** A structurally valid success header with a corrupt generated result can
fail after the exact worker has become reusable. This is a source-confirmed lifecycle
gap; the review did not launch a malicious worker to reproduce it end to end.

**Correction:** Add a generated-response validation boundary while the
exact checkout remains owned, then release it only on a validated result. Malformed
protocol responses must retire that worker and terminally lose singleton state;
completed business exceptions remain ordinary completed calls. Distinguish wire
corruption from trusted host DTO-constructor failures, preserve causes, and keep
physical retirement separate from result delivery. Keep ownership changes internal,
with only the narrow bridge required by separately compiled generated consumers.
Start with a real-child regression.

### CG06 — Enforce bounds while encoding

**Evidence:** [PortalStubGenerator.kt:336–363](../../../portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt#L336)
builds per-field/element arrays before concatenating them.
[PortalCodec.kt:48–66](../../../portals/runtime/src/main/kotlin/io/mazewall/portal/PortalCodec.kt#L48)
allocates variable-sized values and concatenations without the protocol request/reply
budget. Frame/admission checks occur after the encoded array exists.

The generated dispatcher produced:

- A 65,544-byte list reply containing 16,385 elements, which its own
  `collectionCount()` reader rejects against the 16,384 limit.
- An 8,388,613-byte encoded string reply, already beyond the 8 MiB frame cap.

**Correction:** Use a shared bounded writer with checked arithmetic and finite byte,
collection and traversal budgets, enforced before growth or expensive traversal.
Apply it to current synchronous requests and worker replies as well as planned
submit-time snapshots. Track temporary copies in peak-memory accounting; a late
frame-size assertion is not a memory bound. Preserve valid wire bytes.

The proposed P03 snapshot API should reuse this writer. P10 measures and optimizes
its costs after correctness is established, rather than owning the safety fix.

### CG07 — Validate publication paths before moving anything

**Evidence:** [GeneratedOutputPublisher at PortalCodegenPlugin.kt:157–183](../../../portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalCodegenPlugin.kt#L157)
rejects equal destinations but permits ancestor/descendant destinations. Publishing
`output/child` and then `output` succeeds while deleting the newly published child
along with the parent's backup. Default sibling destinations are unaffected; custom
overlapping output configuration is accepted incorrectly.

**Correction:** Build a validated ownership plan requiring pairwise disjoint output
trees and no staged/destination aliases before any filesystem mutation. Keep
staging, backup and publication ownership explicit, and inject failures at each
move/rollback boundary. The generation task's unconditional staging cleanup at
lines 111–112 also needs primary/fatal failure preservation, following the existing
metadata publisher's cleanup pattern. That error-masking path was source-reviewed,
not fault-injected in this review.

## Type, state and debugging direction

Use a small compiler pipeline rather than another framework:

```text
declarations
  → one immutable, fully validated contract model
  → typed host/worker/metadata artifacts
  → staged output with validated ownership
  → published generation
```

- Separate value shapes from inbound capabilities and result-only Unit. Emit JVM
  declarations and codecs from the same model, eliminating contradictory type/kind/
  nullable combinations. Preserve public generator entrypoints with internal adapters.
- Give generated artifacts an explicit host/worker/metadata role. The current
  `endsWith("PortalDispatcher")` routing becomes fragile as P03 adds more artifacts.
- Keep schema format and wire encoding versions explicit. Preserve golden valid
  wire cases and same-binary-name compatibility tests; avoid speculative support
  for arbitrary serializers, polymorphic graphs or future JVM features.
- Extend P02's existing diagnostic work: report validated method IDs, full field
  paths, schema/codec version, limits and artifact roles. Errors should identify
  contract/method/parameter-or-result/field plus expected and actual shape, without
  logging payload values. `portalDescribe` currently prints registrations only.
- Expand actual generated-source compile/round-trip tests and deterministic malformed
  input/failure injection. The selected tests passed while these probes reproduced
  the gaps; more source-substring checks would not close them.

## Suggested order and relationship to the existing plan

1. CG01 type agreement and CG07 publication preconditions.
2. CG02 validated shapes, then CG03 complete schema identity.
3. CG04 strict decoding and CG06 bounded encoding; then CG05 checkout/validation integration.
4. P02 diagnostics/build ergonomics and P03 generated bindings reuse those contracts.
   P10 remains measurement-driven optimization.

The [developer-experience plan](../../superpowers/plans/2026-11-03-portal-developer-experience.md)
continues to own those features. This review adds corrective work rather than
duplicating the feature issues or authorizing their execution.

The [implementation execution guide](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#cg01--compilable-boundary-types)
provides separate junior work cards for CG01–CG07, including fixture construction,
test commands, intended failures and the proof needed before closure. It does not
replace the evidence distinctions in this review.

## Verification and evidence limits

- Refreshed relevant compiled classes and ran `PortalManifestGeneratorTest` (2),
  `ResolvedPortalContractTest` (4), `PortalStubGeneratorTest` (27), and
  `PortalCodecTest` (3): **36 tests, zero failures/errors/skips**.
- Disposable Java fixture harness used the repository-resolved **Kotlin 2.4.21**
  compiler and JDK 25. It asserted the observations above and completed successfully.
  Generated sources were compiled without metadata-skip flags or source repair.
- Local evidence is under `/tmp/opencode/portal-codegen-review.8O5cAU/`:
  `CodegenReviewProbe.java`, `probe.log`, `focused-tests.log`, generated sources and
  their compiler logs. This temporary location is not durable release evidence;
  each issue must land its meaningful regression in the owning test suite.
- Full build, full TestKit suite, native hostile-worker test and performance benchmarks
  were not run. No production code fix or performance improvement is claimed.
