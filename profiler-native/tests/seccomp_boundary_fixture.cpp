#include <errno.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <cstdio>

int main() {
    const sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getpid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    const sock_fprog program{sizeof(filter) / sizeof(filter[0]), const_cast<sock_filter*>(filter)};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0 || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) != 0) {
        std::perror("seccomp");
        return 1;
    }
    errno = 0;
    const auto result = syscall(__NR_getpid);
    if (result != -1 || errno != EPERM) return 1;
    std::puts("seccomp-boundary: attempt_missed=1 observed_sys_enter=0 verdict=INCOMPLETE");
    return 0;
}
