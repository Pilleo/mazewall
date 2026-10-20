// Adapted from the retained actual-C characterization, attempt-2 (2026-10-18).
// Include the complete prepared production dispatcher; mock only helper boundaries.
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define TESTING_COREDUMP 1
#include "types.h"
#define OPTI_BPFDEFS_H 1
#define UNUSED __attribute__((unused))
#define __uint(name, val) int (*name)[val]
#define __type(name, val) typeof(val) *name
#define __array(name, val) typeof(val) *name[]
#define BPF_RODATA_VAR(type, name, value) type name = value;
#define SEC(name)
#define EBPF_INLINE
#define DEBUG_PRINT(...) bpf_log(__VA_ARGS__)
#define printt(...) bpf_log(__VA_ARGS__)
#define MIN(a, b) (((a) < (b)) ? (a) : (b))

struct cgo_ctx { u64 id, tp_base; };
static struct cgo_ctx diagnostic_context;
static struct cgo_ctx *__cgo_ctx = &diagnostic_context;
static void bpf_log(const char *fmt, ...);
static void *bpf_map_lookup_elem(void *map, const void *key);
static int bpf_map_update_elem(void *map, const void *key, const void *value, u64 flags);
static int bpf_map_delete_elem(void *map, const void *key);
static long bpf_probe_read_user(void *dst, int size, const void *src);
static long bpf_probe_read_kernel(void *dst, int size, const void *src);
static u64 bpf_ktime_get_ns(void);
static int bpf_perf_event_output(void *ctx, void *map, u64 flags, void *data, int size);
static int bpf_tail_call(void *ctx, void *map, int index);
static int bpf_get_current_comm(void *buf, int size);
static int bpf_get_stackid(void *ctx, void *map, u64 flags);
static unsigned cas_calls, forced_failures;
static u64 diagnostic_cas(u64 *last, u64 old, u64 next)
{
  cas_calls++;
  if (forced_failures) {
    forced_failures--;
    return ++*last; // Another allocator won this attempt.
  }
  return __sync_val_compare_and_swap(last, old, next);
}
#define __sync_val_compare_and_swap diagnostic_cas
#include "interpreter_dispatcher.ebpf.c"
#undef __sync_val_compare_and_swap

struct hotspot_procs_t { int diagnostic; } hotspot_procs;
struct go_labels_procs_t { int diagnostic; } go_labels_procs;
_Static_assert(sizeof(TraceCache) == 24 + 3072 * 8, "production key ABI");
_Static_assert(offsetof(TraceCache, frame_data) == 24, "production frame ABI");
_Static_assert(sizeof(HotspotEpochIdentity) == 16, "production epoch ABI");
enum { CPUS = 2, CACHE_LIMIT = 256, DEFINITIONS = 8 };
static PerCPURecord records[CPUS];
static TraceCache temporary_keys[CPUS], zero_key;
static u64 returned_ids[CPUS], last_id, epoch_counter;
static struct { TraceCache key; u64 value; } cache[CACHE_LIMIT];
static Trace definitions[DEFINITIONS];
static unsigned cpu, entries, emitted, hits, misses, clock_calls;
static int missing_allocator, insert_error, output_error;
static HotspotProcInfo hotspot_info;
#define REQUIRE(condition) do { if (!(condition)) { \
  fprintf(stderr, "CHECK_FAILED line=%d condition=%s\n", __LINE__, #condition); \
  exit(1); } } while (0)
static void bpf_log(const char *fmt, ...) { (void)fmt; }
static void *bpf_map_lookup_elem(void *map, const void *key)
{
  if (map == &stack_cache2correlation_id) {
    for (unsigned i = 0; i < entries; i++) {
      if (!memcmp(&cache[i].key, key, sizeof(TraceCache))) {
        hits++;
        return &cache[i].value;
      }
    }
    misses++;
    return NULL;
  }
  if (map == &hotspot_procs) return &hotspot_info;
  if (map == &apm_int_procs || map == &go_labels_procs) return NULL;
  REQUIRE(*(const u32 *)key == 0);
  if (map == &per_cpu_records) return &records[cpu];
  if (map == &trace_cache_tmp) return &temporary_keys[cpu];
  if (map == &trace_cache_zero) return &zero_key;
  if (map == &generic_params) return &returned_ids[cpu];
  if (map == &correlation_ids) return missing_allocator ? NULL : &last_id;
  REQUIRE(0 && "unexpected lookup");
  return NULL;
}
static int bpf_map_update_elem(void *map, const void *key, const void *value, u64 flags)
{
  REQUIRE(flags == BPF_ANY);
  if (map == &stack_cache2correlation_id) {
    if (insert_error) return insert_error;
    REQUIRE(entries < CACHE_LIMIT);
    memcpy(&cache[entries].key, key, sizeof(TraceCache));
    memcpy(&cache[entries++].value, value, sizeof(u64));
    return 0;
  }
  REQUIRE(map == &generic_params && *(const u32 *)key == 0);
  memcpy(&returned_ids[cpu], value, sizeof(u64));
  return 0;
}
static long bpf_probe_read_kernel(void *dst, int size, const void *src)
{ REQUIRE(size >= 0); memcpy(dst, src, (size_t)size); return 0; }
static long bpf_probe_read_user(void *dst, int size, const void *src)
{ REQUIRE(src == &epoch_counter && size == sizeof(epoch_counter)); memcpy(dst, src, size); return 0; }
static u64 bpf_ktime_get_ns(void) { clock_calls++; return 516936189570219ULL; }
static int bpf_perf_event_output(void *ctx, void *map, u64 flags, void *data, int size)
{
  REQUIRE(ctx && map == &trace_events && flags == BPF_F_CURRENT_CPU);
  REQUIRE(size == offsetof(Trace, frame_data) + records[cpu].trace.frame_data_len * sizeof(u64));
  if (output_error) return output_error;
  REQUIRE(emitted < DEFINITIONS);
  memcpy(&definitions[emitted++], data, size);
  return 0;
}
static int bpf_tail_call(void *ctx, void *map, int index) { REQUIRE(0); return -1; }
static int bpf_map_delete_elem(void *map, const void *key) { REQUIRE(0); return -1; }
static int bpf_get_current_comm(void *buf, int size) { REQUIRE(0); return -1; }
static int bpf_get_stackid(void *ctx, void *map, u64 flags) { REQUIRE(0); return -1; }
static u64 invoke(unsigned slot, u32 tid, u64 pc, u64 epoch)
{
  cpu = slot;
  unsigned other = cpu ^ 1;
  PerCPURecord saved_record = records[other];
  TraceCache saved_key = temporary_keys[other];
  u64 saved_return = returned_ids[other];
  PerCPURecord *record = get_pristine_per_cpu_record();
  REQUIRE(record == &records[cpu]);
  record->trace.pid = 621929;
  record->trace.tid = tid;
  record->trace.origin = TRACE_PROBE;
  record->trace.ktime = 516936189500000ULL;
  record->trace.correlation_id = 0;
  record->trace.hotspot_identity = (HotspotEpochIdentity){epoch,
      epoch ? HOTSPOT_EPOCH_STABLE : HOTSPOT_EPOCH_UNKNOWN, 0};
  u64 *word = push_frame(&record->state, &record->trace, FRAME_MARKER_NATIVE, 0, pc, 1);
  REQUIRE(word); word[0] = 1337;
  epoch_counter = epoch;
  returned_ids[cpu] = 0; // Actual Gadget clears the return slot before entry.
  struct pt_regs ctx = {};
  REQUIRE(unwind_stop(&ctx) == 0);
  REQUIRE(!memcmp(&records[other], &saved_record, sizeof(saved_record)));
  REQUIRE(!memcmp(&temporary_keys[other], &saved_key, sizeof(saved_key)));
  REQUIRE(returned_ids[other] == saved_return);
  return returned_ids[cpu];
}
static void reset(void)
{
  entries = emitted = hits = misses = cas_calls = clock_calls = forced_failures = 0;
  missing_allocator = insert_error = output_error = 0;
  last_id = 0;
}
int main(int argc, char **argv)
{
  REQUIRE(argc == 2);
  if (!strcmp(argv[1], "unique")) {
    for (unsigned variant = 0; variant < 3; variant++) {
      reset();
      hotspot_info.counter_address = variant ? (u64)&epoch_counter : 0;
      hotspot_info.initial_epoch = 2;
      u64 epoch = variant ? 2 : 0;
      u64 a = invoke(0, 622909, 21, epoch);
      REQUIRE(a > 0 && emitted == 1 && entries == 1);
      REQUIRE(invoke(1, 622909, 21, epoch) == a && emitted == 1 && entries == 1);
      u64 b = invoke(1, variant ? 622909 : 622910, variant == 1 ? 22 : 21, variant == 2 ? 4 : epoch);
      REQUIRE(b > 0 && a != b && emitted == 2 && entries == 2);
      REQUIRE(invoke(0, variant ? 622909 : 622910, variant == 1 ? 22 : 21, variant == 2 ? 4 : epoch) == b);
      REQUIRE(hits == 2 && misses == 2 && emitted == 2);
      for (unsigned i = 0; i < 2; i++) {
        REQUIRE(definitions[i].correlation_id == cache[i].value);
        REQUIRE(definitions[i].pid == cache[i].key.pid && definitions[i].tid == cache[i].key.tid);
        REQUIRE(!memcmp(&definitions[i].hotspot_identity, &cache[i].key.hotspot_identity, 16));
        REQUIRE(!memcmp(definitions[i].frame_data, cache[i].key.frame_data, 16));
      }
      printf("unique variant=%u ids=%llu,%llu hits=%u definitions=%u clock_calls=%u\n", variant, a, b, hits, emitted, clock_calls);
    }
    return 0;
  }
  REQUIRE(!strcmp(argv[1], "edges"));
  const u64 starts[] = {0, 1ULL << 53, 0x7ffffffffffffffeULL};
  for (unsigned i = 0; i < 3; i++) {
    reset(); last_id = starts[i];
    REQUIRE(invoke(0, 622909, 21, 0) == starts[i] + 1);
    REQUIRE(last_id == starts[i] + 1 && emitted == 1 && entries == 1 && cas_calls == 1);
    last_id = 0x7fffffffffffffffULL;
    REQUIRE(invoke(1, 622909, 21, 0) == starts[i] + 1 && emitted == 1 && cas_calls == 1);
  }
  const u64 invalid[] = {0x7fffffffffffffffULL, 1ULL << 63, ~0ULL};
  for (unsigned i = 0; i < 3; i++) {
    reset(); last_id = invalid[i];
    REQUIRE(invoke(0, 622909, 21, 0) == 0);
    REQUIRE(last_id == invalid[i] && !entries && !emitted && !cas_calls);
  }
  reset(); missing_allocator = 1;
  REQUIRE(invoke(0, 622909, 21, 0) == 0 && !entries && !emitted && !cas_calls);
  reset(); forced_failures = 32;
  REQUIRE(invoke(0, 622909, 21, 0) == 0 && cas_calls == 32 && last_id == 32 && !entries && !emitted);
  reset(); forced_failures = 31;
  REQUIRE(invoke(0, 622909, 21, 0) == 32 && cas_calls == 32 && emitted == 1);
  reset(); insert_error = -12;
  REQUIRE(invoke(0, 622909, 21, 0) == 1 && !entries && emitted == 1);
  insert_error = 0;
  REQUIRE(invoke(1, 622909, 21, 0) == 2 && last_id == 2 && entries == 1 && emitted == 2);
  reset(); output_error = -28;
  REQUIRE(invoke(0, 622909, 21, 0) == 1 && entries == 1 && !emitted);
  output_error = 0;
  REQUIRE(invoke(1, 622909, 21, 0) == 1 && last_id == 1 && !emitted);
  REQUIRE(invoke(1, 622910, 21, 0) == 2 && last_id == 2 && emitted == 1);
  REQUIRE(!clock_calls);
  puts("edges PASS: boundaries, missing, saturation, 32-CAS contention, insertion/output faults; no reset/reuse");
  return 0;
}
