// SPDX-License-Identifier: GPL-2.0
/*
 * Tier E WP-03 Gate G0a driver: deterministic marker/syscall pairing.
 *
 * Phases (single thread, no concurrency):
 *   wait for attach
 *   marker(42);  5x getpid()      -> events must carry context_id = 42
 *   marker(7);   3x getpid()     -> events must carry context_id = 7
 *   marker(0);   3x getpid()     -> UNKNOWN: loader must print NOTHING
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/syscall.h>

typedef void (*marker_fn)(uint32_t);

int main(int argc, char **argv)
{
	int wait_us = argc > 1 ? atoi(argv[1]) : 1000000;
	const char *lib_path = argc > 2 ? argv[2] : "./build/libmazewall_context.so";
	int cycles = argc > 3 ? atoi(argv[3]) : 1;

	void *handle = dlopen(lib_path, RTLD_NOW);
	if (!handle) {
		fprintf(stderr, "driver: dlopen failed: %s\n", dlerror());
		return 1;
	}
	marker_fn marker = (marker_fn)dlsym(handle, "mazewall_context_marker");
	if (!marker) {
		fprintf(stderr, "driver: mazewall_context_marker not exported\n");
		return 1;
	}

	usleep((useconds_t)wait_us);

	for (int c = 0; c < cycles; c++) {
		marker(42);
		for (int i = 0; i < 5; i++) {
			syscall(SYS_getpid);
			usleep(20000);
		}
		marker(7);
		for (int i = 0; i < 3; i++) {
			syscall(SYS_getpid);
			usleep(20000);
		}
		marker(0);
		for (int i = 0; i < 3; i++) {
			syscall(SYS_getpid);
			usleep(20000);
		}
		if (c + 1 < cycles)
			usleep(400000); /* inter-cycle gap for live re-attach tests */
	}
	fprintf(stderr, "driver: %d phase cycle(s) complete (pid=%d)\n", cycles, getpid());
	return 0;
}
