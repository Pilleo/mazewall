// SPDX-License-Identifier: GPL-2.0
/*
 * Tier E WP-04: privileged session daemon (prototype).
 *
 * Owns one BPF object PER SESSION EPOCH (never recycled — invariant 5), no
 * bpffs pinning (invariant 6): every attachment lives and dies with this
 * process's FDs. Control plane is a line protocol over AF_UNIX:
 *
 *   ATTACH <pid> <uprobe|usdt> <marker.so>   -> OK | ERR <reason>
 *   DETACH                                    -> OK | ERR
 *   STATUS                                    -> OK <state> epoch=<n> tgid=<p|->|ERR BUSY
 *   SHUTDOWN                                  -> OK (daemon exits)
 *
 * Trust model (design doc §5): the control plane is metadata-only. A session
 * is RUNNING only after the marker library passed inode-in-target-maps +
 * NT_GNU_BUILD_ID verification; peers are accepted solely with uid 0;
 * additional connections while a session is active are rejected with ERR BUSY.
 * Session death is terminal for that session (RUNNING -> DEAD); a subsequent
 * connection starts a NEW epoch with freshly created maps.
 */

#define _GNU_SOURCE

#include <bpf/bpf.h>
#include <bpf/libbpf.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define SOCK_PATH_DEFAULT "/run/mazewall/wp04.sock"
#define LINE_MAX_LEN 512

typedef enum {
	ST_LISTENING = 0, /* no client connected */
	ST_ACCEPTED,	  /* client connected, not yet attached */
	ST_RUNNING,	  /* attached to a target */
	ST_DEAD,	  /* terminal for THIS session epoch */
} session_state_t;

static volatile sig_atomic_t g_stop;

struct session {
	int client_fd;
	session_state_t state;
	unsigned long epoch;
	pid_t tgid;	      /* -1 when detached */
	struct bpf_object *object; /* fresh per epoch */
	struct bpf_link *enter_link;
	struct bpf_link *marker_link;
	int ring_fd;
	struct ring_buffer *ring;
	int verbose;
	int quiet;
	unsigned long long event_total;
};

static void on_signal(int sig)
{
	(void)sig;
	g_stop = 1;
}

static double now_s(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

static int reply(int fd, const char *fmt, ...)
{
	char buf[LINE_MAX_LEN];
	va_list ap;
	va_start(ap, fmt);
	vsnprintf(buf, sizeof(buf), fmt, ap);
	va_end(ap);
	size_t len = strlen(buf);
	return send(fd, buf, len, MSG_NOSIGNAL) == (ssize_t)len ? 0 : -1;
}

/* ------------------------------------------------------------------ *
 * Marker hygiene: NT_GNU_BUILD_ID extraction + inode-in-target-maps.
 * ------------------------------------------------------------------ */

static int read_build_id(const char *path, char *out /* >= 41 bytes */)
{
	int fd = open(path, O_RDONLY);
	if (fd < 0)
		return -1;

	Elf64_Ehdr ehdr;
	if (pread(fd, &ehdr, sizeof(ehdr), 0) != sizeof(ehdr) ||
	    memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0 ||
	    ehdr.e_machine != EM_X86_64 /* prototype scope */) {
		close(fd);
		return -1;
	}

	Elf64_Shdr *shdrs = malloc(sizeof(*shdrs) * ehdr.e_shnum);
	if (!shdrs || pread(fd, shdrs, sizeof(*shdrs) * ehdr.e_shnum,
			    ehdr.e_shoff) != (ssize_t)(sizeof(*shdrs) * ehdr.e_shnum)) {
		free(shdrs);
		close(fd);
		return -1;
	}
	char *shstrtab = malloc(shdrs[ehdr.e_shstrndx].sh_size);
	if (!shstrtab || pread(fd, shstrtab, shdrs[ehdr.e_shstrndx].sh_size,
			       shdrs[ehdr.e_shstrndx].sh_offset) !=
			       (ssize_t)shdrs[ehdr.e_shstrndx].sh_size) {
		free(shstrtab);
		free(shdrs);
		close(fd);
		return -1;
	}

	int found = -1;
	for (int i = 0; i < (int)ehdr.e_shnum && found < 0; i++) {
		if (shdrs[i].sh_type != SHT_NOTE)
			continue;
		char *note = malloc(shdrs[i].sh_size);
		if (!note || pread(fd, note, shdrs[i].sh_size, shdrs[i].sh_offset) !=
				     (ssize_t)shdrs[i].sh_size) {
			free(note);
			continue;
		}
		size_t off = 0;
		while (off + sizeof(Elf64_Nhdr) <= shdrs[i].sh_size && found < 0) {
			Elf64_Nhdr nh;
			memcpy(&nh, note + off, sizeof(nh));
			off += sizeof(nh);
			size_t name_sz = (nh.n_namesz + 3) & ~(size_t)3;
			size_t desc_sz = (nh.n_descsz + 3) & ~(size_t)3;
			if (off + name_sz + desc_sz > shdrs[i].sh_size)
				break;
			if (nh.n_type == NT_GNU_BUILD_ID && nh.n_namesz == 4 &&
			    memcmp(note + off, "GNU\0", 4) == 0) {
				const unsigned char *desc =
					(const unsigned char *)(note + off + name_sz);
				for (size_t b = 0; b < nh.n_descsz && b < 20; b++)
					sprintf(out + b * 2, "%02x", desc[b]);
				out[nh.n_descsz < 20 ? nh.n_descsz * 2 : 40] = '\0';
				found = 0;
			}
			off += name_sz + desc_sz;
		}
		free(note);
	}
	free(shstrtab);
	free(shdrs);
	close(fd);
	return found;
}

/* The marker must be mapped by the TARGET under exactly this inode. */
static int target_maps_inode(pid_t pid, const char *real_path,
			     const struct stat *so_stat)
{
	char maps_path[64];
	snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", (int)pid);
	FILE *f = fopen(maps_path, "r");
	if (!f)
		return -1;
	char line[512];
	int found = 0;
	while (fgets(line, sizeof(line), f)) {
		unsigned long dev_major, dev_minor;
		unsigned long long inode;
		char path_part[256] = "";
		/* addr perms offset dev inode pathname */
		if (sscanf(line, "%*s %*s %*s %lx:%lx %llu %255s",
			   &dev_major, &dev_minor, &inode, path_part) >= 3 &&
		    inode == so_stat->st_ino &&
		    dev_major == major(so_stat->st_dev) &&
		    dev_minor == minor(so_stat->st_dev)) {
			found = 1;
			break;
		}
		(void)path_part;
	}
	fclose(f);
	return found ? 0 : -1;
}

/* ------------------------------------------------------------------ *
 * Session internals
 * ------------------------------------------------------------------ */

static int on_event(void *ctx, void *data, size_t size)
{
	struct session *s = ctx;
	(void)size;
	const struct {
		unsigned long long ktime_ns;
		unsigned int tgid;
		unsigned int tid;
		int syscall_nr;
		unsigned int context_id;
	} __attribute__((packed)) * e = data;

	s->event_total++;
	if (!s->quiet)
		printf("%d ctx=%u\n", e->syscall_nr, e->context_id), fflush(stdout);
	return 0;
}

static void teardown_attachments(struct session *s)
{
	if (s->ring) {
		ring_buffer__free(s->ring);
		s->ring = NULL;
	}
	if (s->marker_link) {
		bpf_link__destroy(s->marker_link);
		s->marker_link = NULL;
	}
	if (s->enter_link) {
		bpf_link__destroy(s->enter_link);
		s->enter_link = NULL;
	}
	if (s->object) {
		/* Closing the object closes maps+programs: the whole epoch dies
		 * with these FDs. No pins anywhere (invariant 6). */
		bpf_object__close(s->object);
		s->object = NULL;
	}
	s->tgid = -1;
}

/* Returns 0 on success and fills s->object/links/ring. */
static int attach_target(struct session *s, pid_t pid, const char *mode,
			 const char *marker_path)
{
	char real[PATH_MAX];
	if (!realpath(marker_path, real)) {
		reply(s->client_fd, "ERR MARKER_PATH %s\n", strerror(errno));
		return -1;
	}

	struct stat so_stat;
	if (stat(real, &so_stat) != 0 || !S_ISREG(so_stat.st_mode)) {
		reply(s->client_fd, "ERR MARKER_STAT\n");
		return -1;
	}

	char build_id[41];
	if (read_build_id(real, build_id) != 0) {
		/* Loud failure: an ELF we cannot identify must never silently
		 * become the tracing ABI (design doc §11 risk 4). */
		reply(s->client_fd, "ERR BUILD_ID_UNREADABLE %s\n", real);
		return -1;
	}
	if (target_maps_inode(pid, real, &so_stat) != 0) {
		reply(s->client_fd, "ERR NOT_MAPPED_IN_TARGET inode=%llu buildid=%s\n",
		      (unsigned long long)so_stat.st_ino, build_id);
		return -1;
	}

	s->object = bpf_object__open_file("build/context_probe.bpf.o", NULL);
	if (libbpf_get_error(s->object)) {
		s->object = NULL;
		reply(s->client_fd, "ERR OPEN_BPF\n");
		return -1;
	}
	if (bpf_object__load(s->object)) {
		reply(s->client_fd, "ERR LOAD_BPF %s\n", strerror(errno));
		bpf_object__close(s->object);
		s->object = NULL;
		return -1;
	}

	__u32 key = 0;
	__u32 target = (__u32)pid;
	int filter_fd = bpf_object__find_map_fd_by_name(s->object, "target_tgid");
	if (filter_fd < 0 || bpf_map_update_elem(filter_fd, &key, &target, BPF_ANY)) {
		reply(s->client_fd, "ERR FILTER\n");
		goto fail;
	}

	struct bpf_program *enter_prog =
		bpf_object__find_program_by_name(s->object, "tier_e_sys_enter_ctx");
	s->enter_link = enter_prog ? bpf_program__attach(enter_prog) : NULL;
	if (!s->enter_link) {
		reply(s->client_fd, "ERR_ATTACH_SYS_ENTER\n");
		goto fail;
	}

	struct bpf_program *marker_prog;
	if (strcmp(mode, "usdt") == 0)
		marker_prog = bpf_object__find_program_by_name(s->object,
							       "tier_e_on_marker_usdt");
	else
		marker_prog = bpf_object__find_program_by_name(s->object,
							       "tier_e_on_marker");
	if (!marker_prog) {
		reply(s->client_fd, "ERR_NO_MARKER_PROG\n");
		goto fail;
	}

	if (strcmp(mode, "usdt") == 0) {
		s->marker_link = bpf_program__attach_usdt(
			marker_prog, pid, real, "mazewall", "context_switch", NULL);
	} else {
		LIBBPF_OPTS(bpf_uprobe_opts, opts,
			    .func_name = "mazewall_context_marker",
			    .retprobe = false);
		s->marker_link = bpf_program__attach_uprobe_opts(marker_prog, pid,
								 real, 0, &opts);
	}
	if (!s->marker_link) {
		reply(s->client_fd, "ERR_ATTACH_MARKER_%s %s\n", mode, strerror(errno));
		goto fail;
	}

	s->ring_fd = bpf_object__find_map_fd_by_name(s->object, "context_events");
	s->ring = ring_buffer__new(s->ring_fd, on_event, s, NULL);
	if (!s->ring) {
		reply(s->client_fd, "ERR_RINGBUF\n");
		goto fail;
	}

	s->state = ST_RUNNING;
	s->tgid = pid;
	fprintf(stderr, "[wp04] epoch=%lu RUNNING tgid=%d mode=%s marker=%s buildid=%s\n",
		s->epoch, (int)pid, mode, real, build_id);
	reply(s->client_fd, "OK ATTACHED epoch=%lu buildid=%s\n", s->epoch, build_id);
	return 0;

fail:
	teardown_attachments(s);
	s->state = ST_DEAD;
	return -1;
}

static void end_session(struct session *s, const char *why)
{
	teardown_attachments(s);
	if (s->client_fd >= 0) {
		close(s->client_fd);
		s->client_fd = -1;
	}
	s->state = ST_DEAD; /* terminal for this session (invariant 7) */
	fprintf(stderr, "[wp04] epoch=%lu DEAD (%s) events=%llu\n",
		s->epoch, why, s->event_total);
}

/* Handle one newline-terminated command from the connected peer. */
static void handle_command(struct session *s, char *line)
{
	char cmd[32] = { 0 };
	char mode[16] = { 0 };
	char path[PATH_MAX] = { 0 };
	long pid_arg = 0;

	if (sscanf(line, "ATTACH %ld %15s %4095s", &pid_arg, mode, path) == 3) {
		if (s->state != ST_ACCEPTED && s->state != ST_RUNNING) {
			reply(s->client_fd, "ERR STATE\n");
			return;
		}
		if (s->state == ST_RUNNING) {
			/* Re-ATTACH within one session is refused: one target
			 * binding per epoch keeps attribution unambiguous. */
			reply(s->client_fd, "ERR ALREADY_BOUND tgid=%d\n", (int)s->tgid);
			return;
		}
		if (strcmp(mode, "uprobe") != 0 && strcmp(mode, "usdt") != 0) {
			reply(s->client_fd, "ERR BAD_MODE\n");
			return;
		}
		attach_target(s, (pid_t)pid_arg, mode, path);
		return;
	}
	if (strcmp(line, "DETACH") == 0) {
		if (s->state != ST_RUNNING) {
			reply(s->client_fd, "ERR NOT_BOUND\n");
			return;
		}
		teardown_attachments(s);
		s->state = ST_ACCEPTED; /* same epoch, unbound; no re-ATTACH */
		reply(s->client_fd, "OK DETACHED\n");
		return;
	}
	if (strcmp(line, "STATUS") == 0) {
		reply(s->client_fd, "OK %s epoch=%lu tgid=%d\n",
		      s->state == ST_RUNNING ? "RUNNING"
		      : s->state == ST_ACCEPTED ? "ACCEPTED"
						: "LISTENING",
		      s->epoch, s->state == ST_RUNNING ? (int)s->tgid : -1);
		return;
	}
	if (strcmp(line, "SHUTDOWN") == 0) {
		reply(s->client_fd, "OK BYE\n");
		g_stop = 1;
		return;
	}
	reply(s->client_fd, "ERR UNKNOWN_COMMAND\n");
}

int main(int argc, char **argv)
{
	const char *sock_path = SOCK_PATH_DEFAULT;
	struct session s = { .client_fd = -1, .tgid = -1 };

	for (int i = 1; i < argc; i++) {
		if (strcmp(argv[i], "--sock") == 0 && i + 1 < argc)
			sock_path = argv[++i];
		else if (strcmp(argv[i], "--quiet") == 0)
			s.quiet = 1;
		else if (strcmp(argv[i], "--verbose") == 0)
			s.verbose = 1;
		else {
			fprintf(stderr,
				"usage: wp04_daemon [--sock <path>] [--quiet|--verbose]\n");
			return 2;
		}
	}
	if (geteuid() != 0) {
		fprintf(stderr, "refusing: requires initial-userns root\n");
		return 1;
	}

	signal(SIGINT, on_signal);
	signal(SIGTERM, on_signal);
	signal(SIGPIPE, SIG_IGN);

	int lfd = socket(AF_UNIX, SOCK_STREAM, 0);
	if (lfd < 0) {
		perror("socket");
		return 1;
	}
	unlink(sock_path); /* stale socket from a crashed predecessor */
	char sock_dir[PATH_MAX];
	snprintf(sock_dir, sizeof(sock_dir), "%s", sock_path);
	char *last_slash = strrchr(sock_dir, '/');
	if (last_slash && last_slash != sock_dir) {
		*last_slash = '\0';
		mkdir(sock_dir, 0755); /* best effort */
	}
	struct sockaddr_un addr = { .sun_family = AF_UNIX };
	strncpy(addr.sun_path, sock_path, sizeof(addr.sun_path) - 1);
	if (bind(lfd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
		perror("bind");
		return 1;
	}
	chmod(sock_path, 0660); /* trust boundary: owner/group rw only (§5) */
	listen(lfd, 2);

	fprintf(stderr, "[wp04] listening on %s\n", sock_path);

	char buf[LINE_MAX_LEN];
	size_t buf_len = 0;

	while (!g_stop) {
		struct pollfd pfds[2] = {
			{ .fd = lfd, .events = POLLIN },
			{ .fd = s.client_fd, .events = s.client_fd >= 0 ? POLLIN : 0 },
		};
		int ready = poll(pfds, 2, 200);
		if (ready < 0) {
			if (errno == EINTR)
				break;
			perror("poll");
			break;
		}

		if (pfds[0].revents & POLLIN) {
			int cfd = accept(lfd, NULL, NULL);
			if (cfd < 0)
				continue;
			if (s.client_fd >= 0) {
				/* One session at a time: duplicate controllers are
				 * rejected, never queued (design doc §5). */
				reply(cfd, "ERR BUSY\n");
				close(cfd);
				continue;
			}
			struct ucred cred;
			socklen_t cred_len = sizeof(cred);
			if (getsockopt(cfd, SOL_SOCKET, SO_PEERCRED, &cred,
				       &cred_len) < 0 ||
			    cred.uid != 0) {
				reply(cfd, "ERR PEER_UID %u\n",
				      cred_len == sizeof(cred) ? cred.uid : 0);
				close(cfd);
				continue;
			}
			memset(&s, 0, offsetof(struct session, event_total));
			s.client_fd = cfd;
			s.tgid = -1;
			s.epoch++; /* NEW epoch: fresh maps below on ATTACH */
			s.state = ST_ACCEPTED;
			s.quiet = s.quiet; /* preserved from argv */
			s.verbose = s.verbose;
			reply(cfd, "OK HELLO epoch=%lu\n", s.epoch);
			continue;
		}

		if (s.client_fd >= 0 && (pfds[1].revents & (POLLIN | POLLHUP))) {
			char tmp[LINE_MAX_LEN];
			ssize_t n = recv(s.client_fd, tmp, sizeof(tmp), 0);
			if (n <= 0) {
				end_session(&s, n == 0 ? "peer EOF" : "peer error");
				continue;
			}
			for (ssize_t i = 0; i < n; i++) {
				if (tmp[i] == '\n') {
					buf[buf_len] = '\0';
					handle_command(&s, buf);
					buf_len = 0;
					if (g_stop)
						break;
				} else if (buf_len < LINE_MAX_LEN - 1) {
					buf[buf_len++] = tmp[i];
				}
			}
		}

		if (s.ring) {
			int consumed = ring_buffer__poll(s.ring, 0);
			if (consumed < 0 && consumed != -EAGAIN) {
				fprintf(stderr, "[wp04] ring poll error %d\n", consumed);
				end_session(&s, "ring error");
			}
		}
	}

	if (s.client_fd >= 0 || s.object)
		end_session(&s, g_stop ? "shutdown" : "loop exit");
	unlink(sock_path);
	close(lfd);
	fprintf(stderr, "[wp04] clean exit\n");
	return 0;
}
