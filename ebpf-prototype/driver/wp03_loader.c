// SPDX-License-Identifier: GPL-2.0
/*
 * Tier E WP-03 Gate G0a loader: attaches the context programs to a target and
 * prints attributed events as "<nr> ctx=<id>". Unattributed syscalls print
 * nothing (fail-unknown).
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

	printf("%d ctx=%u\n", event->syscall_nr, event->context_id);
	fflush(stdout);
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
		} else {
			fprintf(stderr,
				"usage: wp03_loader --pid <tgid> [--marker <so>] "
				"[--attach uprobe|usdt] [--duration s]\n");
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
	bpf_link__destroy(marker_link);
	bpf_link__destroy(enter_link);
	bpf_object__close(object);
	return 0;
}
