// SPDX-License-Identifier: GPL-2.0
/*
 * Tier E WP-03 Gate G1: marker transition latency.
 *
 * Dlopens a marker library and times N back-to-back context transitions with
 * clock_gettime(CLOCK_MONOTONIC). Run once detached (raw call cost) and once
 * with the loader attached (trap/probe cost). Emits exactly one result line:
 *   [bench] ns_per_transition=<double>
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>

typedef void (*marker_fn)(uint32_t);

static double now_s(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

int main(int argc, char **argv)
{
	if (argc < 3) {
		fprintf(stderr, "usage: wp03_bench <lib.so> <iterations> [gate_file]\n");
		return 2;
	}
	const char *lib_path = argv[1];
	long iterations = strtol(argv[2], NULL, 10);
	const char *gate = argc > 3 ? argv[3] : NULL;
	if (iterations <= 0)
		return 2;

	void *handle = dlopen(lib_path, RTLD_NOW);
	if (!handle) {
		fprintf(stderr, "bench: dlopen failed: %s\n", dlerror());
		return 1;
	}
	marker_fn marker = (marker_fn)dlsym(handle, "mazewall_context_marker");
	if (!marker) {
		fprintf(stderr, "bench: marker symbol missing\n");
		return 1;
	}

	for (long i = 0; i < 1000; i++)
		marker(1); /* warmup: page in library, plumb caches */

	while (gate && access(gate, F_OK) != 0)
		usleep(1000); /* hold here so the tracer attaches first */

	double t0 = now_s();
	for (long i = 0; i < iterations; i++)
		marker(1);
	double dt = now_s() - t0;

	printf("[bench] ns_per_transition=%.1f\n", (dt / (double)iterations) * 1e9);
	fflush(stdout);
	return 0;
}
