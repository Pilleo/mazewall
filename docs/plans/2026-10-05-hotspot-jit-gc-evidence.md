# Pinned JIT and GC candidate qualification

Implemented offline proof gate, exact-token JVM fixture/verifier and explicit
experimental schema1 harness. Production ValidateProfile/Discover remain unchanged;
no UseCompiler/G1 whitelist relaxation or new native lease is activated.
Host/race tests reject missing compiled generation/lease, changed generation,
missing concurrent reclamation protection, changed build, zero useful and wrong/
mixed/reordered corpus evidence. Permission/exec rules remain in production identity.

Pinned Temurin image119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6;
same stripped collector335e6704ea10bc9b2ab3a0ac2d9387271babea7a04d943c2e89b8dd178b39ea2
and gadget3ae6007bdf574e1c082b5f185f38d3f60bb678d9e964f05a658da4ac5670de68.
Oracles are prepared before attachment; no fixture value supplies production frames.
Actual VM.flags/compilation logs, capture JSONL/manifests and results retained under
`profiler/build/reports/hotspot-capability-ready-qualification/`.

|Candidate|Flags|Marked|Useful application prefixes|Application unavailable|Production qualified|
|---|---|---:|---:|---:|---|
|C1/Serial|-XX:+UseSerialGC -XX:TieredStopAtLevel=1|40|28|12|false|
|C2/Serial|-XX:+UseSerialGC -XX:-TieredCompilation|40|31|9|false|
|Interpreter/G1|-Xint -XX:+UseG1GC|40|26|14|false|

Useful prefixes have correct application leaf/router order and exact event token/
host PID/birth ticks. Native-only correlated stacks are not wrong Java labels;
the verifier separately reports application unavailability. Prefix correctness is
not full-stack/inline/source completeness or metadata lifetime protection.
All targets/collectors exit0 and are retired through named owner cleanup.
The first fixed2second harness wait missed tokens because attachment started later;
retained initial artifacts are at `hotspot-capability-qualification/`. A red readiness
test now requires the actual newline-terminated collector_attached record.
Another red oracle test corrects native-only/application-unavailable classification;
corrected verdicts are computed from retained records, not rerun/rewritten stacks.

## Lifetime gate: promotion remains blocked

Exact JDK source520406d87195 is retained with SHA256 manifest beside reports.
`src/hotspot/share/code/nmethod.hpp:259,592` declares/exposes compile_id.
`nmethod.cpp:2086–2127` requires a concurrent unlink/flush handshake, registers
unlinked methods for later safe flushing and purges under CodeCache_lock.
Neither a pointer nor an even safepoint counter conveys that native lease.
Current event layout captures PID birth/BOOTTIME/safepoint epoch, not a compile ID
or a code-cache/object reclamation lease. Those gate facts therefore remain false.

`g1ConcurrentMark.cpp:1384–1415` places remark/class unloading at a safepoint.
This is evidence worth extending, not proof that every metadata/code reclamation
path is protected by the current Serial contract. The complete G1 path and capture/
decode binding remain unqualified; no concurrent reclamation fact is fabricated.
Current epoch overlay caches are address-keyed and purge on rejected/changed epochs;
there is no newly implemented compiled-generation admission mechanism.

The bounded initial corpus is not an adversarial deopt/code-cache/unloading proof.
Existing disposable-loader controls demonstrated why wider promotion needs those
mechanisms. Once capture-time code generation and retained definition leases are
designed/approved, run pressure/deopt/unloading/redefinition negatives before any
whitelist change. Qualification software/corpus is delivered; wider production
support and those promotion stress gates remain explicitly pending.
