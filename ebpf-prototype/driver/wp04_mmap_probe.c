// SPDX-License-Identifier: GPL-2.0
/* Tier E WP-04 diagnostic: which ringbuf mmap shape is permitted here?
 * Loads context_probe.bpf.o via libtier_e_bpf.so, attaches to the CALLING
 * process (self), then probes mapping variants with raw libc mmap,
 * reporting errno for each. Zero policy logic. */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

typedef void *(*load_fn)(const char *);
typedef int (*int_int_fn)(void *, int);
typedef int (*int_one_fn)(void *);
typedef int (*attach_uprobe_fn)(void *, int, const char *);
typedef int (*ring_fd_fn)(void *);
typedef const char *(*err_fn)(void);

static long try_mmap(const char *label, long len, int prot, int flags, int fd, long off)
{
	long rc = (long)mmap(NULL, (size_t)len, prot, flags, fd, off);
	if (rc == -1) {
		printf("%-28s FAIL errno=%d (%s)\n", label, errno, strerror(errno));
		return -1;
	}
	printf("%-28s OK\n", label);
	munmap((void *)rc, (size_t)len);
	return 0;
}

int main(int argc, char **argv)
{
	const char *so = argc > 1 ? argv[1] : "./build/libtier_e_bpf.so";
	void *shim = dlopen(so, RTLD_NOW);
	if (!shim) {
		fprintf(stderr, "dlopen shim: %s\n", dlerror());
		return 1;
	}
	load_fn te_load = (load_fn)dlsym(shim, "te_load_object");
	int_int_fn te_tgid = (int_int_fn)dlsym(shim, "te_set_target_tgid");
	int_one_fn te_enter = (int_one_fn)dlsym(shim, "te_attach_sys_enter");
	ring_fd_fn te_ring_fd = (ring_fd_fn)dlsym(shim, "te_ring_fd");
	err_fn te_err = (err_fn)dlsym(shim, "te_last_error");
	if (!te_load || !te_tgid || !te_enter || !te_ring_fd || !te_err) {
		fprintf(stderr, "shim symbols missing\n");
		return 1;
	}

	void *ctx = te_load("build/context_probe.bpf.o");
	if (!ctx) {
		fprintf(stderr, "load failed: %s\n", te_err());
		return 1;
	}
	te_tgid(ctx, (int)getpid());
	if (te_enter(ctx)) {
		fprintf(stderr, "sys_enter attach failed: %s\n", te_err());
		return 1;
	}
	int ring_fd = te_ring_fd(ctx);
	printf("ring_fd=%d\n", ring_fd);

	const long PAGE = 4096L;
	const long DATA = 1L << 20;
	try_mmap("RW full (meta+data)", PAGE + DATA, PROT_READ | PROT_WRITE, MAP_SHARED, ring_fd, 0);
	try_mmap("RO full (meta+data)", PAGE + DATA, PROT_READ, MAP_SHARED, ring_fd, 0);
	try_mmap("RW meta-only", PAGE, PROT_READ | PROT_WRITE, MAP_SHARED, ring_fd, 0);
	try_mmap("RO data alias (pgoff)", DATA, PROT_READ, MAP_SHARED, ring_fd, PAGE);
	try_mmap("RW data alias (pgoff)", DATA, PROT_READ | PROT_WRITE, MAP_SHARED, ring_fd, PAGE);
	return 0;
}
