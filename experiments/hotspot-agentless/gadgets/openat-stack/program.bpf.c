// SPDX-License-Identifier: (LGPL-2.1 OR BSD-2-Clause)
// Uses Inspektor Gadget's stack and event-buffer APIs.
#include <vmlinux.h>
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_core_read.h>
#include <bpf/bpf_tracing.h>
#include <gadget/types.h>
#include <gadget/buffer.h>
#include <gadget/macros.h>
#include <gadget/user_stack_map.h>

const volatile __u32 target_pid = 0;
GADGET_PARAM(target_pid);
const volatile __u64 target_start_ticks = 0;
GADGET_PARAM(target_start_ticks);

struct event {
	__u64 capture_boot_ns;
	__u64 sequence;
	__u64 process_start_ticks;
	__u64 cgroup_id;
	__u32 cpu;
	__u32 tgid;
	__u32 tid;
	__s32 dirfd;
	__u64 flags;
	__u64 mode;
	__s32 argument_read;
	__s32 register_read;
	__u32 path_truncated;
	char filename[256];
	struct gadget_user_stack ustack;
};

struct {
	__uint(type, BPF_MAP_TYPE_PERCPU_ARRAY);
	__uint(max_entries, 1);
	__type(key, __u32);
	__type(value, __u64);
} sequences SEC(".maps");

GADGET_TRACER_MAP(events, 1024 * 256);
GADGET_TRACER(openat, events, event);

SEC("kprobe/__x64_sys_openat")
int capture_openat(struct pt_regs *ctx)
{
	__u64 pid_tgid = bpf_get_current_pid_tgid();
	if ((pid_tgid >> 32) != target_pid)
		return 0;
	// /proc starttime is USER_HZ=100 on the supported x86-64 Linux ABI.
	// Compare the process leader, not the current worker's own birth time.
	struct task_struct *task = (struct task_struct *)bpf_get_current_task();
	struct task_struct *leader = BPF_CORE_READ(task, group_leader);
	__u64 process_start_ticks = BPF_CORE_READ(leader, start_boottime) / 10000000ULL;
	if (target_start_ticks && process_start_ticks != target_start_ticks)
		return 0;
	__u32 zero = 0;
	__u64 *seq = bpf_map_lookup_elem(&sequences, &zero);
	if (!seq)
		return 0;
	__u64 occurrence = __sync_fetch_and_add(seq, 1) + 1;
	struct event *event = gadget_reserve_buf(&events, sizeof(*event));
	if (!event)
		return 0;
	__builtin_memset(event, 0, sizeof(*event));
	event->sequence = occurrence;
	event->process_start_ticks = process_start_ticks;
	event->cgroup_id = bpf_get_current_cgroup_id();
	event->cpu = bpf_get_smp_processor_id();
	event->tgid = pid_tgid >> 32;
	event->tid = (__u32)pid_tgid;
	event->capture_boot_ns = bpf_ktime_get_boot_ns();
	// Gadget's builder packages both architectures. The runtime preflight
	// rejects non-x86 hosts, and this probe symbol is x86-specific as well.
#if defined(__TARGET_ARCH_x86)
	// The wrapper's first argument points to userspace syscall registers.
	struct pt_regs *sys_regs = (struct pt_regs *)PT_REGS_PARM1(ctx);
	__u64 dirfd = 0;
	const char *path = NULL;
#define READ_REGISTER(dst, field) do { \
	int rc = BPF_CORE_READ_INTO(dst, sys_regs, field); \
	if (rc && !event->register_read) event->register_read = rc; \
} while (0)
	READ_REGISTER(&dirfd, di);
	READ_REGISTER(&event->flags, dx);
	READ_REGISTER(&event->mode, r10);
	READ_REGISTER(&path, si);
#undef READ_REGISTER
	event->dirfd = (__s32)dirfd;
	event->argument_read = event->register_read ? event->register_read :
		bpf_probe_read_user_str(event->filename, sizeof(event->filename), path);
	// An exactly full read is conservatively "possibly truncated".
	event->path_truncated = event->argument_read == sizeof(event->filename);
#else
	event->argument_read = -95; // EOPNOTSUPP; no ARM syscall ABI is implemented.
	event->register_read = -95;
#endif
	// The upstream entry can exit during process discovery without producing a
	// reference. Never inherit the previous event's per-CPU return value.
	struct generic_param *params = bpf_map_lookup_elem(&otel_generic_params, &zero);
	if (params)
		params->correlation_id = 0;
	gadget_get_user_stack_from_kprobe(ctx, &event->ustack);
	gadget_submit_buf(ctx, &events, event, sizeof(*event));
	return 0;
}

char LICENSE[] SEC("license") = "Dual BSD/GPL";
