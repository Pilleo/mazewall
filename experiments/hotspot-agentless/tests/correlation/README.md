# Actual native correlation allocator checks

After normal `go run ./cmd/mw-collector-tools prepare`, run from the owning source
directory (choose a new output directory every time):

```bash
python3 -B tests/correlation/run.py build/deps/profiler-PATCH_SHA256 /absolute/new/actual-c-output
```

`PATCH_SHA256` is the generated `build/patch-manifest.json` value. The runner uses
the complete prepared production dispatcher and layouts, successful helper-boundary
fixtures, two independent CPU slots and equal clocks. It records selected C/header
inputs before compilation and binary hashes before execution, then requires the
clock-allocation mutant to fail uniqueness. Missing/invalid/saturated allocator
state, 32 failed CAS attempts and publication helper faults are deterministic
host checks, not claims of real kernel errors or live JVM stacks.

`allocator.bpf.c` includes the same production map/helper header. Compile it with
the locked `dependencies.lock.json` Gadget builder's Clang (`-c -g -O2 -target bpf
-mcpu=v2`, include the prepared `support/ebpf` and architecture Linux headers).
With the resulting object in `MW_CORRELATION_OBJECT`, explicitly run:

```bash
go test -buildvcs=false -modfile build/experiment.go.mod -tags=mw_kernel -v -run '^TestKernelSharedCorrelationAllocator$' ./internal/qualification/correlation
go test -buildvcs=false -modfile build/experiment.go.mod -tags=mw_kernel -v -run '^TestKernelCorrelation' go.opentelemetry.io/ebpf-profiler/tracer
```

These commands require native BPF privileges and fail rather than skip when invoked.
Use the locked Go image and an offline/read-only existing module cache for receipted
qualification. Record selected sources/images/compiler bytes before building and
object/test-binary hashes before execution. The kernel test pins temporary calling
threads to distinct allowed CPUs, checks actual helper-reported CPUs/map IDs/layout,
and checks close/affinity restoration. Legitimate bounded-contention zero results
are counted separately from unique positives; interrupted test-run calls may consume
unobserved IDs, so numeric density is not an invariant.

The loader test executes real `loadAllMaps`/`rewriteMaps` on the allocator/LRU and
inspects both actual embedded unwinder specifications. It does not attach the
entire tracer. The allocator-only XDP wrapper does not qualify JVM walking,
transport or the dispatcher's kernel-read helpers. Task2's actual eight public-native
cases and reproducible helper/OCI supply JVM coupling.

## Full cache/publication fixture (optional, Linux-amd64)

`cache.bpf.c` includes the complete prepared production dispatcher, `unwind_stop`,
allocator, `send_trace` and layouts unchanged. Only generated multi-use entries are
suppressed in favor of one TGID-filtered `__x64_sys_getpgid` kprobe. The fixture
supplies the extern HotSpot/Go-label maps with their real layouts/capacities; those
maps and the APM map remain empty. It calls real `get_pristine_per_cpu_record` and
`push_frame`, clearing both references like Gadget. Task/epoch/native-frame inputs
are **synthetic valid unwind-stop entry data**, not live JVM metadata or proof of
one task running on two CPUs. No helpers or allocator/cache algorithm are mocked.

Explicit commands from the owning source root after normal preparation (set `OUT`
to a new owned absolute directory; `ROOT`, `H`, `P`, `BUILDER`, `GO_IMAGE` are the
checkout, source root, manifest patch SHA and locked dependency image references):

```bash
export DOCKER_HOST=unix:///var/run/docker.sock MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock
# Retain image-inspect, actual compiler version+hashes, clang -M and Go list
# -deps -test -json selection receipts BEFORE builds, as in the evidence.
docker --host "$DOCKER_HOST" run --rm --pull=never --network=none --user "$(id -u):$(id -g)" \
  --mount "type=bind,src=$ROOT,dst=$ROOT,readonly" --mount "type=bind,src=$OUT,dst=/out" \
  --entrypoint clang "$BUILDER" -g -O2 -target bpf -mcpu=v2 -D__x86_64__ -D__x86_64 \
  -I"$H/build/deps/profiler-$P/support/ebpf" -c "$H/tests/correlation/cache.bpf.c" -o /out/cache.o
docker --host "$DOCKER_HOST" run --rm --pull=never --network=none --user "$(id -u):$(id -g)" \
  --mount "type=bind,src=$ROOT,dst=$ROOT,readonly" --mount "type=bind,src=$OUT,dst=/out" \
  --mount type=bind,src=/home/leanid/go/pkg/mod,dst=/home/leanid/go/pkg/mod,readonly --workdir "$H" \
  -e GOMODCACHE=/home/leanid/go/pkg/mod -e GOCACHE=/out/go-cache -e GOPROXY=off -e GOSUMDB=off \
  -e GOTOOLCHAIN=local -e CGO_ENABLED=0 -e GOFLAGS=-buildvcs=false --entrypoint /usr/local/go/bin/go "$GO_IMAGE" \
  test -buildvcs=false -modfile build/experiment.go.mod -tags=mw_kernel -c \
  -o /out/cache.test ./internal/qualification/correlation
sha256sum "$OUT/cache.o" "$OUT/cache.test" # Retain BEFORE execution.
docker --host "$DOCKER_HOST" run --rm --pull=never --network=none --privileged --pid=host \
  --mount "type=bind,src=$ROOT,dst=$ROOT,readonly" --mount "type=bind,src=$OUT,dst=/out,readonly" \
  -e MW_CORRELATION_CACHE_OBJECT=/out/cache.o --entrypoint /out/cache.test "$GO_IMAGE" \
  -test.v -test.count=1 -test.timeout=60s -test.run='^TestKernelCorrelationCachePublication$'
```

The host PID namespace makes userspace TGID/TIDs match BPF helper identities.
Select only this test for `MW_CORRELATION_CACHE_OBJECT`; the older allocator test
separately needs `MW_CORRELATION_OBJECT`. Explicit native calls fail, never skip or
fall back, on load/attach/permission/affinity/transport errors.

The test checks actual map IDs/specs/program bindings and helper-reported CPUs,
repeat hits, concurrent distinct/equal keys, actual LRU256 pressure, a membership-
proven victim, fresh republication without reset, retained exact old definitions
and native saturation (forward-only test seed, final allocation, eight hits,
zero/no-definition miss). Every logical packet byte is compared using production
BTF offsets; complete raw perf samples including padding are logged.

The fixture admits376 calls; its callback queue is32 and native perf remains
524288 bytes/possible CPU. Nine real packets queue behind a channel-held reader.
Workers quiesce/restore affinity and the link detaches before Flush. The reader
joins while a callback is still held; that callback then joins before final loss/
count/cache sampling. Every close is checked. Actual map/program IDs must disappear
within one shared2s kernel-reclamation budget (1ms polling); other explicit waits
are2s and the native test has a60s outer deadline. ELF data is initialized before
load without automatic live Variable mmap handles that otherwise need GC cleanup.

This is finite supported synchronous kprobe publication/eviction/drain evidence.
The loader's perf-typed entry shares these maps but is not actively attached in
the supported profile. Mixed active perf/NMI nesting, arbitrary entry combinations,
exhaustive schedules, scratch leases and JVM metadata lifetime remain unqualified.
Host insertion/output faults remain helper-boundary controls, not observed native
ENOMEM/permission failures. See [Task3 evidence](../../../../docs/plans/2026-10-18-hotspot-correlation-allocator-evidence.md#task3-finite-native-cache-publication-and-drain).
