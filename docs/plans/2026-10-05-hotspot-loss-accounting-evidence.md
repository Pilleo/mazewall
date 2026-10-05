# Selected-event conservation qualification and all-source inventory

Implemented overflow-safe pure conservation/aggregation and all-source inventory.
Unknown counters, unquiesced/unjoined source, CPU cardinality mismatch, generation
change, native sticky overflow, sum overflow, terminal pending/inconsistent counts
and unexpected producers block complete proof. This is not a new production
whole-session completeness assertion.

Actual kernel qualification attaches a filtered __x64_sys_openat producer for the
test PID, with per-CPU32byte attempted/submitted/reserve_failed/sticky-overflow
values and an isolated4096byte ring.512real opens run with no reader draining;
the producer is detached before final counts/flush/receiver completion. Rootful
pinned Go image/native host is explicit. All12possible CPU slots are sampled.

Result:512attempted,255submitted,257reserve-failed,255received; conservation proves
257lost selected events. Sticky overflow seeded near MaxUint64 is detected on a
real subsequent open and invalidates known counts. Kernel test passes; host/race
tests pass. Initial assembler rejection was traced to unsupported DWord StoreImm
encoding; explicit MOV plus StoreMem corrects it. Native test artifacts are in
tool run-3a487b282151 (actual_openat denominator line).

The test's actual producer/reader ownership is isolated and bounded; it does not
certify the production Gadget collection's producer inventory. No default BPF
program, overlay, asset pin, event layout or public schema is changed. This is the
native counter qualification gate, before integrating32byte accounting state into
the production owner with reviewed map/ABI/provenance negotiation.

## Production inventory remains incomplete

|Lane|Current evidence|Missing before global claim|
|---|---|---|
|selected_events|Existing owned event finalization and reserve lower bounds|Bind attempts/submit/overflow counters to actual collector map/session and final owner|
|definitions|Owned reader/consumer/auxiliary drain and partial perf observations|Authoritative admission/submit/lost/dictionary source counters, not only consumed records|
|enrichment|Delivered resolved/unavailable/skipped accounting|Complete source admission/rejection denominator and final native lifecycle binding|
|wire|Validated delivered rows, EOF/process/pipe joins|Producer-write to consumer-read loss/failure attestation for the session|
|retention|Explicit retained/discarded/pending counters|Binding to complete delivered source proof; pending failures stay incomplete|

Missing() requires all fixed lanes and rejects unknown extra producers; production
graph enumeration must also bind the actual source set. Selected proof may be known
with positive loss; it does not mean lossless collection, complete stack coverage
or policy eligibility. Global loss completeness/public report version promotion
remains blocked by this inventory and native integration review. The pure model
and actual kernel experiment are implemented; default collector integration and
all-source producer counters remain open scope rather than being called complete.
