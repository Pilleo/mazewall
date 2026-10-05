# Kernel asset digest qualification

Developer-only Python AF_ALG SHA256 probe implemented; production Java asset
admission is unchanged. No native/public bridge, helper trust shortcut, digest
cache, automatic provider fallback or runtime capability change is activated.

Actual empty/abc/SHA padding/block/64KiB/1MiB known answers agree with hashlib.
Every streaming byte is counted against the caller limit; input remains unchanged.
Original PermissionError propagates and actual algorithm/operation sockets close
on budget failure. Tests observed missing-script red then pass. Test tracking uses
a private socket factory namespace so stdlib accept's own constructor is not
monkeypatched; no successful digest is mocked.

Kernel7.0.0-34-generic supports AF_ALG on the operator host. All9files in the
68,413,372byte stripped candidate bundle hash identically to its manifest. Helper
digest335e6704ea10bc9b2ab3a0ac2d9387271babea7a04d943c2e89b8dd178b39ea2;
manifest95a8263522f1cfac127b9111b981a582a37df5bb41a0bf51952fd080758cb5a2.
Three serial fresh-process kernel/pinned interpreter pairs, uncontrolled host load
and filesystem cache. Java runs pinned Temurin25.0.4.1+1-LTS with -Xint/SerialGC;
kernel probe runs host Python against the same immutable bundle. Process/container
overhead is recorded separately; compare hash-stage durations, not startup CPU.
Raw reports/provenance: `profiler/build/reports/hotspot-kernel-digest/`.

|Repeat|Interpreter checksum ns|AF_ALG whole-bundle ns|
|---|---:|---:|
|0|33174034830|88053612|
|1|41694316340|85367342|
|2|23406705505|63195561|

Medians33.174s versus0.085s. This qualifies a promising provider on this host,
not all kernels/permissions or an end-to-end deadline guarantee. External timeout
guards the experimental process; blocking kernel calls are not a production
deadline/cleanup design.

## Required native bridge design before activation

Use existing NativeEngine networking/fileSystem with io.mazewall.ffi isolation;
no new direct downcalls in profiler. AF_ALG38/SOCK_SEQPACKET5/CLOEXEC0x80000;
sockaddr_alg88bytes (family ushort0/type14bytes2/feat uint32at16/mask at20/name64at24).
Type hash/name sha256, zero feature/mask, explicit native-endian checked fields.
accept4 obtains operation through getFdOrThrow Generic receipt. MSG_MORE0x8000
sendmsg uses checked short-send loops; final empty send then exactly32byte recv.
Both descriptors are owned before close, operation before algorithm, primary
permission failure preserved and close failures suppressed. Confined arena/native
segments never cross threads; errno captured immediately. Native nonblocking poll/
cancellation and unsupported-kernel behavior need explicit host tests/approval.

Expose only an approved @MazewallInternal platform bridge and immutable explicit
HotSpotOptions opt-in preserving old constructors/default JVM descriptors. Java
remains the default/explicit choice; EPERM/EACCES never silently fall back. Require
NIST/full-file answers, arbitrary block boundaries/short I/O, denied kernel access,
FD/arena failure retirement, native ABI/layout review, old ABI and actual installed
public matrix. This is the recorded qualification decision; no such public/native
activation is implemented by the probe plan.
