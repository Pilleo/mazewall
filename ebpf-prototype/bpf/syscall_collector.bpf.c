// SPDX-License-Identifier: GPL-2.0
/*
 * Tier E WP-02: standalone syscall-entry collector (research prototype).
 *
 * Attached to raw_tp/sys_enter. Emits one ring-buffer record per syscall made
 * by the configured target TGID. Unattributed by design: this work package
 * proves the event path only; context attribution arrives in WP-03.
 *
 * Kernel floor: 5.15 (ring buffer). No CO-RE: the tracepoint's second argument
 * IS the syscall number, so no pt_regs dereference is performed.
 */

#include <linux/bpf.h>
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_tracing.h>

char LICENSE[] SEC("license") = "GPL";

struct pt_regs;

struct syscall_event {
	__u64 ktime_ns;
	__u32 tgid;
	__u32 tid;
	__s32 syscall_nr;
	__u32 pad;
};

struct {
	__uint(type, BPF_MAP_TYPE_RINGBUF);
	__uint(max_entries, 1 << 20);
} events SEC(".maps");

struct {
	__uint(type, BPF_MAP_TYPE_ARRAY);
	__uint(max_entries, 1);
	__type(key, __u32);
	__type(value, __u32); /* target TGID; 0 disables emission entirely */
} target_tgid SEC(".maps");

struct {
	__uint(type, BPF_MAP_TYPE_PERCPU_ARRAY);
	__uint(max_entries, 1);
	__type(key, __u32);
	__type(value, __u64);
} dropped_events SEC(".maps");

SEC("raw_tp/sys_enter")
int BPF_PROG(tier_e_sys_enter, struct pt_regs *regs, long id)
{
	__u64 pid_tgid = bpf_get_current_pid_tgid();
	__u32 tgid = pid_tgid >> 32;
	__u32 tid = (__u32)pid_tgid;
	__u32 key = 0;
	__u32 *target;

	target = bpf_map_lookup_elem(&target_tgid, &key);
	if (!target || *target == 0 || *target != tgid)
		return 0;

	struct syscall_event *event = bpf_ringbuf_reserve(&events, sizeof(*event), 0);
	if (!event) {
		__u64 one = 1;
		bpf_map_update_elem(&dropped_events, &key, &one, BPF_ADD);
		return 0;
	}

	event->ktime_ns = bpf_ktime_get_ns();
	event->tgid = tgid;
	event->tid = tid;
	event->syscall_nr = (__s32)id;
	event->pad = 0;
	bpf_ringbuf_submit(event, 0);
	return 0;
}
