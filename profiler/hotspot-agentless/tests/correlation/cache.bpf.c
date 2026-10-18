// Test-only entry seeding; allocator, cache, layouts and send path are included unchanged.
#include "bpfdefs.h"
#include "tracemgmt.h"
// Supply one owned entry instead of the dispatcher's generated perf/kprobe entries.
#undef MULTI_USE_FUNC
#define MULTI_USE_FUNC(name)
#include "interpreter_dispatcher.ebpf.c"

// Definitions normally supplied by the other production translation units.
struct hotspot_procs_t {
  __uint(type, BPF_MAP_TYPE_HASH);
  __type(key, pid_t);
  __type(value, HotspotProcInfo);
  __uint(max_entries, 256);
} hotspot_procs SEC(".maps");
struct go_labels_procs_t {
  __uint(type, BPF_MAP_TYPE_HASH);
  __type(key, pid_t);
  __type(value, GoLabelsOffsets);
  __uint(max_entries, 1024);
} go_labels_procs SEC(".maps");
u32 with_debug_output;
u64 tpbase_offset;
const volatile u32 fixture_tgid = 0;

typedef struct FixtureInput {
  u32 pid, tid;
  u64 pc, file, epoch, token;
} FixtureInput;
typedef struct FixtureResult {
  u64 reference, trace_reference, token, calls;
  u32 cpu;
  s32 status;
} FixtureResult;
struct {
  __uint(type, BPF_MAP_TYPE_HASH);
  __uint(max_entries, 8);
  __type(key, u32);
  __type(value, FixtureInput);
} fixture_inputs SEC(".maps");
struct {
  __uint(type, BPF_MAP_TYPE_HASH);
  __uint(max_entries, 8);
  __type(key, u32);
  __type(value, FixtureResult);
} fixture_results SEC(".maps");
static u32 (*fixture_cpu)(void) = (void *)BPF_FUNC_get_smp_processor_id;

SEC("kprobe/__x64_sys_getpgid")
int cache_fixture(struct pt_regs *ctx)
{
  u64 task = bpf_get_current_pid_tgid();
  if (task >> 32 != fixture_tgid) return 0;
  u32 tid = task;
  FixtureInput *input = bpf_map_lookup_elem(&fixture_inputs, &tid);
  FixtureResult *result = bpf_map_lookup_elem(&fixture_results, &tid);
  if (!input || !result) return 0;
  result->calls++;
  result->token = input->token;
  result->cpu = fixture_cpu();
  result->status = -1;
  int zero = 0;
  GenericParam *returned = bpf_map_lookup_elem(&generic_params, &zero);
  PerCPURecord *record = get_pristine_per_cpu_record();
  if (!returned || !record) return 0;
  // Actual Gadget clears both references before synchronous unwinding.
  returned->correlation_id = 0;
  record->trace.correlation_id = 0;
  record->trace.pid = input->pid;
  record->trace.tid = input->tid;
  record->trace.ktime = 123456789;
  record->trace.origin = TRACE_PROBE;
  record->trace.offtime = 0;
  __builtin_memset(record->trace.comm, 0, sizeof(record->trace.comm));
  record->trace.hotspot_identity = (HotspotEpochIdentity){input->epoch,
      HOTSPOT_EPOCH_STABLE, 0};
  u64 *frame = push_frame(&record->state, &record->trace, FRAME_MARKER_NATIVE,
      0, input->pc, 1);
  if (!frame) return 0;
  frame[0] = input->file;
  result->status = unwind_stop(ctx);
  result->reference = returned->correlation_id;
  result->trace_reference = record->trace.correlation_id;
  return 0;
}
