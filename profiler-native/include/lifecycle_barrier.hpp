#pragma once

#include <atomic>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <string>
#include <thread>

namespace mazewall::profiler {

/** Test-only one-shot file barrier used to inject a transition after exact marker publication. */
class LifecycleBarrier final {
public:
    LifecycleBarrier(std::string path, std::chrono::milliseconds timeout) noexcept
        : path_(std::move(path)), timeout_(timeout) {}

    bool wait_once() noexcept {
        if (consumed_.exchange(true, std::memory_order_acq_rel)) return true;
        try {
            const auto ready_path = path_ + ".ready";
            const auto release_path = path_ + ".release";
            std::ofstream ready(ready_path, std::ios::trunc);
            if (!ready) return false;
            ready << "ready\n";
            ready.close();

            const auto deadline = std::chrono::steady_clock::now() + timeout_;
            while (std::chrono::steady_clock::now() < deadline) {
                std::error_code error;
                if (std::filesystem::exists(release_path, error) && !error) return true;
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
        } catch (...) {
            return false;
        }
        return false;
    }

private:
    std::string path_;
    std::chrono::milliseconds timeout_;
    std::atomic<bool> consumed_{false};
};

} // namespace mazewall::profiler
