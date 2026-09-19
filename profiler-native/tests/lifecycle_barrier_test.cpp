#include "lifecycle_barrier.hpp"

#include <atomic>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <thread>

int main() {
    namespace fs = std::filesystem;
    using namespace std::chrono_literals;

    const auto barrier_path = fs::temp_directory_path() / "mazewall-tier-e-lifecycle-barrier-test";
    const auto ready_path = barrier_path.string() + ".ready";
    const auto release_path = barrier_path.string() + ".release";
    std::error_code ignored;
    fs::remove(ready_path, ignored);
    fs::remove(release_path, ignored);

    std::atomic<bool> controller_released{false};
    std::thread controller([&] {
        for (int attempt = 0; attempt < 500; ++attempt) {
            if (fs::exists(ready_path)) {
                std::ofstream(release_path) << "release\n";
                controller_released.store(true, std::memory_order_release);
                return;
            }
            std::this_thread::sleep_for(1ms);
        }
    });

    mazewall::profiler::LifecycleBarrier barrier(barrier_path.string(), 500ms);
    const bool released = barrier.wait_once();
    controller.join();
    const bool repeated = barrier.wait_once();

    fs::remove(ready_path, ignored);
    fs::remove(release_path, ignored);
    return released && repeated && controller_released.load(std::memory_order_acquire) ? 0 : 1;
}
