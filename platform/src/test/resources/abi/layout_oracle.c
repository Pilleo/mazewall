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

#define SIZE(type) printf("size." #type "=%zu\n", sizeof(struct type))
#define OFFSET(type, member) printf("offset." #type "." #member "=%zu\n", offsetof(struct type, member))

int main(void) {
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
    SIZE(landlock_ruleset_attr); OFFSET(landlock_ruleset_attr, handled_access_fs); OFFSET(landlock_ruleset_attr, handled_access_net); OFFSET(landlock_ruleset_attr, scoped);
    SIZE(landlock_path_beneath_attr); OFFSET(landlock_path_beneath_attr, allowed_access); OFFSET(landlock_path_beneath_attr, parent_fd);
    SIZE(open_how); OFFSET(open_how, flags); OFFSET(open_how, mode); OFFSET(open_how, resolve);
    return 0;
}
