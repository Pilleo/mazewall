# Proposed native asset digest ownership and deadline contract

Status: **not_authorized**. This is a proposal, not an implemented bridge, public
API or activation permission. Java remains the default. Native activation is the
hard approval boundary: new bridge/selection/public ownership implementation needs
explicit approval first. Planning and qualification do not cross that boundary.
A rejected provider leaves Java working.

## Required boundary table

|Boundary|Required outcome|
|---|---|
|Provider selection|Java default; native only by immutable explicit operator opt-in; reject unknown selection|
|AF_ALG decision|Record current upstream deprecation, actual host restrictions and operator accept/reject decision; old85ms result is insufficient|
|socket/bind|Existing NativeEngine only; AF_ALG38/SEQPACKET5/CLOEXEC0x80000; sockaddr_alg88bytes, ushort family0, type14bytes2, uint32feature16/mask20, name64bytes24|
|operation acquisition|accept4 CLOEXEC actual owned Generic descriptor; source descriptor acquired independently; no integer minted as a receipt|
|streaming|Every actual source byte counted, bounded65536chunks, checked short send, MSG_MORE0x8000, final empty send, exactly32byte digest|
|native arena|Created/used/closed on its one owning platform worker; no confined segment crosses threads|
|error|Immediate captured errno; preserve original EPERM/EACCES/read/integrity failure and independent close failures|
|deadline|Monotonic deadline checked at every I/O boundary; nonblocking flags/poll do not claim hard CPU/file-I/O interruption|
|pending ownership|Deadline/interrupt returns failed admission with actual worker/FD owner retained until join/close; no collector launched and no false retired state|
|retirement|Source/operation/algorithm actually closed; worker joined; retries retire resources only, never promote a failed validation|
|public compatibility|Approved additive @MazewallInternal platform bridge and HotSpotOptions selection factory preserve all old constructor/default descriptors and Java default|
|integrity|One common path/manifest/OCI verifier; no digest cache or verification delegated to the unverified helper|

## Current guarantees and sources

The internal [digest boundary](../../profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotAssetDigest.kt)
returns a copied 32-byte digest and actual streamed count, bounded by the remaining
aggregate allowance (ceiling 536,870,912 bytes). Java opens a fresh stream and hashes
all accepted bytes. [HotSpotAssets](../../profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotAssets.kt)
keeps path, manifest, checksums, ownership and complete transitive OCI verification
common. [HotSpotBackend](../../profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotBackend.kt)
loads assets before target identity and collector acquisition. No native selection
exists today; private injection is not an operator API.

[NativeEngine](../../platform/src/main/kotlin/io/mazewall/platform/native/NativeEngine.kt)
is internal and accepts opaque ManagedSegment, not public FFM objects. Existing
methods provide networking.socket/bind/accept4/sendmsg/recv, fileSystem.open/close,
memory.read and raw.poll. SyscallResult<Long, Unhandled> is a normalized carrier:
[SyscallInvoker](../../platform/src/main/kotlin/io/mazewall/ffi/internal/SyscallInvoker.kt)
uses C int downcalls for socket/bind/accept4/close and captures errno immediately;
read/send/recv use Linux-amd64 ssize_t (long). Normalizing to Long does not widen
a C int ABI.

[NativeDescriptorReceipt](../../platform/src/main/kotlin/io/mazewall/platform/fd/NativeDescriptorReceipt.kt)
is single-use acquisition evidence attached at the native boundary to a successful
acquisition. [getFdOrThrow](../../platform/src/main/kotlin/io/mazewall/platform/native/LinuxNative.kt)
consumes it; a synthetic Success(fd) lacks a receipt. Raw generic creates Unowned;
adopt/replace fail. [FileDescriptor](../../platform/src/main/kotlin/io/mazewall/platform/fd/FileDescriptor.kt)
guards reuse with shared lifecycle/generation state and retires one close attempt.
These mechanisms are current, not evidence of a native digest provider using them.

## Proposed acquisition and streaming — approval required

One admission retains one actual platform worker and its captured
LinuxNative.snapshot(); no per-file worker fan-out, executor queue or increased
enrichment/pending budgets. Before start, the admission owner records the actual
Thread and immutable monotonic deadline. Start failure rejects admission without
fictional FDs. The worker alone creates NativeArena.ofConfined().use, allocates
paths/sockaddr/I/O/message metadata and closes that arena. The entire common
asset load/manifest/OCI verifier runs on this same admission worker, calling the
worker-scoped digest capability; bounding a caller-side wait for digest alone
would leave common file I/O outside the owned deadline. Only copied heap
digest/count results, immutable errors and synchronized owner snapshots cross
threads; no confined segment or arena does.

For each asset, on this worker:

1. networking.socket(38, 5 or 0x80000, 0) acquires the algorithm. Consume that actual
   result once with getFdOrThrow(..., Generic). Bind hash/sha256, zero feature/mask,
   length 88. Denial is failure, never an implicit Java fallback.
2. networking.accept4(algorithm, ManagedSegment.NULL, ManagedSegment.NULL,
   0x80000) independently acquires the operation; consume its own receipt as
   FileDescriptor<Generic, Open, Owned>. Existing NativeTransfer.socketAddress
   permits this null pair. Task 2's address-requesting Go wrapper returned
   ECONNABORTED; its raw SYS_ACCEPT4 null-address correction was not permission
   fallback. Do not repeat the broken peer-address request.
3. Independently open the validated source read-only/CLOEXEC/NOFOLLOW via
   fileSystem.open with typed OpenFlags and consume that result's receipt. Source,
   operation and algorithm are distinct owned descriptors. Preserve common checks
   and the existing trusted installation assumption; do not claim atomic protection
   against post-verification operator mutation.
4. memory.read uses at most 65,536 bytes per call. Validate maximum in
   0..536870912, counts and count <= maximum - total before accumulation/send.
   Positive short reads are data; zero is EOF; preserve original read errors.
   Count actual streamed bytes, not stat size. Budget detection may read ahead one
   bounded buffer, but excess bytes never enter the hash.
5. sendmsg uses a bounded iovec with MSG_MORE=0x8000. Advance on positive short
   sends; reject zero progress/out-of-range counts; retain syscall errors. At EOF
   send a genuinely empty message with flags zero, requiring result zero. recv
   requests 32 bytes and must return exactly 32; reject short digest results.
   Publish copied digest/count only after successful cleanup and deadline checks.

### ABI/layout carriers and authorities

Linux UAPI [if_alg.h](https://raw.githubusercontent.com/torvalds/linux/master/include/uapi/linux/if_alg.h)
and retained Task 2 installed/running-kernel headers define:

|Field|Offset|Width / proposed FFM carrier|
|---|---:|---|
|salg_family|0|16 bits, JAVA_SHORT, unsigned value 38|
|salg_type|2|14 JAVA_BYTE, NUL-terminated hash|
|salg_feat|16|32 bits, JAVA_INT, zero|
|salg_mask|20|32 bits, JAVA_INT, zero|
|salg_name|24|64 JAVA_BYTE, NUL-terminated sha256|

Size 88, alignment 4, native byte order; fixed sockaddr_alg suffices for these
names (not sockaddr_alg_new). No field uses JAVA_LONG. C int FD/flag/return and
socklen_t carriers are 32-bit, pointers ADDRESS, Linux-amd64 size_t/ssize_t 64-bit.
Reuse and verify existing message/iovec layouts behind FFM, not profiler copies.
These are proposed requirements, not installed layouts or a new layout test pass.

Follow the [FFM safety guide](../../.agents/skills/ffm_safety/SKILL.md),
[NativeArena](../../platform/src/main/kotlin/io/mazewall/ffi/memory/NativeArena.kt)
and official [JDK 25 Arena contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Arena.html):
confined memory is accessible/closeable only by its creating thread. Downcalls
capture errno and read it immediately before another native call; retain numeric
and symbolic errno, operation context and independent failure causes.

## Proposed deadline and real pending owner — approval required

Use System.nanoTime() elapsed-time arithmetic with a validated duration, one
deadline across admission rather than a fresh allowance per file/retry. Proposed
opt-in factory takes an explicit admission timeout capped at the existing
10-second readiness ceiling; no extra readiness/session/stop/enrichment budget.
Check deadline/cancellation before and after every acquisition, bind, read, send,
receive, poll and cleanup boundary and before publishing success/collector launch.
Common manifest/OCI work must participate in the same admission budget: bounding
only digest work is not bounded complete admission.

Nonblocking socket operation (SOCK_NONBLOCK / MSG_DONTWAIT) and raw.poll can bound
readiness waiting by remaining time, with safe C int millisecond rounding/clamping.
EINTR/EAGAIN handling checks the same deadline/cancellation. Neither these flags
nor poll guarantee interruption of kernel crypto CPU work, file open/read,
scheduling or close. Thread.interrupt, Future cancellation and external process
timeouts do not prove physical retirement. This design makes no hard native
syscall-interruption guarantee.

Proposed closed lifecycle: Running(actual thread, acquired owners) becomes
Succeeded(copied result, joined worker), FailedRetired(causes), or
FailedPending(actual owner, causes). Timeout/interrupt permanently latches failure,
requests cancellation and returns failed admission within the caller's wait bound.
A completion race cannot publish success after that latch. A blocked worker is
retained with real acquisition generations and last phase inspectable; represent
acquisition-in-flight without inventing an FD before return. Preserve caller
interrupt status as appropriate. No collector is launched for failed admission.

The supervisor never closes another thread's confined arena or blindly closes an
FD in active worker I/O. After any stalled call returns, the worker checks failure,
stops digest work, closes resources and arena, then exits. Retain the actual thread
until bounded join observes termination. Pending state derives from this lifecycle,
not arbitrary source Booleans or synthetic owner receipts. Do not start replacement
workers while a failed owner remains pending; no unbounded stalled admission queue.
An environment requiring guaranteed physical retirement by a hard deadline cannot
qualify this in-process candidate from the current evidence.

## Proposed cleanup and additive public shapes — approval required

Unwind source, operation, algorithm independently in reverse acquisition order,
including partial acquisition failures. Preserve original permission/read/integrity/
timeout/interrupt failure as primary; attach every independent cleanup error.
A close-only failure rejects admission. Inspect captured engine fileSystem.close's
SyscallResult explicitly: current FileDescriptor.closeResource/convenience close
discards it, so use alone is insufficient for checked cleanup here.
RealNativeEngine.close claims/retires the generation before dispatch; never retry
that integer after EINTR or any attempted close (reuse hazard). Separate close-error
evidence from observed worker termination/retirement: a retired token or attempted
close is not successful-close evidence. Retry unfinished worker-owned cleanup/join
only, not the already attempted close generation; never promote failed validation.

Proposed minimum cross-module shape: additive @MazewallInternal platform bridge,
tentatively NativeAssetDigests.startAdmission(deadline, validation), returning an
opaque AdmissionOwner<T> with immutable snapshot, bounded await and retirement
operations. The validation callback runs the existing common profiler verifier
on that worker and receives only a worker-scoped DigestAccess.digest(path,
maximum) capability. The adapter implements the private digest boundary, returning
copied bytes/count; the callback returns the fully validated immutable assets.
Neither callback nor capability exposes NativeEngine/FD/FFM. Reject capability use
from another thread/after admission; do not let it queue new workers. Publish T only
after cleanup, successful deadline arbitration and worker join. This lets the
caller return a real pending owner even if common file I/O stalls. Names/signatures
require approval and ABI review, not implementation in this task.

Proposed public selection: closed enum JAVA / AF_ALG_SHA256, additive immutable
HotSpotOptions.withAssetDigestProvider(existingOptions, selection, admissionTimeout)
factory copying all old fields. Native requires explicit operator choice, approved
contract and valid duration; unknown external selection rejects before acquisition.
Keep the existing primary parameter list and Java default/default-mask behavior.
No mutable global switch or fallback after permission/integrity/native failure.

Today [HotSpotProfilingException](../../profiler/src/main/kotlin/io/mazewall/profiler/api/HotSpotProfilingSession.kt)
owns only HotSpotCollectorProcess?, so pendingOwnership/retryCleanup cannot retain
an admission worker. Proposed internal sealed cleanup owner has Collector(process)
and Admission(actual bridge owner) variants, with an additive constructor/factory
mapping to the existing public exception while retaining its old constructor.
Admission failure has partialResult=null, original causes/errnos and real pending
state through pendingOwnership; retryCleanup performs bounded retirement only.
No fake collector, policy-complete result or supplied Boolean can stand in for
that owner. This exception adaptation is a new shape needing approval.

Current JVM descriptors inspected with javap -s; preserve verbatim:

```text
HotSpotOptions primary constructor:
(Ljava/nio/file/Path;Ljava/util/List;Ljava/time/Duration;Ljava/time/Duration;Ljava/time/Duration;II)V
HotSpotOptions Kotlin default constructor:
(Ljava/nio/file/Path;Ljava/util/List;Ljava/time/Duration;Ljava/time/Duration;Ljava/time/Duration;IIILkotlin/jvm/internal/DefaultConstructorMarker;)V
HotSpotProfilingException constructor (Kotlin internal, JVM-visible):
(Ljava/lang/String;Ljava/lang/Throwable;Lio/mazewall/profiler/api/ProfilingResult;Lio/mazewall/profiler/internal/hotspot/HotSpotCollectorProcess;Ljava/util/List;Ljava/lang/String;J)V
HotSpotProfilingException.getPendingOwnership: ()Z
HotSpotProfilingException.retryCleanup: ()V
HotSpotAssets.Companion.load legacy overloads:
(Ljava/nio/file/Path;)Lio/mazewall/profiler/internal/hotspot/HotSpotAssets;
(Ljava/nio/file/Path;Lio/mazewall/profiler/internal/hotspot/HotSpotAssetTimings;)Lio/mazewall/profiler/internal/hotspot/HotSpotAssets;
```

Also preserve every existing option getter and exception diagnostic/partial-result/
errno getter. Before promotion require Kotlin/legacy ABI checks and old compiled
constructor/default-call fixtures, including Java-default selection. Adding a
defaulted primary parameter would break the recorded linkage.

## Native-activation approval record

|Field|Record requiring review|
|---|---|
|provider name|Proposed AF_ALG SHA256 (AF_ALG_SHA256); production Java unchanged.|
|kernel restrictions|Observed Linux 7.2.10-x64v3-xanmod1 amd64, UID/GID1000, real hash calls succeed, hash modules present. af_alg_restrict absent/ENOENT, not zero. Upstream 7.3.0-rc7 docs retrieved 2026-10-17 call AF_ALG insecure/deprecated; future restrictions are separate from host support. SELinux status unestablished (attr read EINVAL). Operator dependency/restriction accept/reject outstanding; no policy changes authorized.|
|ABI/layout carriers|Proposed 88-byte sockaddr and C int/uint16/uint32/pointer/ssize_t carriers above; existing APIs inspected, no native digest bridge/layout implemented or qualified.|
|source/operation/algorithm acquisition owner|Proposed single real platform worker, independently consumed open/accept4/socket receipts as Generic/Open/Owned, worker-only confined arena. Go trace observes FD6/5/4 closure, not JVM bridge ownership proof.|
|pending-owner exception/public retry shape|Proposed opaque platform admission owner and sealed collector/admission exception adapter above; retain old descriptors and pendingOwnership/retryCleanup. Current exception lacks admission owner support; failed validation never promoted by retry.|
|actual short-I/O/permission/cleanup/timeout tests|Recorded known answers and success/budget/open/read cleanup traces valid. Actual short-I/O, independent close-error and EPERM/EACCES injection, stalled syscall retirement, JVM deadline races and owner retry NOT proved. Require these tests plus layout/confinement, bounded pending/join/retry/no-launch checks and common complete OCI admission timing before activation. Historical binary identities verified, exact source-byte linkage INFERRED/UNVERIFIED; future qualification needs contemporaneous source/build receipts.|
|old JVM descriptors|Constructor/default/exception/load descriptors above plus existing getters; Task 1 Kotlin/legacy ABI pass reused, current javap inspected. New shapes need fresh ABI and old-client fixtures before promotion.|
|reviewer/operator decision|**not_authorized**. Submitted for independent controller review; only operator changes activation decision after actual evidence and explicit provider acceptance. No native/public implementation or activation authorized here.|

See [qualification evidence](2026-10-17-hotspot-admission-next-evidence.md) for
measurements and missing proof. The
[performance issue](../internals/backlog/issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-.md)
stays open for complete admission latency and dense public readout.
