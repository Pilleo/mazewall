// Test-only runnable context; the map and allocator are the production header.
#include <linux/bpf.h>
#include <bpf/bpf_helpers.h>
typedef __u32 u32;
typedef __u64 u64;
#define EBPF_INLINE __always_inline
#include "correlation_ids.h"

SEC("xdp") int allocator_test(struct xdp_md *ctx)
{
  void *data = (void *)(long)ctx->data;
  void *end = (void *)(long)ctx->data_end;
  if (data + 16 > end) return XDP_ABORTED;
  u64 *result = data;
  result[0] = next_correlation_id();
  result[1] = bpf_get_smp_processor_id();
  return XDP_PASS;
}
char _license[] SEC("license") = "GPL";
