#include <cerrno>
#include <cstdlib>
#include <exception>
#include <fstream>
#include <sstream>
#include <string>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

namespace {

struct ProcessIdentity {
    pid_t pid;
    unsigned long long start_time;
    unsigned long long incarnation;
};

bool write_all(int fd, const std::string& value) {
    std::size_t offset = 0;
    while (offset < value.size()) {
        const ssize_t written = write(fd, value.data() + offset, value.size() - offset);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            return false;
        }
        if (written == 0) {
            return false;
        }
        offset += static_cast<std::size_t>(written);
    }
    return true;
}

std::string read_all(int fd) {
    std::string value;
    char buffer[256];
    for (;;) {
        const ssize_t count = read(fd, buffer, sizeof(buffer));
        if (count == 0) {
            return value;
        }
        if (count < 0) {
            if (errno == EINTR) {
                continue;
            }
            return {};
        }
        value.append(buffer, static_cast<std::size_t>(count));
    }
}

bool read_start_time(pid_t pid, unsigned long long* start_time) {
    std::ifstream stat_file("/proc/" + std::to_string(pid) + "/stat");
    std::string line;
    if (!stat_file || !std::getline(stat_file, line)) {
        return false;
    }

    const std::size_t comm_end = line.rfind(')');
    if (comm_end == std::string::npos || comm_end + 2 > line.size()) {
        return false;
    }

    std::istringstream fields(line.substr(comm_end + 2));
    std::string field;
    for (int number = 3; number <= 22; ++number) {
        if (!(fields >> field)) {
            return false;
        }
        if (number == 22) {
            try {
                *start_time = std::stoull(field);
            } catch (const std::exception&) {
                return false;
            }
        }
    }
    return true;
}

bool same_process(const ProcessIdentity& left, const ProcessIdentity& right) {
    return left.pid == right.pid && left.start_time == right.start_time;
}

bool same_identity(const ProcessIdentity& left, const ProcessIdentity& right) {
    return same_process(left, right) && left.incarnation == right.incarnation;
}

bool run_exec_cycle(unsigned long long cycle) {
    int pipe_fds[2];
    if (pipe(pipe_fds) != 0) {
        return false;
    }

    const pid_t child = fork();
    if (child < 0) {
        close(pipe_fds[0]);
        close(pipe_fds[1]);
        return false;
    }
    if (child == 0) {
        close(pipe_fds[0]);
        const std::string fd = std::to_string(pipe_fds[1]);
        const std::string pid = std::to_string(getpid());
        const std::string incarnation = std::to_string(cycle * 2);
        execl("/proc/self/exe", "/proc/self/exe", "--lifecycle-stress-child", fd.c_str(),
              pid.c_str(), incarnation.c_str(), static_cast<char*>(nullptr));
        _exit(127);
    }

    close(pipe_fds[1]);
    unsigned long long start_time = 0;
    const bool sampled_before_exec = read_start_time(child, &start_time);
    const ProcessIdentity before_exec{child, start_time, cycle * 2};
    const std::string child_report = read_all(pipe_fds[0]);
    close(pipe_fds[0]);

    int status = 0;
    const bool waited = waitpid(child, &status, 0) == child;
    if (!sampled_before_exec || !waited || !WIFEXITED(status) || WEXITSTATUS(status) != 0 ||
        child_report.find("lifecycle-stress-child: ready") == std::string::npos) {
        return false;
    }

    unsigned long long after_exec_start = 0;
    if (!read_start_time(child, &after_exec_start)) {
        // The child has exited, so procfs disappearance is expected. The child report carries
        // the post-exec sample used for the identity comparison below.
        const std::string marker = " start=";
        const std::size_t marker_start = child_report.find(marker);
        if (marker_start == std::string::npos) {
            return false;
        }
        const std::size_t value_start = marker_start + marker.size();
        const std::size_t value_end = child_report.find(' ', value_start);
        try {
            after_exec_start = std::stoull(child_report.substr(value_start, value_end - value_start));
        } catch (const std::exception&) {
            return false;
        }
    }

    const ProcessIdentity after_exec{child, after_exec_start, cycle * 2 + 1};
    const bool old_exact_restored = same_identity(before_exec, after_exec);
    const bool validated_reentry = same_process(before_exec, after_exec) &&
                                   after_exec.incarnation == before_exec.incarnation + 1;
    const bool detached_old_context_rejected = !same_identity(before_exec, after_exec);
    if (old_exact_restored || !validated_reentry || !detached_old_context_rejected) {
        return false;
    }

    int replacement_pipe[2];
    if (pipe(replacement_pipe) != 0) {
        return false;
    }
    const pid_t replacement = fork();
    if (replacement < 0) {
        close(replacement_pipe[0]);
        close(replacement_pipe[1]);
        return false;
    }
    if (replacement == 0) {
        close(replacement_pipe[0]);
        unsigned long long replacement_child_start = 0;
        const bool sampled = read_start_time(getpid(), &replacement_child_start);
        const std::string report = sampled
                                        ? "replacement start=" + std::to_string(replacement_child_start) + "\n"
                                        : "replacement\n";
        const bool sent = write_all(replacement_pipe[1], report);
        close(replacement_pipe[1]);
        _exit(sent ? 0 : 127);
    }
    close(replacement_pipe[1]);
    const std::string replacement_report = read_all(replacement_pipe[0]);
    close(replacement_pipe[0]);
    status = 0;
    const bool replacement_waited = waitpid(replacement, &status, 0) == replacement;
    unsigned long long replacement_start = 0;
    const std::string replacement_marker = "replacement start=";
    const std::size_t replacement_marker_start = replacement_report.find(replacement_marker);
    bool replacement_sampled = replacement_marker_start != std::string::npos;
    if (replacement_sampled) {
        const std::size_t value_start = replacement_marker_start + replacement_marker.size();
        const std::size_t value_end = replacement_report.find('\n', value_start);
        try {
            replacement_start = std::stoull(replacement_report.substr(value_start, value_end - value_start));
        } catch (const std::exception&) {
            replacement_sampled = false;
        }
    }

    // The incarnation component makes this rejection deterministic even when the kernel
    // happens to reuse the numeric PID in a future iteration.
    const ProcessIdentity reused_pid{replacement, replacement_start, cycle * 2 + 2};
    const bool stale_pid_rejected = !same_identity(after_exec, reused_pid);
    const bool replacement_ok = !replacement_report.empty() && replacement_waited &&
                                WIFEXITED(status) && WEXITSTATUS(status) == 0 && replacement_sampled;
    return replacement_ok && stale_pid_rejected;
}

int lifecycle_stress_child(int fd, pid_t expected_pid, unsigned long long prior_incarnation) {
    unsigned long long start_time = 0;
    if (getpid() != expected_pid || !read_start_time(expected_pid, &start_time)) {
        close(fd);
        return 127;
    }
    const std::string report = "lifecycle-stress-child: ready pid=" + std::to_string(expected_pid) +
                               " start=" + std::to_string(start_time) +
                               " incarnation=" + std::to_string(prior_incarnation + 1) + "\n";
    const bool sent = write_all(fd, report);
    close(fd);
    return sent ? 0 : 127;
}

} // namespace

int main(int argc, char** argv) {
    if (argc == 5 && std::string(argv[1]) == "--lifecycle-stress-child") {
        return lifecycle_stress_child(std::stoi(argv[2]), static_cast<pid_t>(std::stoi(argv[3])),
                                      std::stoull(argv[4]));
    }
    if (argc != 1) {
        return 2;
    }

    constexpr unsigned long long cycles = 12;
    for (unsigned long long cycle = 1; cycle <= cycles; ++cycle) {
        if (!run_exec_cycle(cycle)) {
            return 1;
        }
    }
    return write_all(STDOUT_FILENO, "lifecycle-identity-stress: cycles=12 status=PASS\n") ? 0 : 1;
}
