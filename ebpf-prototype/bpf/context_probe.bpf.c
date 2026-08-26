// SPDX-License-Identifier: GPL-2.0
/*
 * Tier E WP-03: context attribution programs (Gate G0a).
 *
 *   uprobe(mazewall_context_marker)  ->  task_storage[current] = ctx
 *   raw_tp/sys_enter                 ->  emit {tgid, tid, nr, ctx} if attributed
 *
 * Both programs run ON the traced task, so the write and all later reads are
 * serialized by construction — no cross-context synchronization exists here,
 * by design (design doc §4.1). Missing/zero storage = UNKNOWN = no event.
 */

#include <stdbool.h>
#include <linux/bpf.h>
/* Full pt_regs layout for PT_REGS_* access (pre-vmlinux.h prototype;
 * CO-RE relocation is not required by this program). Must precede
 * bpf_tracing.h / usdt.bpf.h. */
#include <asm/ptrace.h>
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_tracing.h>
#include <bpf/usdt.bpf.h>

char LICENSE[] SEC("license") = "GPL";

struct context_event {
	__u64 ktime_ns;
	__u32 tgid;
	__u32 tid;
	__s32 syscall_nr;
	__u32 context_id;
};

struct {
	__uint(type, BPF_MAP_TYPE_TASK_STORAGE);
	__uint(map_flags, BPF_F_NO_PREALLOC);
	__type(key, int);
	__type(value, __u32);
} context_storage SEC(".maps");

struct {
	__uint(type, BPF_MAP_TYPE_RINGBUF);
	__uint(max_entries, 1 << 20);
} context_events SEC(".maps");

struct {
	__uint(type, BPF_MAP_TYPE_ARRAY);
	__uint(max_entries, 1);
	__type(key, __u32);
	__type(value, __u32); /* target TGID */
} target_tgid SEC(".maps");

struct {
	__uint(type, BPF_MAP_TYPE_PERCPU_ARRAY);
	__uint(max_entries, 1);
	__type(key, __u32);
	__type(value, __u64);
} dropped_events SEC(".maps");

/* 0 = uprobe marker entries, 1 = attributed sys_enter events */
struct {
	__uint(type, BPF_MAP_TYPE_PERCPU_ARRAY);
	__uint(max_entries, 2);
	__type(key, __u32);
	__type(value, __u64);
} hit_counters SEC(".maps");

SEC("uprobe")
int BPF_UPROBE(tier_e_on_marker, unsigned int context_id)
{
	__u32 hk0 = 0;
	__u64 one = 1;
	bpf_map_update_elem(&hit_counters, &hk0, &one, BPF_ADD);
	__u32 value = context_id;
	struct task_struct *task = (struct task_struct *)bpf_get_current_task_btf();
	__u32 *slot = bpf_task_storage_get(&context_storage, task, &value,
					   BPF_LOCAL_STORAGE_GET_F_CREATE);
	if (slot)
		*slot = value;
	return 0;
}

/* G0b variant: same storage write, attached semantically to
 * mazewall:context_switch via the ELF .note.stapsdt descriptor instead of a
 * raw symbol offset. Argument location is resolved by bpf_usdt machinery. */
SEC("usdt")
int BPF_USDT(tier_e_on_marker_usdt, unsigned int context_id)
{
	__u32 value = context_id;
	struct task_struct *task = (struct task_struct *)bpf_get_current_task_btf();
	__u32 *slot = bpf_task_storage_get(&context_storage, task, &value,
					   BPF_LOCAL_STORAGE_GET_F_CREATE);
	if (slot)
		*slot = value;
	return 0;
}

SEC("raw_tp/sys_enter")
int BPF_PROG(tier_e_sys_enter_ctx, struct pt_regs *regs, long id)
{
	__u64 pid_tgid = bpf_get_current_pid_tgid();
	__u32 tgid = pid_tgid >> 32;

	__u32 key = 0;
	__u32 *target = bpf_map_lookup_elem(&target_tgid, &key);
	if (!target || *target == 0 || *target != tgid)
		return 0;
	{
		__u32 hk1 = 1;
		__u64 one_v = 1;
		bpf_map_update_elem(&hit_counters, &hk1, &one_v, BPF_ADD);
	}

	struct task_struct *task = (struct task_struct *)bpf_get_current_task_btf();
	__u32 *stored = bpf_task_storage_get(&context_storage, task, NULL, 0);
	if (!stored || *stored == 0)
		return 0; /* UNKNOWN is data, not an event (invariant 3) */

	struct context_event *event =
		bpf_ringbuf_reserve(&context_events, sizeof(*event), 0);
	if (!event) {
		__u64 one = 1;
		__u32 drop_key = 0;
		bpf_map_update_elem(&dropped_events, &drop_key, &one, BPF_ADD);
		return 0;
	}

	event->ktime_ns = bpf_ktime_get_ns();
	event->tgid = tgid;
	event->tid = (__u32)pid_tgid;
	event->syscall_nr = (__s32)id;
	event->context_id = *stored;
	bpf_ringbuf_submit(event, 0);
	return 0;
}
