# HotSpot interpreter performance diagnosis

Budgets, integrity validation, ownership and incomplete coverage are unchanged.
Normal JIT is an offline diagnostic control, not supported live admission.
Measurement commit27846a591168fd2dfd09a7fe5b62c80ae3d041f9; earlier commits
26f011d9asset timing/673ab63doffline tooling.
Runtime25.0.4.1+1-LTS, pinned image
`eclipse-temurin@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6`.
Interpreter `-Xint -XX:+UseSerialGC`; JIT control has neither flag.
Asset manifest SHA256 `9ebd31a29c1c5f8cef6d7e58a6a3808f0c1c5bfb12e3105d3371d772846ca89d`.
Listed assets95,912,323bytes; helper95,730,281bytes (**99.81%**).

Raw reports: `profiler/build/reports/hotspot-performance/diagnosis-20261005/`.
Three repetitions alternate Xint/JIT, JIT/Xint, Xint/JIT, each mode running assets,
single replay,600replay in order. This session's measurements do not overlap.
Host load/cache are uncontrolled; fresh process does not mean cold filesystem cache.
Container launch-to-exit time is a separate provenance field, not JVM CPU time.
Single projection2380bytes/SHA256
`f8f1674326e7fc08cb9de310a631462d29bf964aaa430d20e94602854e5e6720`, from
`profiler/src/test/resources/hotspot/measured-event-projection.jsonl`.
Synthetic600projection1,285,889bytes/SHA256
`f82aa97ba6397666ad3db3896b0a94827e1c2917e359aca9676a49b5de7545dd`.
Generation: deep-copy genuine event/terminal600times, set outer/nested ID to
`<session>:8:<61+i>`, sequence61+i, summary received/resolved600; compact JSONL
with final newline. These are not600 fresh native samples. All six replays report
1201rows/600received/600retained/0discarded/0pending/no native attestation.

## Asset measurements (nanoseconds)

Total includes class initialization/report metadata work; phase sum need not equal
total. Every run recomputes every checksum.

|Mode/repeat|Total|Manifest|Checksums|OCI graph|Collector|
|---|---:|---:|---:|---:|---:|
|Xint0|85442334721|253010817|85085969889|57055614|588300|
|Xint1|58759214883|157957215|58512986349|46278392|387592|
|Xint2|70511743818|120498194|70273347558|65506575|532053|
|JIT0|669226680|191730868|394018508|40494047|447622|
|JIT1|499238864|158513006|258350109|37888162|512192|
|JIT2|618028483|155742400|344078717|59785703|729869|

Median interpreter checksums **70.273s**, JIT **0.344s** (~204x).
Checksums99.66% of median-total row70.512s; manifest/graph costs are minor.

## Replay measurements (nanoseconds)

Pipeline includes reader/framing/UTF8/depth/JSON and assembler callback. Mapping
times callback; remainder is not isolated parser CPU. Final construction is separate.
Single input includes cold initialization, not throughput.

|Mode/repeat/input|Pipeline|Mapping|Reader/decode/I/O|Finish|
|---|---:|---:|---:|---:|
|Xint0/single|270663759|40385266|230278493|2305964|
|Xint1/single|167333669|29155037|138178632|2012620|
|Xint2/single|207199381|36004942|171194439|2450804|
|JIT0/single|137429212|18463857|118965355|1483356|
|JIT1/single|140206997|25953475|114253522|1984589|
|JIT2/single|255478660|36594795|218883865|2485951|
|Xint0/600|3303672791|1398636544|1905036247|4910597|
|Xint1/600|2859127167|1237482452|1621644715|3436439|
|Xint2/600|3709355699|1582025575|2127330124|3870006|
|JIT0/600|520769257|178034012|342735245|4970720|
|JIT1/600|348735862|105631835|243104027|3266595|
|JIT2/600|515434128|171179970|344254158|4783567|

Interpreter600median **3.304s**:1.399s mapping/1.905s remainder; JIT0.515s.
Final construction interpreter median3.870ms. This supports a possible backlog,
not a model of native producer pace or certification of native drain.

## Separate JFR attribution

Offline profile-settings/dump-on-exit runs are excluded from matched medians.
Recordings and sample inventories are retained beside reports.
Assets3938samples:3709stacks (**94.18%**) contain JDK SHA2;2388leaf samples
`SHA2.implCompress0`; rotations/VarHandles/byte conversion dominate other hot methods.
JDK samples reference Unsafe; diagnostic code adds no Unsafe usage.
Replay185samples:90stacks String/52JSON tree reader/47JsonObject/40lexer/29quoted
JSON printing. Parsing and diagnostic tree rendering contribute; sample count does
not justify precise allocation percentages or a parser replacement.

## Live measurements

Matrix **7tests/0skips/0failures/0errors**,265.753s,
timestamp2026-10-05T15:32:34.669Z, same native graph/UID1000.

|Case|Admission ns|Warmup ns|Marked ns|Finish-call ns|Received/retained/pending|
|---|---:|---:|---:|---:|---|
|20/40/100ms accepted|58884713673|4036894965|2014626977|1209697850|62/62/0|
|600/40/1ms rejected|61284901502|48341068|730650771|2374688231|268/267/1|

Reports under `profiler/build/reports/hotspot-acceptance/`:
`hs-current-a9b33ce8-7ab8-4959-933d-9f494a760e4d-current` and
`hs-current-038cb213-1701-4038-8433-13763acc8e2f-dense`.
Rejected original failure/partial evidence/diagnostics retained. Both have no
pending ownership; helper/client absence checked before forced fixture cleanup.
Finish-call includes public adaptation/failure handling:2.375s is not the configured
native stop budget. No budget increased.

## Decision and verification

Primary cost is interpreted SHA256 over the95.7MB helper. No cache substitutes for
hashing every byte. First minimal-scope candidate: **reduce verified release helper
debug/symbol footprint**, preserving hashes/build info. ELF debug info/line/frame/
loclists/rnglists/addr sections are present. This refinement avoids a new native
hashing provider/trust boundary; no reduction/bound promised before actual comparison.
See [next draft](../superpowers/plans/2026-10-05-hotspot-release-footprint.md).
Dense parsing/rendering needs separate later work; smaller helper alone cannot fix
finish or establish complete coverage. Existing performance issue stays open.

Red missing timing/replay/settings interfaces observed; integrity/immutable snapshots,
ordered identity/frame/protocol/streaming/settings tests pass. Full profiler tests,
main Detekt/ktlint/Kotlin/legacy ABI pass; native matrix actually ran7cases. Final
merge/lint/backlog/link/whitespace gates pass: root build run-b64225ab7266 exit0,
lint/backlog run-0807b8fcb5e9 exit0; local plan/evidence links and placeholder scan
pass. Repository plan metadata was added after the first backlog check correctly
refused missing frontmatter. No operator hook/configuration was changed.
