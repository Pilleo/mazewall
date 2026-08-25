// SPDX-License-Identifier: GPL-2.0
/* Tier E WP-04: minimal control-plane client.
 *   wp04_client <sock> ATTACH <pid> <uprobe|usdt> <marker.so>
 *   wp04_client <sock> DETACH|STATUS|SHUTDOWN
 * Prints the server's reply line verbatim; exits 0 on OK, 1 on ERR. */

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

int main(int argc, char **argv)
{
	if (argc < 3) {
		fprintf(stderr,
			"usage: %s <sock> ATTACH <pid> <mode> <so>|DETACH|STATUS|SHUTDOWN\n",
			argv[0]);
		return 2;
	}

	int fd = socket(AF_UNIX, SOCK_STREAM, 0);
	if (fd < 0) {
		perror("socket");
		return 1;
	}
	struct sockaddr_un addr = { .sun_family = AF_UNIX };
	strncpy(addr.sun_path, argv[1], sizeof(addr.sun_path) - 1);
	if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
		fprintf(stderr, "ERR CONNECT %s\n", strerror(errno));
		return 1;
	}

	char request[512];
	size_t off = 0;
	for (int i = 2; i < argc && off < sizeof(request) - 2; i++)
		off += (size_t)snprintf(request + off, sizeof(request) - off - 1,
					"%s%s", i > 2 ? " " : "", argv[i]);
	request[off++] = '\n';
	if (send(fd, request, off, 0) < 0) {
		perror("send");
		return 1;
	}

	char reply[512];
	ssize_t n = recv(fd, reply, sizeof(reply) - 1, 0);
	if (n <= 0) {
		fprintf(stderr, "ERR RECV %s\n", n == 0 ? "eof" : strerror(errno));
		return 1;
	}
	reply[n] = '\0';
	fputs(reply, stdout);
	return strncmp(reply, "OK", 2) == 0 ? 0 : 1;
}
