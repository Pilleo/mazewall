// SPDX-License-Identifier: GPL-2.0
/*
 * Tier E WP-04: stateless libbpf binding shim.
 *
 * This library deliberately contains ZERO policy logic: no sockets, no state
 * machine, no trust decisions. It is a swappable seam behind which libbpf
 * performs object load / map filter / program attach. The Kotlin control plane
 * (tier-e-proto module) owns all lifecycle and trust decisions and talks to
 * this shim over plain C ABI via FFM downcalls. WP-14 replaces this seam with
 * a pure-syscall loader without touching anything above it.
 *
 * Every function returns 0 on success, -errno on failure; te_last_error()
 * returns a thread-local diagnostic for the most recent failure.
 */

#include <bpf/bpf.h>
#include <bpf/libbpf.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

struct te_ctx {
	struct bpf_object *object;
	struct bpf_link *enter_link;
	struct bpf_link *marker_link;
};

static __thread char last_error[256];

static void set_err(const char *fmt, ...)
{
	va_list ap;
	va_start(ap, fmt);
	vsnprintf(last_error, sizeof(last_error), fmt, ap);
	va_end(ap);
}

const char *te_last_error(void)
{
	return last_error;
}

void *te_load_object(const char *bpf_o_path)
{
	libbpf_set_print(NULL); /* diagnostics flow through te_last_error */
	struct bpf_object *obj = bpf_object__open_file(bpf_o_path, NULL);
	if (libbpf_get_error(obj)) {
		obj = NULL;
		set_err("open %s failed", bpf_o_path);
		return NULL;
	}
	if (bpf_object__load(obj)) {
		set_err("load failed: %s", strerror(errno));
		bpf_object__close(obj);
		return NULL;
	}
	struct te_ctx *ctx = calloc(1, sizeof(*ctx));
	if (!ctx) {
		bpf_object__close(obj);
		set_err("oom");
		return NULL;
	}
	ctx->object = obj;
	return ctx;
}

int te_set_target_tgid(void *handle, int tgid)
{
	struct te_ctx *ctx = handle;
	if (!ctx || !ctx->object)
		return -EINVAL;
	__u32 key = 0;
	__u32 value = (__u32)tgid;
	int fd = bpf_object__find_map_fd_by_name(ctx->object, "target_tgid");
	if (fd < 0)
		return -ENOENT;
	int rc = bpf_map_update_elem(fd, &key, &value, BPF_ANY);
	return rc ? -errno : 0;
}

int te_attach_sys_enter(void *handle)
{
	struct te_ctx *ctx = handle;
	if (!ctx || !ctx->object || ctx->enter_link)
		return -EINVAL;
	struct bpf_program *prog =
		bpf_object__find_program_by_name(ctx->object, "tier_e_sys_enter_ctx");
	if (!prog)
		return -ENOENT;
	ctx->enter_link = bpf_program__attach(prog);
	if (!ctx->enter_link)
		return -errno ? -errno : -EIO;
	return 0;
}

static struct bpf_program *marker_prog(struct bpf_object *obj, int usdt)
{
	return bpf_object__find_program_by_name(
		obj, usdt ? "tier_e_on_marker_usdt" : "tier_e_on_marker");
}

int te_attach_marker_uprobe(void *handle, int pid, const char *so_path)
{
	struct te_ctx *ctx = handle;
	if (!ctx || !ctx->object || ctx->marker_link)
		return -EINVAL;
	struct bpf_program *prog = marker_prog(ctx->object, 0);
	if (!prog)
		return -ENOENT;
	LIBBPF_OPTS(bpf_uprobe_opts, opts,
		    .func_name = "mazewall_context_marker",
		    .retprobe = false);
	ctx->marker_link =
		bpf_program__attach_uprobe_opts(prog, pid, so_path, 0, &opts);
	if (!ctx->marker_link)
		return -errno ? -errno : -EIO;
	return 0;
}

/* Retained for the future Kotlin stapsdt parser's parity runs; unused while
 * the daemon ships uprobe-first per design doc §4.1.1 sign-off (2026-08-25). */
int te_attach_marker_usdt(void *handle, int pid, const char *so_path)
{
	struct te_ctx *ctx = handle;
	if (!ctx || !ctx->object || ctx->marker_link)
		return -EINVAL;
	struct bpf_program *prog = marker_prog(ctx->object, 1);
	if (!prog)
		return -ENOENT;
	ctx->marker_link = bpf_program__attach_usdt(
		prog, pid, so_path, "mazewall", "context_switch", NULL);
	if (!ctx->marker_link)
		return -errno ? -errno : -EIO;
	return 0;
}

int te_ring_fd(void *handle)
{
	struct te_ctx *ctx = handle;
	if (!ctx || !ctx->object)
		return -EINVAL;
	return bpf_object__find_map_fd_by_name(ctx->object, "context_events");
}

int te_dropped_total(void *handle, unsigned long long *out)
{
	struct te_ctx *ctx = handle;
	if (!ctx || !ctx->object || !out)
		return -EINVAL;
	__u32 key = 0;
	int nr_cpus = libbpf_num_possible_cpus();
	unsigned long long per_cpu[512] = {};
	if (nr_cpus <= 0 || nr_cpus > 512)
		return -ERANGE;
	int fd = bpf_object__find_map_fd_by_name(ctx->object, "dropped_events");
	if (fd < 0)
		return -ENOENT;
	if (bpf_map_lookup_elem(fd, &key, per_cpu))
		return -errno;
	unsigned long long total = 0;
	for (int cpu = 0; cpu < nr_cpus; cpu++)
		total += per_cpu[cpu];
	*out = total;
	return 0;
}

void te_destroy(void *handle)
{
	struct te_ctx *ctx = handle;
	if (!ctx)
		return;
	if (ctx->marker_link)
		bpf_link__destroy(ctx->marker_link);
	if (ctx->enter_link)
		bpf_link__destroy(ctx->enter_link);
	if (ctx->object)
		bpf_object__close(ctx->object);
	free(ctx);
}
