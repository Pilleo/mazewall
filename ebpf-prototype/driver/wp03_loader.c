// SPDX-License-Identifier: GPL-2.0
/*
 * Tier E WP-03 Gate G0a loader: attaches the context programs to a target and
 * prints attributed events as "<nr> ctx=<id>". Unattributed syscalls print
 * nothing (fail-unknown).
 */

#include <bpf/bpf.h>
#include <bpf/libbpf.h>
#include <errno.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t g_stop;
static int g_summary;
static unsigned long long event_total;

static int libbpf_printf_logger(enum libbpf_print_level level, const char *format,
				va_list args)
{
	if (level == LIBBPF_DEBUG)
		return 0;
	vfprintf(stderr, format, args);
	return 0;
}

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
		unsigned int context_id;
	} __attribute__((packed)) * event = data;

	if (!g_summary) {
		printf("%d ctx=%u\n", event->syscall_nr, event->context_id);
		fflush(stdout);
	}
	event_total++;
	return 0;
}

int main(int argc, char **argv)
{
	long target_tgid = 0;
	double duration_s = 8.0;
	const char *marker_path = "./build/libmazewall_context.so";
	const char *attach_mode = "uprobe";

	for (int i = 1; i < argc; i++) {
		if (strcmp(argv[i], "--pid") == 0 && i + 1 < argc) {
			target_tgid = parse_long(argv[++i], "--pid");
		} else if (strcmp(argv[i], "--duration") == 0 && i + 1 < argc) {
			duration_s = (double)parse_long(argv[++i], "--duration");
		} else if (strcmp(argv[i], "--marker") == 0 && i + 1 < argc) {
			marker_path = argv[++i];
		} else if (strcmp(argv[i], "--attach") == 0 && i + 1 < argc) {
			attach_mode = argv[++i];
		} else if (strcmp(argv[i], "--summary") == 0) {
			g_summary = 1;
		} else {
			fprintf(stderr,
				"usage: wp03_loader --pid <tgid> [--marker <so>] "
				"[--attach uprobe|usdt] [--duration s] [--summary]\n");
			return 2;
		}
	}
	if (strcmp(attach_mode, "uprobe") && strcmp(attach_mode, "usdt")) {
		fprintf(stderr, "unknown --attach mode: %s\n", attach_mode);
		return 2;
	}
	if (target_tgid <= 0) {
		fprintf(stderr, "a positive --pid is mandatory\n");
		return 2;
	}
	if (geteuid() != 0) {
		fprintf(stderr,
			"refusing to start: tracing BPF requires initial-userns root "
			"(use scripts/run_wp03.sh)\n");
		return 1;
	}

	/* USDT attachment reconciles .note.stapsdt addresses against the
	 * TARGET's /proc/<pid>/maps entries, which are absolute. Normalize so
	 * path-form differences cannot silently park the probe off-map. */
	char resolved_marker[PATH_MAX];
	if (!realpath(marker_path, resolved_marker)) {
		fprintf(stderr, "cannot resolve marker path %s: %s\n",
			marker_path, strerror(errno));
		return 1;
	}
	marker_path = resolved_marker;
	if (getenv("TIER_E_DEBUG"))
		libbpf_set_print(libbpf_printf_logger);

	struct bpf_object *object =
		bpf_object__open_file("build/context_probe.bpf.o", NULL);
	if (libbpf_get_error(object)) {
		object = NULL;
		fprintf(stderr, "failed to open build/context_probe.bpf.o (run make)\n");
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

	struct bpf_program *enter_prog =
		bpf_object__find_program_by_name(object, "tier_e_sys_enter_ctx");
	struct bpf_link *enter_link = enter_prog ? bpf_program__attach(enter_prog) : NULL;
	if (!enter_link) {
		fprintf(stderr, "failed to attach sys_enter program\n");
		bpf_object__close(object);
		return 1;
	}

	struct bpf_program *marker_prog =
		bpf_object__find_program_by_name(object, "tier_e_on_marker");
	struct bpf_link *marker_link = NULL;
	if (strcmp(attach_mode, "usdt") == 0) {
		marker_prog = bpf_object__find_program_by_name(object,
							       "tier_e_on_marker_usdt");
		marker_link = bpf_program__attach_usdt(marker_prog,
						       (pid_t)target_tgid, marker_path,
						       "mazewall", "context_switch",
						       NULL);
	} else {
		LIBBPF_OPTS(bpf_uprobe_opts, uprobe_opts,
			    .func_name = "mazewall_context_marker",
			    .retprobe = false);
		marker_link = bpf_program__attach_uprobe_opts(
			marker_prog, (pid_t)target_tgid, marker_path, 0, &uprobe_opts);
	}
	if (!marker_link) {
		fprintf(stderr, "failed to attach %s to %s: %s\n",
			attach_mode, marker_path, strerror(errno));
		bpf_link__destroy(enter_link);
		bpf_object__close(object);
		return 1;
	}

	struct ring_buffer *ring =
		ring_buffer__new(bpf_object__find_map_fd_by_name(object, "context_events"),
				 on_event, NULL, NULL);
	if (!ring) {
		fprintf(stderr, "failed to create ring buffer manager\n");
		bpf_link__destroy(marker_link);
		bpf_link__destroy(enter_link);
		bpf_object__close(object);
		return 1;
	}

	signal(SIGINT, on_signal);
	signal(SIGTERM, on_signal);

	fprintf(stderr, "[wp03] tgid=%ld attach=%s marker=%s\n", target_tgid, attach_mode, marker_path);
	const double deadline = now_monotonic_s() + duration_s;
	while (!g_stop) {
		int consumed = ring_buffer__poll(ring, 100);
		if (consumed == -EINTR)
			break;
		if (consumed < 0 && consumed != -EAGAIN) {
			fprintf(stderr, "ring poll error %d\n", consumed);
			break;
		}
		if (now_monotonic_s() >= deadline)
			break;
	}

	ring_buffer__free(ring);

	if (g_summary)
		fprintf(stderr, "[wp03] events=%llu\n", event_total);

	{
		int hc_fd = bpf_object__find_map_fd_by_name(object, "hit_counters");
		if (hc_fd >= 0) {
			int nr_cpus = libbpf_num_possible_cpus();
			unsigned long long per[1024] = {};
			__u32 hk = 0;
			if (nr_cpus > 0 && bpf_map_lookup_elem(hc_fd, &hk, per) == 0) {
				unsigned long long up = 0, ev = 0;
				for (int cpu = 0; cpu < nr_cpus; cpu++) {
					up += per[cpu * 2];
					ev += per[cpu * 2 + 1];
				}
				fprintf(stderr, "[wp03] uprobe_hits=%llu sysenter_hits=%llu\n", up, ev);
			}
		}
	}

	int dropped_fd = bpf_object__find_map_fd_by_name(object, "dropped_events");
	if (dropped_fd >= 0) {
		int nr_cpus = libbpf_num_possible_cpus();
		unsigned long long per_cpu[512] = {};
		unsigned long long total = 0;
		if (nr_cpus > 0 && nr_cpus <= 512 &&
		    bpf_map_lookup_elem(dropped_fd, &key, per_cpu) == 0) {
			for (int cpu = 0; cpu < nr_cpus; cpu++)
				total += per_cpu[cpu];
		}
		fprintf(stderr, "[wp03] dropped=%llu complete=%s\n", total,
			total == 0 ? "true" : "false");
	}

	bpf_link__destroy(marker_link);
	bpf_link__destroy(enter_link);
	bpf_object__close(object);
	return 0;
}
