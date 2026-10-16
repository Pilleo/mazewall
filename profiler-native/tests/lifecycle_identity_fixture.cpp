#include <cstdint>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <sys/wait.h>
#include <unistd.h>

namespace {

struct Identity final {
    pid_t pid = 0;
    std::uint64_t start_time = 0;
    bool operator==(const Identity&) const = default;
};

std::uint64_t process_start_time(pid_t pid) {
    std::ifstream stat("/proc/" + std::to_string(pid) + "/stat");
    if (!stat) return 0;
    std::string line;
    std::getline(stat, line);
    const auto closing_name = line.rfind(") ");
    if (closing_name == std::string::npos) return 0;
    std::istringstream fields(line.substr(closing_name + 2));
    std::string field;
    if (!(fields >> field)) return 0;
    for (int number = 4; number <= 22; ++number) {
        if (!(fields >> field)) return 0;
        if (number == 22) return std::stoull(field);
    }
    return 0;
}

class IdentityGate final {
public:
    explicit IdentityGate(Identity expected) : expected_(expected) {}
    bool accepts(Identity observed) const { return observed == expected_; }

private:
    Identity expected_;
};

int child_mode() {
    const Identity identity{getpid(), process_start_time(getpid())};
    if (identity.start_time == 0) return 2;
    std::cout << identity.pid << ' ' << identity.start_time << '\n';
    return 0;
}

} // namespace

int main(int argc, char** argv) {
    if (argc == 2 && std::string(argv[1]) == "--child") return child_mode();

    int pipe_fds[2];
    if (pipe(pipe_fds) != 0) return 3;
    const auto child = fork();
    if (child < 0) return 4;
    if (child == 0) {
        close(pipe_fds[0]);
        if (dup2(pipe_fds[1], STDOUT_FILENO) < 0) _exit(5);
        close(pipe_fds[1]);
        execl("/proc/self/exe", "/proc/self/exe", "--child", nullptr);
        _exit(6);
    }

    close(pipe_fds[1]);
    char buffer[64] = {};
    const auto bytes = read(pipe_fds[0], buffer, sizeof(buffer) - 1);
    close(pipe_fds[0]);
    const auto waited = waitpid(child, nullptr, 0);
    if (bytes <= 0 || waited != child) return 7;
    std::istringstream fields(std::string(buffer, static_cast<std::size_t>(bytes)));
    Identity observed;
    if (!(fields >> observed.pid >> observed.start_time) || observed.pid != child || observed.start_time == 0) return 8;

    const IdentityGate gate(observed);
    if (!gate.accepts(observed)) return 9;
    if (gate.accepts(Identity{observed.pid, observed.start_time + 1})) return 10;
    if (gate.accepts(Identity{observed.pid + 1, observed.start_time})) return 11;
    std::cout << "lifecycle-identity-fixture: status=PASS pid=" << observed.pid << '\n';
    return 0;
}
