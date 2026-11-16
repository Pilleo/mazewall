#define _GNU_SOURCE
#include <fcntl.h>
#include <stddef.h>
#include <stdio.h>
#include <linux/filter.h>
#include <linux/landlock.h>
#include <linux/openat2.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/un.h>
#include "live_stack_context_abi.h"

#define SIZE(type) printf("size." #type "=%zu\n", sizeof(struct type))
#define OFFSET(type, member) printf("offset." #type "." #member "=%zu\n", offsetof(struct type, member))
#define ALIGN(type) printf("align." #type "=%zu\n", _Alignof(struct type))
#define RECORD(type) SIZE(type); ALIGN(type)
#define HEADER(type) OFFSET(type, abi_version); OFFSET(type, struct_bytes); OFFSET(type, status); OFFSET(type, reason)
#define IDENTITIES(type) OFFSET(type, process_cookie); OFFSET(type, process_start_ticks); OFFSET(type, installation_id); OFFSET(type, worker_id); OFFSET(type, task_id); OFFSET(type, code_generation); OFFSET(type, invocation_id)

int main(void) {
    printf("flag.O_PATH=%d\n", O_PATH);
    SIZE(sock_filter); OFFSET(sock_filter, code); OFFSET(sock_filter, jt); OFFSET(sock_filter, jf); OFFSET(sock_filter, k);
    SIZE(sock_fprog); OFFSET(sock_fprog, len); OFFSET(sock_fprog, filter);
    SIZE(seccomp_data); OFFSET(seccomp_data, nr); OFFSET(seccomp_data, arch); OFFSET(seccomp_data, args);
    SIZE(seccomp_notif); OFFSET(seccomp_notif, id); OFFSET(seccomp_notif, pid); OFFSET(seccomp_notif, flags); OFFSET(seccomp_notif, data);
    SIZE(seccomp_notif_resp); OFFSET(seccomp_notif_resp, id); OFFSET(seccomp_notif_resp, val); OFFSET(seccomp_notif_resp, error); OFFSET(seccomp_notif_resp, flags);
    SIZE(seccomp_notif_addfd); OFFSET(seccomp_notif_addfd, id); OFFSET(seccomp_notif_addfd, flags); OFFSET(seccomp_notif_addfd, srcfd); OFFSET(seccomp_notif_addfd, newfd); OFFSET(seccomp_notif_addfd, newfd_flags);
    SIZE(iovec); OFFSET(iovec, iov_base); OFFSET(iovec, iov_len);
    SIZE(msghdr); OFFSET(msghdr, msg_name); OFFSET(msghdr, msg_namelen); OFFSET(msghdr, msg_iov); OFFSET(msghdr, msg_iovlen); OFFSET(msghdr, msg_control); OFFSET(msghdr, msg_controllen); OFFSET(msghdr, msg_flags);
    SIZE(cmsghdr); OFFSET(cmsghdr, cmsg_len); OFFSET(cmsghdr, cmsg_level); OFFSET(cmsghdr, cmsg_type);
    SIZE(sockaddr_un); OFFSET(sockaddr_un, sun_family); OFFSET(sockaddr_un, sun_path);
    SIZE(pollfd); OFFSET(pollfd, fd); OFFSET(pollfd, events); OFFSET(pollfd, revents);
    SIZE(landlock_ruleset_attr); OFFSET(landlock_ruleset_attr, handled_access_fs);
#ifdef LANDLOCK_ACCESS_NET_BIND_TCP
    OFFSET(landlock_ruleset_attr, handled_access_net);
#endif
#ifdef LANDLOCK_SCOPE_ABSTRACT_UNIX_SOCKET
    OFFSET(landlock_ruleset_attr, scoped);
#endif
    SIZE(landlock_path_beneath_attr); OFFSET(landlock_path_beneath_attr, allowed_access); OFFSET(landlock_path_beneath_attr, parent_fd);
    SIZE(open_how); OFFSET(open_how, flags); OFFSET(open_how, mode); OFFSET(open_how, resolve);
    RECORD(mw_live_request_v1);
    OFFSET(mw_live_request_v1, abi_version); OFFSET(mw_live_request_v1, struct_bytes); OFFSET(mw_live_request_v1, operation); OFFSET(mw_live_request_v1, reserved0);
    IDENTITIES(mw_live_request_v1);
    OFFSET(mw_live_request_v1, tgid); OFFSET(mw_live_request_v1, tid); OFFSET(mw_live_request_v1, worker_capacity); OFFSET(mw_live_request_v1, reserved1);
    RECORD(mw_live_frame_v1); OFFSET(mw_live_frame_v1, method_token); OFFSET(mw_live_frame_v1, location);
    RECORD(mw_live_snapshot_v1); HEADER(mw_live_snapshot_v1); IDENTITIES(mw_live_snapshot_v1);
    OFFSET(mw_live_snapshot_v1, parent_invocation_id); OFFSET(mw_live_snapshot_v1, publication_id);
    OFFSET(mw_live_snapshot_v1, tgid); OFFSET(mw_live_snapshot_v1, tid); OFFSET(mw_live_snapshot_v1, binding); OFFSET(mw_live_snapshot_v1, frame_count); OFFSET(mw_live_snapshot_v1, frames);
    printf("stride.mw_live_snapshot_v1.frames=%zu\n", sizeof(((mw_live_snapshot_v1 *)0)->frames[0]));
    RECORD(mw_live_receipt_v1); HEADER(mw_live_receipt_v1); IDENTITIES(mw_live_receipt_v1);
    OFFSET(mw_live_receipt_v1, tgid); OFFSET(mw_live_receipt_v1, tid); OFFSET(mw_live_receipt_v1, worker_capacity); OFFSET(mw_live_receipt_v1, allocated_slots);
    RECORD(mw_live_info_v1); HEADER(mw_live_info_v1);
    OFFSET(mw_live_info_v1, process_cookie); OFFSET(mw_live_info_v1, process_start_ticks);
    OFFSET(mw_live_info_v1, tgid); OFFSET(mw_live_info_v1, arch); OFFSET(mw_live_info_v1, max_workers); OFFSET(mw_live_info_v1, max_classes); OFFSET(mw_live_info_v1, max_methods); OFFSET(mw_live_info_v1, max_frames); OFFSET(mw_live_info_v1, binding_mask); OFFSET(mw_live_info_v1, reserved);
    return 0;
}
