#include "capture_timing_diagnostic.hpp"

#include <cassert>
#include <cstdint>
#include <limits>

using mazewall::profiler::CaptureClockReading;
using mazewall::profiler::CaptureTimingDiagnostic;

int main() {
    CaptureTimingDiagnostic diagnostic;
    std::uint32_t reads = 0;
    const auto read = [&reads] {
        ++reads;
        return CaptureClockReading{1'000, 600, 0, 4, 2, true};
    };
    const auto disabled = diagnostic.begin(false, read);
    diagnostic.finish(disabled, read);
    assert(reads == 0);
    assert(diagnostic.snapshot().attempted == 0);

    const auto start = diagnostic.begin(true, read);
    diagnostic.finish(start, [] { return CaptureClockReading{1'230, 780, 2, 5, 4, true}; });
    const auto valid = diagnostic.snapshot();
    assert(reads == 1);
    assert(valid.attempted == 1 && valid.completed == 1 && valid.invalid == 0);
    assert(valid.elapsedNs == 230 && valid.cpuNs == 180);
    assert(valid.migrations == 1 && valid.voluntarySwitches == 1 && valid.involuntarySwitches == 2);

    diagnostic.finish(diagnostic.begin(true, read), [] { return CaptureClockReading{0, 0, -1, 0, 0, false}; });
    assert(diagnostic.snapshot().attempted == 2);
    assert(diagnostic.snapshot().invalid == 1);

    CaptureTimingDiagnostic overflow;
    const auto huge = overflow.begin(true, [] { return CaptureClockReading{0, 0, 0, 0, 0, true}; });
    overflow.finish(huge, [] { return CaptureClockReading{std::numeric_limits<std::uint64_t>::max(), 10, 0, 0, 0, true}; });
    const auto second = overflow.begin(true, [] { return CaptureClockReading{0, 0, 0, 0, 0, true}; });
    overflow.finish(second, [] { return CaptureClockReading{1, 1, 0, 0, 0, true}; });
    assert(overflow.snapshot().invalid == 1);
    assert(overflow.snapshot().completed == 1);
    return 0;
}
