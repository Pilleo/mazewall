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
	(void)argc;

	void *handle = dlopen("./build/libmazewall_context.so", RTLD_NOW);
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
	fprintf(stderr, "driver: phases complete (pid=%d)\n", getpid());
	return 0;
}
