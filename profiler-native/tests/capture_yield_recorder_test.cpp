#include "capture_yield_recorder.hpp"

#include <cassert>
#include <cstdint>

int main() {
    using mazewall::profiler::BindingKind;
    using mazewall::profiler::CaptureYieldOutcome;
    using mazewall::profiler::CaptureYieldRecorder;

    static_assert(CaptureYieldRecorder::record_size == 16);
    static_assert(CaptureYieldRecorder::header_size == 24);
    static_assert(CaptureYieldRecorder::binding_code(BindingKind::unix_open0) == 1);
    static_assert(CaptureYieldRecorder::binding_code(BindingKind::net_accept) == 12);

    CaptureYieldRecorder recorder(2);
    assert(recorder.record(0x0102030405060708ULL, BindingKind::unix_open0, CaptureYieldOutcome::captured));
    assert(recorder.record(9, BindingKind::net_accept, CaptureYieldOutcome::capture_failed));
    assert(!recorder.record(10, BindingKind::file_read0, CaptureYieldOutcome::admission_failed));
    assert(recorder.record_count() == 2);
    assert(recorder.dropped_count() == 1);

    const auto bytes = recorder.serialize(0x01020304U);
    assert(bytes.size() == CaptureYieldRecorder::header_size + 2 * CaptureYieldRecorder::record_size);
    assert(bytes[0] == 'M');
    assert(bytes[1] == 'Z');
    assert(bytes[2] == 'C');
    assert(bytes[3] == 'Y');
    assert(CaptureYieldRecorder::read_u16_le(bytes, 4) == 1);
    assert(CaptureYieldRecorder::read_u16_le(bytes, 6) == CaptureYieldRecorder::record_size);
    assert(CaptureYieldRecorder::read_u32_le(bytes, 8) == 0x01020304U);
    assert(CaptureYieldRecorder::read_u64_le(bytes, 16) == 2);
    assert(CaptureYieldRecorder::read_u64_le(bytes, CaptureYieldRecorder::header_size) == 0x0102030405060708ULL);
    assert(CaptureYieldRecorder::read_u16_le(bytes, CaptureYieldRecorder::header_size + 8) == 1);
    assert(CaptureYieldRecorder::read_u16_le(bytes, CaptureYieldRecorder::header_size + 10) == static_cast<std::uint16_t>(CaptureYieldOutcome::captured));
}
