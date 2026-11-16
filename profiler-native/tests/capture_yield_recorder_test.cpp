#include "capture_yield_recorder.hpp"

#include <cassert>
#include <cstdint>
#include <iostream>
#include <limits>
#include <stdexcept>

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
    assert(recorder.record_count() == 2);
    assert(recorder.dropped_count() == 0);

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
    assert(CaptureYieldRecorder::read_u32_le(bytes, 12) == 0);
    assert(CaptureYieldRecorder::read_u32_le(bytes, 36) == 0);
    assert(CaptureYieldRecorder::read_u64_le(bytes, 40) == 9);
    assert(CaptureYieldRecorder::read_u16_le(bytes, 48) == 12);
    assert(CaptureYieldRecorder::read_u16_le(bytes, 50) == 2);
    assert(CaptureYieldRecorder::read_u32_le(bytes, 52) == 0);

    const auto fails = [](CaptureYieldRecorder& r) {
        try { (void)r.serialize(1); } catch (const std::runtime_error&) { return true; }
        return false;
    };
    assert(fails(recorder));
    assert(!recorder.record(11, BindingKind::unix_open0, CaptureYieldOutcome::excluded));
    assert(recorder.dropped_count() == UINT64_MAX);

    CaptureYieldRecorder overflow(2);
    assert(overflow.record(1, BindingKind::unix_open0, CaptureYieldOutcome::captured));
    assert(overflow.record(2, BindingKind::unix_open0, CaptureYieldOutcome::captured));
    for (int i = 0; i < 100; ++i) {
        assert(!overflow.record(3, BindingKind::file_read0, CaptureYieldOutcome::admission_failed));
        assert(overflow.record_count() == 2 && overflow.dropped_count() == UINT64_MAX);
    }
    assert(fails(overflow) && fails(overflow));

    CaptureYieldRecorder empty(0);
    assert(empty.serialize(7).size() == 24);
    CaptureYieldRecorder zero(0);
    assert(!zero.record(1, BindingKind::unix_open0, CaptureYieldOutcome::excluded));
    assert(zero.record_count() == 0 && zero.dropped_count() == UINT64_MAX);
    assert(fails(zero));
    bool invalid = false;
    try { CaptureYieldRecorder excessive(65'537); } catch (const std::invalid_argument&) { invalid = true; }
    assert(invalid);

    CaptureYieldRecorder full(65'536);
    for (std::uint64_t i = 1; i <= 65'536; ++i) {
        assert(full.record(i, BindingKind::unix_open0, CaptureYieldOutcome::captured));
    }
    assert(full.record_count() == 65'536 && full.dropped_count() == 0);
    const auto maximum = full.serialize(9);
    assert(maximum.size() == 1'048'600 && CaptureYieldRecorder::read_u64_le(maximum, 16) == 65'536);
    assert(CaptureYieldRecorder::read_u64_le(maximum, 24 + 65'535 * 16) == 65'536);
    for (std::size_t i = 0; i < 65'536; ++i) assert(CaptureYieldRecorder::read_u32_le(maximum, 24 + i * 16 + 12) == 0);
    std::cout << "full-capacity rows=65536 bytes=" << maximum.size() << " object=" << sizeof(full) << '\n';
}
