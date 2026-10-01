# Interpreter identity investigation

## Finding

The capture identifies an interpreted method using its `Method*` address and a
32-bit pointer-derived cookie. Neither is an allocation generation. Deferred
symbolization and address-keyed caches can therefore confuse different metadata
incarnations when their captured identity is indistinguishable.

The initial synthetic probes established an acceptance boundary. The subsequent
live disposable-loader experiment below reproduces wrong-generation attribution
in both diagnostic and normal collectors. It still does not estimate frequency
in arbitrary applications or demonstrate the particular 32-GiB collision.

## Evidence chain in the pinned provider

Base profiler revision: `ef6cf29492c8a980f889052903fa2dd0c0765cc0`.

1. `support/ebpf/hotspot_tracer.ebpf.c:277–300` reads the current ConstMethod
   pointer, stores the full Method pointer and assigns `cmethod >> 3` to the
   `u32 ptr_check` member (`:53`). High pointer bits are discarded at assignment.
2. `calc_line` (`:120–122`) packs subtype, PC/BCI and the cookie into one word:
   subtype occupies four bits, PC/BCI twenty-eight bits and cookie thirty-two bits.
3. `interpreter/hotspot/instance.go:884–887` extracts the Method address, subtype,
   PC/BCI and low-32-bit cookie. Our checked interpreted path validates the live
   cookie and cached full ConstMethod pointer before/after method lookup.
4. These reads validate the metadata visible during lookup, not the generation
   that was alive when the earlier kernel event was captured. A replacement that
   happens before both reads can look stable throughout the check.
5. `processmanager/manager.go:350–363` has an earlier frame-cache fast path. Its
   key is the captured raw frame and, for HotSpot's PID-specific frames, the PID.
   A hit appends cached frames without invoking `Symbolize` or the new pointer
   validation. The five-minute lifetime (`:48`) is refreshed on hits (`:355`);
   it is an eviction policy, not an allocation-generation check.
6. The HotSpot method cache is address-keyed (`instance.go:77–79,260–263`). The
   inspected HotSpot implementation has process/mapping handling, but no observed
   class-unloading-specific invalidation of this method cache. Method metadata
   hashes calculated on a miss do not validate a later address-only cache hit.

## Two distinct mechanisms

### Truncation collision

The token is `uint32(ConstMethod >> 3)`. For the synthetic pair:

| Value | Old | Replacement |
|---|---:|---:|
| Full pointer | `0x200` | `0x800000200` |
| Shifted pointer | `0x40` | `0x100000040` |
| Captured cookie | `0x40` | `0x40` |

The period is `2^(32+3)` bytes: 32 GiB. The first probe assumes the same Method
address has been reused and replacement metadata is already cached. A delayed
old capture then passes the cookie and live/full-cached-pointer checks. The
probe demonstrates ambiguity of the captured identity, not that the JVM was
observed allocating these particular addresses.

### Same-address reuse / ABA

Both Method and ConstMethod allocations can be replaced while retaining the same
addresses. The cookie then agrees even if it is widened to a full pointer.
The second probe simulates changed target metadata at unchanged addresses. The
checked lookup returns `OldGeneration.leaf` from cache; removing that method
cache entry and decoding the simulated current memory yields different metadata.
Both before/after pointer reads saw the same address, so they could not detect
the replacement that occurred before the reads.

The synthetic replacement buffer is a minimal decoder fixture, not a live JVM
class image. Its purpose is to establish that the cache-hit path cannot tell
changed metadata from a new incarnation using pointer equality alone.

## JVM lifetime context

OpenJDK 25u source review provides the relevant allocator/lifetime mechanism:

- `oops/method.cpp`, `Method::allocate`: Method and ConstMethod are separately
  allocated under the owning ClassLoaderData. `Method::deallocate_contents` frees
  ConstMethod metadata and clears the pointer.
- `classfile/classLoaderData.cpp`, `ClassLoaderData::~ClassLoaderData`: class-loader
  destruction deletes its metaspace ownership.
- `memory/metaspace/metaspaceArena.cpp:225–227`: allocation first attempts to reuse
  a freed block; `:395–408` returns deallocated blocks to the free-block list.

References (25u branch reviewed on 2026-10-01; explanatory source, not a claim of
the exact build source of the digest-pinned Temurin image):

- https://github.com/openjdk/jdk25u/blob/master/src/hotspot/share/oops/method.cpp
- https://github.com/openjdk/jdk25u/blob/master/src/hotspot/share/classfile/classLoaderData.cpp
- https://github.com/openjdk/jdk25u/blob/master/src/hotspot/share/memory/metaspace/metaspaceArena.cpp

Metadata protection while a method is actively on a JVM stack does not pin it
for an external observer's later processing. The captured operation can finish,
its loader can become unloadable, and userspace symbolization can occur later.
The live follow-up below reproduces wrong-generation attribution under verified
class unloading and identifies producer-reference reuse as the observed bypass.

## Reproduction and conclusion

```bash
bash scripts/probe-identity-boundary.sh
```

Expected exit: 1. Artifact `build/identity-boundary.UanQmL/probe.log` contains both
failed stronger identity assertions. The probe runs in a copied provider tree;
normal sources, module cache and capture protocol are unchanged.

The bounded lookup fix remains useful: distinct captured cookies, changed cached
full pointers, unreadable memory and replacements during lookup are rejected.
The stronger guarantee needs trustworthy metadata-incarnation/lifetime evidence
and cache invalidation at all relevant layers. Full pointer capture addresses
truncation only; disabling one cache or shortening TTL does not restore missing
historical identity. A cache policy may help conservatively reject ambiguity, but
it must not be advertised as a generation proof without corresponding evidence.

The live follow-up below uses disposable class loaders, verifies actual unloading,
records raw identities in a validation-only path, and compares class/method labels
with operation tokens. This separates practical JVM reuse behavior from the
synthetic information-loss proof before selecting a protocol/cache remedy.

## Live follow-up: reproduced in the pinned JVM

`UnloadDriver` loads eighty differently named `UnloadG00000`…`UnloadG00079`
classes from equal-length modified template bytecode, using a fresh disposable
class loader for each generation. Each class performs ten unique file opens per
branch. Operations identify generation/sequence/branch in exact filenames; a
manifest predeclares all 1,600 tokens. Method handles and class/loader references
are released before requesting GC. Weak references and `class+unload` logs verify
actual unloading. Target JVM: digest-pinned Temurin, `-Xint`, Serial GC, 64 MiB
heap and 32 MiB metaspace cap. Java is an ordinary user with no injected agent.

Three fresh processes produced the same counts:

| Build/run | Observed | Correct segments | Unavailable | Wrong generation | Confirmed unloaded |
|---|---:|---:|---:|---:|---:|
| Initial raw diagnostics, `capture.x8pMCm` | 1,600 | 20 | 20 | 1,560 | 80 |
| Full-pointer diagnostics, `capture.ZlX9mH` | 1,600 | 20 | 20 | 1,560 | 80 |
| Normal uninstrumented collector, `capture.UueXxk` | 1,600 | 20 | 20 | 1,560 | 80 |

Generation zero was unavailable. Generation one was correctly symbolized.
Generations two through seventy-nine were labelled as generation one.
In the normal control, `/fixture/mw-unload-g00002-s000-A` was attributed to
`void UnloadG00001.sharedLeaf(java.lang.String)` followed by
`void UnloadG00001.pathA(java.lang.String)`, with apparently valid interpreted
BCIs. The operation retained PID/TID/session identity and its own reference;
process/session scoping did not detect this within-process metadata reuse.

## Cache layer identified by live evidence

The producer cache is earlier than the user-mode frame cache described above.
`support/ebpf/interpreter_dispatcher.ebpf.c:354–371` builds an exact cache key from
PID, TID and captured raw frame data. A hit reuses the old correlation ID and
returns without publishing another trace definition. It has no class-loader or
metadata generation in the key.

In `capture.ZlX9mH`, four nonzero references were first published in generation
one and then reused through generation seventy-nine. Only three dynamic-method
checked-lookup diagnostics appeared, all for generation one. Hence later reused
references did not cause the new interpreted pointer checks to run. This directly
identifies stale producer-reference/definition reuse in this experiment.

The raw definition logs also show user-mode frame-cache hits for the same Method
address/cookie pairs. Those hits occur in the first publication generation here;
they establish the additional bypass path but are not, by themselves, a separate
live proof of cross-generation frame-cache failure.

JVM logs independently show generation one onward unloaded at the same Klass
address in each process. Reused exact producer keys establish reuse of captured
Method addresses and cookies. The full live ConstMethod observations were taken
when definitions were published in generation one; they do not establish every
later generation's full ConstMethod address. Thus this experiment demonstrates
real metadata-identity/caching failure without attributing it specifically to a
32-GiB truncation collision.

## Reproducible diagnostic and normal control

```bash
DOCKER_HOST=unix:///var/run/docker.sock \
COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock \
  make unloading-investigation DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'

COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock \
MW_UNLOAD_STRESS=true MW_REQUIRE_IDENTITY_DIAGNOSTICS=false \
  bash scripts/run-demo.sh
```

Both are currently expected to fail the wrong-generation assertion, after writing
`unloading-report.json`. The explicit control flag only removes the requirement
for raw debug logs; it does not disable generation validation. Diagnostic patch
`identity-observations.patch` and its helper additions are installed only by
`--identity-observations`, with separate binary/modfile/manifest. They read/log
identities but do not clear caches or alter the wire protocol. Normal build
bundle remains `9e1564da79a1e3c4cac750968fcf0ddb323e396dbdb3b13fac99011f74df47eb`;
the second diagnostic bundle is
`ab712b44d5317927c4ca9e1c3cd15ce5d6464365330ff8eef29e575cd2df39e9`.

The fix design must address producer-reference reuse, user-mode frame-cache reuse,
and method metadata lifetime together. Improving only the method-cache check
cannot protect events that never reach it. The live finding supersedes the
earlier statement that only synthetic ambiguity had been reproduced.

Investigation verification: the generation validator's failure-classification
tests, ordinary race/upstream tests, normal build, final diagnostic build,
backlog validation, shell syntax and repository `./gradlew build` passed. The
live unloading acceptance assertions remain failing in all three runs. No
generation/protocol/cache-policy fix has been applied.

## Remedy research: safepoint epoch candidate

Read-only inspection of the exact pinned Temurin image on 2026-10-01 found:

- JVM source repository `https://github.com/adoptium/jdk25u.git`, source commit
  `520406d87195`, as recorded in `/opt/java/openjdk/release`.
- `libjvm.so` SHA-256
  `5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c`;
  ELF build ID `e4dad22744e32e4d71755f4a58c5cf1f1d336f87`.
- Local ELF symbol `SafepointSynchronize::_safepoint_counter` at symbol value
  `0x17fa938`. This is not a process virtual address; discovery must account for
  the actual mapping/load bias. Symbol availability was verified with `nm -C`,
  not inferred from an upstream source table.

The build's source declares a volatile 64-bit counter, even outside safepoints,
incremented at safepoint entry and exit. `arm_safepoint` publishes the odd value
before synchronizing Java threads. `disarm_safepoint` publishes the next even
value before restarting them. The pinned Serial collector purges unloaded class
loader metaspaces with `ClassLoaderDataGraph::purge(/*at_safepoint*/true)` during
full collection.

Exact-build references:

- https://github.com/adoptium/jdk25u/blob/520406d87195/src/hotspot/share/runtime/safepoint.hpp
- https://github.com/adoptium/jdk25u/blob/520406d87195/src/hotspot/share/runtime/safepoint.cpp
- https://github.com/adoptium/jdk25u/blob/520406d87195/src/hotspot/share/gc/serial/serialHeap.cpp

A candidate remedy captures a stable even epoch around the stack walk, carries
it through the definition/reference protocol, separates producer and frame-cache
keys by epoch, and prevents method-cache reuse across epochs. Symbolization of a
new definition must verify that the current epoch equals its captured epoch
before and after decoding. Crossing a boundary must discard tentative Java
labels rather than decode replacement metadata. An already validated immutable
definition can remain usable for its original reference: subsequent unloading
does not rewrite an earlier captured stack.

This is a proposed design, not an implemented or validated guarantee. Initial
coverage should be restricted to the exact pinned interpreter-only Serial-GC
configuration, with explicit runtime verification. Concurrent unloading and
compiled-code reclamation are separate lifetime mechanisms and require further
evidence before broadening coverage. Missing symbols, failed reads, odd/changing
epochs and unsupported configurations cannot silently enter the supported path.
Counter wrap/regression must not permit an old identity to become valid again.

Alternatives considered: full pointers/content fingerprints improve mismatch
detection but are not lifetime evidence; lifecycle uprobes could cover more
configurations but require complete reclamation-hook coverage and attachment
race handling. The epoch candidate is the smallest evidence-backed next step
for the reproduced interpreter-only Serial-GC failure. Cache capacities,
pruning, worker/deadline bounds and wrong-generation assertions remain acceptance
constraints. Useful resolution across later generations must be tested separately
from rejection correctness, so an all-unavailable result is not accepted as a
successful attribution remedy.
