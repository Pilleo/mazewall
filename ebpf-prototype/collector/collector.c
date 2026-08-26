// SPDX-License-Identifier: GPL-2.0
/*
 * Tier E WP-02: userspace loader/reader for the syscall-entry collector.
 *
 * Privileged component: loads the BPF object, pins the target TGID into the
 * filter map, drains the ring buffer, and reports drop accounting. Refuses to
 * run without CAP_BPF/CAP_SYS_ADMIN-class privileges rather than degrading.
 */

#include <bpf/bpf.h>
#include <bpf/libbpf.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t g_stop;
static int g_summary;
static unsigned long long nr_counts[512];
static unsigned long long nr_total;

static void on_signal(int sig)
{
	(void)sig;
	g_stop = 1;
}

static double now_monotonic_s(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

static long parse_long(const char *arg, const char *what)
{
	char *end = NULL;
	long value = strtol(arg, &end, 10);
	if (end == arg || *end != '\0') {
		fprintf(stderr, "invalid %s: '%s'\n", what, arg);
		exit(2);
	}
	return value;
}

static int on_event(void *ctx, void *data, size_t size)
{
	(void)ctx;
	(void)size;
	const struct {
		unsigned long long ktime_ns;
		unsigned int tgid;
		unsigned int tid;
		int syscall_nr;
	} __attribute__((packed)) * event = data;

	if (g_summary) {
		nr_total++;
		if (event->syscall_nr >= 0 &&
		    (unsigned)event->syscall_nr < sizeof(nr_counts) / sizeof(nr_counts[0]))
			nr_counts[event->syscall_nr]++;
		return 0;
	}
	printf("%u/%u %d\n", event->tgid, event->tid, event->syscall_nr);
	return 0;
}

static void print_summary(void)
{
	fprintf(stderr, "[tier-e] events=%llu per-syscall:", nr_total);
	for (unsigned nr = 0; nr < sizeof(nr_counts) / sizeof(nr_counts[0]); nr++) {
		if (nr_counts[nr])
			fprintf(stderr, " %llu*%u", nr_counts[nr], nr);
	}
	fprintf(stderr, "\n");
}

int main(int argc, char **argv)
{
	long target_tgid = 0;
	double duration_s = 5.0;

	for (int i = 1; i < argc; i++) {
		if (strcmp(argv[i], "--pid") == 0 && i + 1 < argc) {
			target_tgid = parse_long(argv[++i], "--pid");
		} else if (strcmp(argv[i], "--duration") == 0 && i + 1 < argc) {
			duration_s = (double)parse_long(argv[++i], "--duration");
		} else if (strcmp(argv[i], "--summary") == 0) {
			g_summary = 1;
		} else {
			fprintf(stderr,
				"usage: tier_e_collector --pid <tgid> [--duration <seconds>] [--summary]\n");
			return 2;
		}
	}
	if (target_tgid <= 0) {
		fprintf(stderr, "a positive --pid <tgid> filter is mandatory\n");
		return 2;
	}

	if (geteuid() != 0) {
		fprintf(stderr,
			"refusing to start: loading tracing BPF requires capabilities in the "
			"initial user namespace (run via scripts/run_collector.sh as root)\n");
		return 1;
	}

	struct bpf_object *object = bpf_object__open_file("build/syscall_collector.bpf.o", NULL);
	if (libbpf_get_error(object)) {
		object = NULL;
		fprintf(stderr, "failed to open build/syscall_collector.bpf.o "
				"(run make first)\n");
		return 1;
	}
	if (bpf_object__load(object)) {
		fprintf(stderr, "failed to load BPF object: %s\n", strerror(errno));
		bpf_object__close(object);
		return 1;
	}

	__u32 key = 0;
	__u32 target = (__u32)target_tgid;
	int filter_fd = bpf_object__find_map_fd_by_name(object, "target_tgid");
	if (filter_fd < 0 || bpf_map_update_elem(filter_fd, &key, &target, BPF_ANY)) {
		fprintf(stderr, "failed to configure TGID filter\n");
		bpf_object__close(object);
		return 1;
	}

	struct bpf_program *program =
		bpf_object__find_program_by_name(object, "tier_e_sys_enter");
	struct bpf_link *link = bpf_program__attach(program);
	if (libbpf_get_error(link)) {
		link = NULL;
		fprintf(stderr, "failed to attach raw_tp/sys_enter\n");
		bpf_object__close(object);
		return 1;
	}

	struct ring_buffer *ring = ring_buffer__new(
		bpf_object__find_map_fd_by_name(object, "events"), on_event, NULL, NULL);
	if (!ring) {
		fprintf(stderr, "failed to create ring buffer manager\n");
		bpf_link__destroy(link);
		bpf_object__close(object);
		return 1;
	}

	signal(SIGINT, on_signal);
	signal(SIGTERM, on_signal);

	fprintf(stderr, "[tier-e] tracing tgid=%ld for %.0fs\n", target_tgid, duration_s);
	const double deadline = now_monotonic_s() + duration_s;
	while (!g_stop) {
		int consumed = ring_buffer__poll(ring, 100 /* ms */);
		if (consumed == -EINTR)
			break;
		if (consumed < 0) {
			fprintf(stderr, "ring poll error %d\n", consumed);
			break;
		}
		if (now_monotonic_s() >= deadline)
			break;
	}

	if (g_summary)
		print_summary();
	key = 0;
	int dropped_fd = bpf_object__find_map_fd_by_name(object, "dropped_events");
	if (dropped_fd >= 0) {
		int nr_cpus = libbpf_num_possible_cpus();
		unsigned long long per_cpu[512] = {};
		unsigned long long total_dropped = 0;
		if (nr_cpus > 0 && nr_cpus <= 512 &&
		    bpf_map_lookup_elem(dropped_fd, &key, per_cpu) == 0) {
			for (int cpu = 0; cpu < nr_cpus; cpu++)
				total_dropped += per_cpu[cpu];
		}
		fprintf(stderr, "[tier-e] dropped=%llu complete=%s\n", total_dropped,
			total_dropped == 0 ? "true" : "false");
	}

	ring_buffer__free(ring);
	bpf_link__destroy(link);
	bpf_object__close(object);
	return 0;
}
