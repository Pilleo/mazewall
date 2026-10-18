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
go test -buildvcs=false -modfile build/experiment.go.mod -tags=mw_kernel -v ./internal/qualification/correlation
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
entire tracer. The runnable wrapper uses XDP test context: it does not qualify live
JVM walking, transport, cache-publication interleavings, kprobe attachment or active
perf/NMI nesting. Full public capture and reproducible helper/OCI remain Task2.
