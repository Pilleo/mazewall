---
document_type: execution_plan
base_revision: eacd0081
status: implemented_verified_dense_promotion_failed
---

# HotSpot Dense Readout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Delegate only if the operator chooses it.

**Goal:** Reduce interpreter readout work while preserving exact typed/raw evidence, then test whether600marked opens at1ms pacing can retire within existing budgets.

**Architecture:** Defer canonical JSON rendering until diagnostics/public mapping requests it; retain parsed, owned JSON trees in the internal model. Reuse one strict UTF8 decoder per read invocation. Keep every field, depth/type/identity check and occurrence accounting, then compare offline and actual public workloads before claiming support.

**Tech Stack:** Kotlin/JDK25, existing kotlinx.serialization/JUnit5, installed tooling probe and rootful native harness.

**Spec:** `docs/plans/2026-10-05-hotspot-performance-capability-design.md`, dense section, existing performance backlog issue.

## Global Constraints

- JDK25 idioms/library floor22; Linux-amd64/pinned Temurin25.0.4.1+1-LTS `-Xint -XX:+UseSerialGC` synchronous openat.
- Readiness<=10s/session<=60s/stop<=2 remaining seconds/enrichment deadline1s; no larger native budgets.
- Retention4096/33,554,432bytes, max line1,048,576bytes, rows65,536, frames4096, tracked32,768.
- No public API/dependency/native protocol change; preserve constructors/copies/unsigned fields and defensive filename bytes.
- No skipped validation on discarded payloads; raw fields remain canonical JSON with original unknown fields/key order preserved.
- No later-stack substitution, carrier-TID logical inference, permission fallback, GITHUB_TOKEN handling or seccomp/JVM-floor changes.
- Partial evidence stays immutable; whole-session/policy completeness stays false; actual joins/cleanup remain required.
- Preserve user work and scoped commits; native asset builds stay optional.

---

## File Structure

|File|Responsibility|
|---|---|
|Create `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotRawJson.kt`|Owned lazy canonical representation|
|Modify `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotEvidence.kt:43–94`|Internal raw-value storage with same rawJson getters|
|Modify `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotWireMapper.kt`|Pass owned trees instead of immediate tree serialization|
|Modify `profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotWireReader.kt:35–92`|Invocation-local reusable strict decoder|
|Create `profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotRawJsonTest.kt`|Lazy/unknown-field/immutable-report contract|
|Modify `profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotWireReaderTest.kt`|Malformed UTF8 after valid input/reset/split framing|
|Create `docs/plans/2026-10-05-hotspot-dense-readout-evidence.md`|Matched replay/live result and honest capability decision|

### Task1: Deferred canonical diagnostics

**Interfaces:** New internal `HotSpotRawJson(value: JsonElement, render: (JsonElement)->String = { it.toString() })`, `text: String` getter. Internal event/frame/evidence constructors consume that type; existing `rawJson: String` getters remain. Public types/descriptors stay unchanged.

- [ ] Run `./scripts/adkw blast-radius HotSpotFrame`, `HotSpotEvent`, `HotSpotStackEvidence`; outline/read mapper/model/public conversion/document files and verify all constructor callers. Current constructors are in mapper only.
- [ ] Preserve the executable Java baseline before source changes:

```bash
./gradlew :profiler:installDist
mkdir -p profiler/build/reports/hotspot-dense-comparison
cp -a profiler/build/install/tier-e-daemon/lib profiler/build/reports/hotspot-dense-comparison/baseline-lib
cp profiler/src/test/resources/hotspot/measured-event-projection.jsonl profiler/build/reports/hotspot-dense-comparison/projection.jsonl
```

Require baseline-lib not already present; record jar SHA256 values. Copy the
existing synthetic600projection with its recorded SHA256 from diagnosis, or
regenerate it using the exact generation recipe in the evidence document.
- [ ] Write red tests:

```kotlin
@Test
fun `raw rendering is deferred and canonical unknown fields survive`() {
    var renders = 0
    val tree = Json.parseToJsonElement("""{"unknown":{"quoted":"a\\\"b"},"n":18446744073709551615}""")
    val raw = HotSpotRawJson(tree) { renders++; it.toString() }
    assertEquals(0, renders)
    assertEquals(tree.toString(), raw.text)
    assertEquals(tree.toString(), raw.text)
    assertEquals(1, renders)
}
```

Add a two-thread read test comparing both strings, not asserting one render under
PUBLICATION races. Parse the genuine projection through mapper and assert its
rawJson getters equal the exact original `JsonObject.toString()` values, including
provider/error/quality fields. Retention0 still rejects invalid typed frames.
- [ ] Run `./gradlew :profiler:test --tests '*HotSpotRawJsonTest'`; expect missing helper failure.
- [ ] Implement helper:

```kotlin
internal class HotSpotRawJson(
    value: JsonElement,
    render: (JsonElement) -> String = { it.toString() },
) {
    val text: String by lazy(LazyThreadSafetyMode.PUBLICATION) { render(value) }
}
```

Only pass JSON trees owned by the parser; do not expose a mutable builder or retain
input byte buffers. In internal `HotSpotEvent`, `HotSpotFrame`, `HotSpotStackEvidence`,
replace the final constructor `val rawJson: String` with private
`rawValue: HotSpotRawJson`, and add `val rawJson: String get() = rawValue.text`.
For the data classes this field is `private val rawValue`; for stack evidence keep
immutable frame/error list copies. Keep all other fields and public conversion intact.

Mapper replacements are exact:

```kotlin
// Event constructor final argument:
HotSpotRawJson(event)
// Stack evidence constructor final argument:
HotSpotRawJson(evidence)
// Frame constructor final argument:
HotSpotRawJson(value)
```

Summary rendering stays eager in this task because it feeds completion validation;
do not mix lifecycle proof optimization with per-frame diagnostics.
- [ ] Run raw/assembler/streaming/result compatibility/coverage tests and full
`./gradlew :profiler:test :profiler:checkKotlinAbi :profiler:checkLegacyAbi`.
Compare exact public diagnostic JSON roundtrip with eager baseline. Snapshot A must
remain unchanged after later rows; no raw getter can promote coverage.
- [ ] Commit scoped model/mapper/helper/tests: `perf(profiler): defer HotSpot diagnostic JSON rendering`.

### Task2: Invocation-local UTF8 decoder reuse

**Files:** Reader and its tests. **Interfaces:** Existing `read(InputStream, (HotSpotWireRecord)->Unit): Unit` unchanged; private decode receives a `CharsetDecoder` local to that invocation, never shared between read calls.

- [ ] Add red regression with a valid first row followed by malformed UTF8:

```kotlin
@Test
fun `valid prefix is delivered but malformed second row still aborts`() {
    val first = """{"schema_version":1,"kind":"collector_attached","session_id":"s"}""" + "\n"
    val bytes = first.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28, 0x0a)
    var delivered = 0
    assertFailsWith<HotSpotProtocolException> {
        HotSpotWireReader(HotSpotReplayLimits()).read(bytes.inputStream()) { delivered++ }
    }
    assertEquals(1, delivered)
}
```

Add valid multibyte names split into13byte input chunks and independent consecutive
read invocations. Tests prove preserved decoder/refusal behavior; do not assert
wall-clock latency. Observe the existing semantic tests passing before refactoring.
- [ ] Move decoder construction into `read`:

```kotlin
val decoder = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
// At each complete line:
consume(decode(line.toByteArray(), decoder))
// Inside decode, use the convenience decode that resets state per full input:
val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
```

Keep byte/depth/row limits before downstream allocation, newline truncation refusal
and event/session unsigned parsing. Do not replace the JSON parser or relax depth.
- [ ] Run all reader/protocol/assembler/streaming tests, ktlint/main Detekt, ABI;
commit `perf(profiler): reuse invocation-local UTF8 decoder`.

### Task3: Performance and real public promotion gate

**Files:** Evidence, performance issue and public guide; modify the existing dense
test only if genuine accepted promotion is proved. **Interfaces:** Existing
`HotSpotPerformanceMain replay`, `HotSpotSupportedWorkloadMain` settings and public
`HotSpotPublicIntegrationTest` remain authoritative.

- [ ] Build/install baseline and candidate JVM distributions at separate new paths.
Use the same native `build/footprint-candidate-installed` graph for both. Never
compare an old native graph against a new one while attributing Java effects.
- [ ] Run3serial alternating pinned `-Xint -XX:+UseSerialGC` replays on identical
2380byte and1,285,889byte schema1projection inputs from diagnosis. Record SHA256,
all pipeline/mapping/remainder/finish rows and JVM arguments. Diagnostic rendering
cost is moved, not eliminated: also time `HotSpotEvidenceDocument.encode` on the
result with `System.nanoTime`, retain result bytes/hash and compare parsed JSON.
- [ ] Execute actual guarded matrix:

```bash
DOCKER_HOST=unix:///var/run/docker.sock \
MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock \
MW_HOTSPOT_NATIVE_ACCEPTANCE=true \
MW_HOTSPOT_ASSET_TEST_DIR=/home/leanid/Documents/code/java/jseccomp/profiler/hotspot-agentless/build/footprint-candidate-installed \
./gradlew :profiler:installDist :profiler:integrationTest \
  --tests '*HotSpotPublicIntegrationTest' -x :profiler:rootfulContainerTest
```

- [ ] On dense accepted result require600marked tokens, exact PID birth generation,
known A/B frame order for each attributed token, explicit empty-frame unavailability,
terminal pairing, no pending ownership and actual helper/client absence. On rejection
retain all failure/partial/timing rows; faster replay does not change supported claims.
- [ ] Write evidence, keep issue open if either live600case or admission remains
unproved. Run backlog/lint/whitespace/ABI/fullbuild and commit only owned files.

## Completion gate

Execution:92e9fa0a deferred canonical diagnostics;233f8e6a invocation-local decoder.
Full semantic/ABI/static controls and actual7case matrix pass. Dense600case remains
rejected with301received/276retained/25pending and no pending ownership. Offline
rendering costs are retained, not hidden. See
[evidence](../../plans/2026-10-05-hotspot-dense-readout-evidence.md).

Exact evidence/refusal/resource contracts pass, matched timings and serialization
cost are retained, actual public result is reported honestly. A600case guarantee
is promoted only when the live acceptance criteria hold with unchanged budgets.
