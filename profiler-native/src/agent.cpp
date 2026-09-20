#include <jni.h>
#include <jvmti.h>

#include <array>
#include <atomic>
#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <mutex>
#include <memory>
#include <stdexcept>
#include <string>
#include <sys/syscall.h>
#include <thread>
#include <type_traits>
#include <unordered_map>
#include <unistd.h>
#include <utility>
#include <vector>

#include "binding_manifest.hpp"
#include "capture_profile.hpp"
#include "capture_yield_recorder.hpp"
#include "invocation_registry.hpp"
#include "lifecycle_barrier.hpp"
#include "symbolization_generation.hpp"
#include "tier_e_usdt_marker.hpp"

extern "C" __attribute__((noinline, visibility("default"))) void mazewall_stack_marker(
    std::uint64_t invocation_id,
    std::uint32_t flags) noexcept {
    asm volatile("" : : "r"(invocation_id), "r"(flags) : "memory");
    MAZEWALL_TIER_E_USDT_MARKER(invocation_id, flags);
}

namespace {

std::atomic<std::uint64_t> native_bind_count{0};
std::atomic<std::uint64_t> proxied_bind_count{0};
std::atomic<std::uint64_t> stack_capture_count{0};
std::atomic<std::uint64_t> stack_capture_failures{0};
std::atomic<std::uint64_t> scope_failures{0};
std::atomic<std::uint64_t> virtual_pinned_scopes{0};
std::atomic<std::uint64_t> virtual_pinned_nanos{0};
std::atomic<std::uint64_t> benchmark_get_stack_trace_nanos{0};
std::atomic<std::uint64_t> benchmark_raw_key_nanos{0};
std::atomic<std::uint64_t> benchmark_raw_hash_nanos{0};
std::atomic<std::uint64_t> benchmark_raw_equality_nanos{0};
std::atomic<std::uint64_t> benchmark_generation_validation_nanos{0};
std::atomic<std::uint64_t> benchmark_guard_validation_nanos{0};
std::atomic<std::uint64_t> benchmark_canonical_cache_lock_wait_nanos{0};
std::atomic<std::uint64_t> benchmark_logical_metadata_nanos{0};
std::atomic<std::uint64_t> benchmark_marker_set_nanos{0};
std::atomic<std::uint64_t> benchmark_marker_clear_nanos{0};
std::atomic<std::uint64_t> benchmark_marker_admission_clear_nanos{0};
std::atomic<std::uint64_t> benchmark_marker_exact_set_nanos{0};
std::atomic<std::uint64_t> benchmark_marker_exit_clear_nanos{0};
std::atomic<std::uint64_t> benchmark_marker_admission_clear_count{0};
std::atomic<std::uint64_t> benchmark_marker_exact_set_count{0};
std::atomic<std::uint64_t> benchmark_marker_exit_clear_count{0};
std::atomic<std::uint64_t> benchmark_cache_hits{0};
std::atomic<std::uint64_t> benchmark_cache_misses{0};
std::atomic<std::uint64_t> benchmark_metadata_cache_hits{0};
std::atomic<std::uint64_t> benchmark_metadata_cache_misses{0};
std::atomic<std::uint64_t> benchmark_serialization_nanos{0};
std::atomic<std::uint64_t> benchmark_publication_bytes{0};
std::atomic<std::uint64_t> benchmark_publication_latency_nanos{0};
std::atomic<std::uint64_t> benchmark_definition_lock_wait_nanos{0};
std::atomic<std::uint64_t> benchmark_peak_retained_bytes{0};
std::atomic<std::uint64_t> benchmark_publication_count{0};
std::atomic<std::uint64_t> benchmark_coalesced_publications{0};
std::atomic<std::uint64_t> benchmark_failed_publications{0};
std::atomic<std::uint64_t> native_retention_exhaustions{0};
std::atomic<std::size_t> native_retained_bytes{0};
std::atomic<std::uint32_t> next_invocation_id{1};
std::atomic<bool> invocation_ids_exhausted{false};
std::atomic<bool> live_definitions{false};
std::atomic<bool> live_publisher_stop{false};
std::atomic<std::uint64_t> live_publication_requests{0};
std::atomic<std::uint64_t> next_publication_id{1};
std::uint32_t session_tag = 1;
std::atomic<std::uint64_t> next_loader_id{1};
mazewall::profiler::SymbolizationGeneration symbolization_generation;
thread_local bool cooperative_replacement = false;
std::atomic<jmethodID> thread_is_virtual{nullptr};
std::atomic<jmethodID> thread_id{nullptr};
jvmtiEnv* global_jvmti = nullptr;
std::string definitions_path;
std::string capture_yield_path;
std::mutex capture_yield_mutex;
std::unique_ptr<mazewall::profiler::CaptureYieldRecorder> capture_yield_recorder;
std::unique_ptr<mazewall::profiler::LifecycleBarrier> lifecycle_barrier;
std::string lifecycle_barrier_gate_path;
// Benchmark-only fault injection. The marker has already been cleared before
// this can run, so the original native call continues without stale exact state.
std::atomic<bool> force_capture_failure_once{false};
std::string capture_failure_gate_path;
std::atomic<bool> force_exec_once{false};
std::atomic<std::uint64_t> capture_yield_contention_drops{0};
mazewall::profiler::CaptureProfile capture_profile = mazewall::profiler::CaptureProfile::full();
std::atomic<bool> capture_profile_admitted{true};
std::array<std::atomic<std::uint64_t>, mazewall::profiler::BindingManifest::supported_bindings.size()> capture_profile_admitted_counts{};
std::array<std::atomic<std::uint64_t>, mazewall::profiler::BindingManifest::supported_bindings.size()> capture_profile_excluded_counts{};
std::atomic<bool> callback_suppression_probe_enabled{false};
std::atomic<bool> callback_suppression_probe_fired{false};
std::atomic<jclass> callback_suppression_probe_class{nullptr};
std::atomic<jmethodID> callback_suppression_probe_method{nullptr};
std::atomic<bool> callback_suppression_probe_ready{false};
std::string callback_suppression_probe_gate_path;
bool benchmark_phases = false;

std::uint64_t elapsed_nanos(std::chrono::steady_clock::time_point started) noexcept;

std::chrono::steady_clock::time_point benchmark_started() noexcept {
    return benchmark_phases ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
}

bool write_definitions(bool producer_closed = false) noexcept;
void request_live_publication() noexcept;

bool profile_admits(mazewall::profiler::BindingKind binding_kind) noexcept {
    if (binding_kind == mazewall::profiler::BindingKind::unsupported) return false;
    const bool admitted = capture_profile.is_full() ||
        (capture_profile_admitted.load(std::memory_order_acquire) && capture_profile.admits(binding_kind));
    const auto index = mazewall::profiler::BindingManifest::index(binding_kind);
    if (index >= mazewall::profiler::BindingManifest::supported_bindings.size()) {
        return false;
    }
    auto& count = admitted ? capture_profile_admitted_counts[index] : capture_profile_excluded_counts[index];
    count.fetch_add(1, std::memory_order_relaxed);
    return admitted;
}

bool validate_capture_profile(JNIEnv* env) noexcept {
    if (capture_profile.is_full()) return true;
    const auto clear_owned_exception = [env]() noexcept {
        if (!env->ExceptionCheck()) return false;
        env->ExceptionClear();
        return true;
    };
    const auto system_class = env->FindClass("java/lang/System");
    if (system_class == nullptr || clear_owned_exception()) return false;
    const auto get_property = env->GetStaticMethodID(system_class, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    if (get_property == nullptr || clear_owned_exception()) {
        env->DeleteLocalRef(system_class);
        return false;
    }
    const auto key = env->NewStringUTF("java.specification.version");
    if (key == nullptr || clear_owned_exception()) {
        env->DeleteLocalRef(system_class);
        return false;
    }
    const auto specification_version = static_cast<jstring>(env->CallStaticObjectMethod(system_class, get_property, key));
    if (specification_version == nullptr || clear_owned_exception()) {
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(system_class);
        return false;
    }
    const auto value = env->GetStringUTFChars(specification_version, nullptr);
    if (value == nullptr || clear_owned_exception()) {
        env->DeleteLocalRef(specification_version);
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(system_class);
        return false;
    }
    const bool matches = std::strcmp(value, "25") == 0;
    env->ReleaseStringUTFChars(specification_version, value);
    env->DeleteLocalRef(specification_version);
    env->DeleteLocalRef(key);
    env->DeleteLocalRef(system_class);
    return matches;
}

void record_capture_yield(
    std::uint64_t invocation_id,
    mazewall::profiler::BindingKind binding_kind,
    mazewall::profiler::CaptureYieldOutcome outcome) noexcept {
    if (capture_yield_recorder == nullptr || invocation_id == 0) return;
    std::unique_lock lock(capture_yield_mutex, std::try_to_lock);
    if (!lock.owns_lock()) {
        capture_yield_contention_drops.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    (void)capture_yield_recorder->record(invocation_id, binding_kind, outcome);
}

bool write_capture_yield() noexcept {
    if (capture_yield_path.empty() || capture_yield_recorder == nullptr) return true;
    try {
        std::vector<std::uint8_t> bytes;
        {
            std::lock_guard lock(capture_yield_mutex);
            bytes = capture_yield_recorder->serialize(session_tag);
        }
        const auto temporary_path = capture_yield_path + ".tmp";
        std::ofstream stream(temporary_path, std::ios::binary | std::ios::trunc);
        if (!stream) return false;
        stream.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
        stream.flush();
        if (!stream.good()) return false;
        stream.close();
        return std::rename(temporary_path.c_str(), capture_yield_path.c_str()) == 0;
    } catch (...) {
        return false;
    }
}


struct LogicalFrame final {
    std::string loader;
    std::string owner;
    std::string name;
    std::string descriptor;
    std::int64_t location = 0;
    bool native_method = false;

    bool operator==(const LogicalFrame&) const = default;
};

struct LogicalStack final {
    std::uint32_t id = 0;
    std::vector<LogicalFrame> frames;
    bool truncated = false;
    std::uint64_t symbol_epoch = 1;
};

constexpr std::size_t NATIVE_RETAINED_LIMIT_BYTES = 64ULL * 1024ULL * 1024ULL;
std::atomic<std::size_t> native_retained_limit{NATIVE_RETAINED_LIMIT_BYTES};

bool reserve_native_bytes(std::size_t bytes) noexcept {
    auto retained = native_retained_bytes.load(std::memory_order_relaxed);
    const auto limit = native_retained_limit.load(std::memory_order_relaxed);
    while (retained <= limit && bytes <= limit - retained) {
        if (native_retained_bytes.compare_exchange_weak(
                retained,
                retained + bytes,
                std::memory_order_relaxed)) {
            return true;
        }
    }
    native_retention_exhaustions.fetch_add(1, std::memory_order_relaxed);
    return false;
}

void release_native_bytes(std::size_t bytes) noexcept {
    native_retained_bytes.fetch_sub(bytes, std::memory_order_relaxed);
}

struct ScopedNativeReservation final {
    explicit ScopedNativeReservation(std::size_t bytes) : bytes_(bytes), reserved_(reserve_native_bytes(bytes)) {}
    ~ScopedNativeReservation() { if (reserved_) release_native_bytes(bytes_); }

    [[nodiscard]] bool reserved() const noexcept { return reserved_; }

private:
    std::size_t bytes_ = 0;
    bool reserved_ = false;
};

std::size_t combine_hash(std::size_t hash, std::size_t value) noexcept {
    return hash ^ (value + 0x9e3779b9 + (hash << 6) + (hash >> 2));
}

std::size_t logical_stack_hash(
    const std::vector<LogicalFrame>& frames,
    bool truncated,
    std::uint64_t symbol_epoch) noexcept {
    auto hash = combine_hash(std::hash<std::uint64_t>{}(symbol_epoch), truncated ? 1U : 0U);
    for (const auto& frame : frames) {
        hash = combine_hash(hash, std::hash<std::string>{}(frame.loader));
        hash = combine_hash(hash, std::hash<std::string>{}(frame.owner));
        hash = combine_hash(hash, std::hash<std::string>{}(frame.name));
        hash = combine_hash(hash, std::hash<std::string>{}(frame.descriptor));
        hash = combine_hash(hash, std::hash<std::int64_t>{}(frame.location));
        hash = combine_hash(hash, frame.native_method ? 1U : 0U);
    }
    return hash;
}

struct InvocationDefinition final {
    std::uint64_t id = 0;
    std::uint64_t parent_id = 0;
    std::uint32_t stack_id = 0;
    std::uint8_t failure = 0;
    std::uint64_t execution_id = 0;
};

enum class ExecutionKind : std::uint8_t {
    platform = 0,
    virtual_thread = 1,
};

struct ExecutionDefinition final {
    std::uint64_t id = 0;
    ExecutionKind kind = ExecutionKind::platform;
};

struct ExecutionContext final {
    std::uint64_t id = 0;
    ExecutionKind kind = ExecutionKind::platform;

    [[nodiscard]] bool valid() const noexcept { return id != 0; }
};

struct ExecutionEntry final {
    ExecutionContext context{};
    jweak thread = nullptr;
};

enum class EmissionMode : std::uint8_t {
    full_stream,
    unique_stack_syscall,
};

enum class StackTransport : std::uint8_t {
    dictionary,
    inline_per_invocation,
};

struct RawFrame final {
    std::uintptr_t method = 0;
    std::int64_t location = 0;

    bool operator==(const RawFrame&) const = default;
};

struct RawStack final {
    std::vector<RawFrame> frames;
    std::uint32_t flags = 0;
    std::uint64_t symbol_epoch = 1;

    bool operator==(const RawStack&) const = default;
};

struct RawStackView final {
    const jvmtiFrameInfo* frames = nullptr;
    std::size_t frame_count = 0;
    std::uint32_t flags = 0;
    std::uint64_t symbol_epoch = 1;
};

struct RawStackHash final {
    using is_transparent = void;

    static std::size_t combine(std::size_t hash, std::size_t value) noexcept {
        return hash ^ (value + 0x9e3779b9 + (hash << 6) + (hash >> 2));
    }

    std::size_t operator()(const RawStack& stack) const noexcept {
        const auto started = benchmark_started();
        std::size_t hash = stack.flags;
        hash = combine(hash, std::hash<std::uint64_t>{}(stack.symbol_epoch));
        for (const auto& frame : stack.frames) {
            hash = combine(hash, std::hash<std::uintptr_t>{}(frame.method));
            hash = combine(hash, std::hash<std::int64_t>{}(frame.location));
        }
        if (benchmark_phases) benchmark_raw_hash_nanos.fetch_add(elapsed_nanos(started), std::memory_order_relaxed);
        return hash;
    }

    std::size_t operator()(const RawStackView& stack) const noexcept {
        const auto started = benchmark_started();
        std::size_t hash = stack.flags;
        hash = combine(hash, std::hash<std::uint64_t>{}(stack.symbol_epoch));
        for (std::size_t index = 0; index < stack.frame_count; ++index) {
            hash = combine(hash, std::hash<std::uintptr_t>{}(reinterpret_cast<std::uintptr_t>(stack.frames[index].method)));
            hash = combine(hash, std::hash<std::int64_t>{}(stack.frames[index].location));
        }
        if (benchmark_phases) benchmark_raw_hash_nanos.fetch_add(elapsed_nanos(started), std::memory_order_relaxed);
        return hash;
    }
};

struct RawStackEqual final {
    using is_transparent = void;

    bool operator()(const RawStack& left, const RawStack& right) const noexcept {
        const auto started = benchmark_started();
        const auto equal = left == right;
        if (benchmark_phases) benchmark_raw_equality_nanos.fetch_add(elapsed_nanos(started), std::memory_order_relaxed);
        return equal;
    }
    [[maybe_unused]] bool operator()(const RawStack& left, const RawStackView& right) const noexcept;
    [[maybe_unused]] bool operator()(const RawStackView& left, const RawStack& right) const noexcept;
};

bool raw_stack_equals(const RawStack& stored, const RawStackView& candidate) noexcept {
    const auto started = benchmark_started();
    const auto equal = [&]() noexcept {
        if (stored.flags != candidate.flags || stored.symbol_epoch != candidate.symbol_epoch || stored.frames.size() != candidate.frame_count) return false;
        for (std::size_t index = 0; index < candidate.frame_count; ++index) {
            if (stored.frames[index] != RawFrame{reinterpret_cast<std::uintptr_t>(candidate.frames[index].method), static_cast<std::int64_t>(candidate.frames[index].location)}) return false;
        }
        return true;
    }();
    if (benchmark_phases) benchmark_raw_equality_nanos.fetch_add(elapsed_nanos(started), std::memory_order_relaxed);
    return equal;
}

bool RawStackEqual::operator()(const RawStack& left, const RawStackView& right) const noexcept {
    return raw_stack_equals(left, right);
}

bool RawStackEqual::operator()(const RawStackView& left, const RawStack& right) const noexcept {
    return raw_stack_equals(right, left);
}

struct CanonicalContext final {
    std::uint32_t stack_id = 0;
    std::vector<jweak> class_guards;
    std::size_t retained_bytes = 0;
};

std::mutex definitions_mutex;
std::vector<std::shared_ptr<const LogicalStack>> logical_stacks;
std::unordered_multimap<std::size_t, std::uint32_t> logical_stack_index;
std::vector<InvocationDefinition> invocation_definitions;
std::vector<ExecutionDefinition> execution_definitions;
std::unordered_map<jlong, ExecutionEntry> executions_by_java_thread_id;
std::atomic<std::uint64_t> next_execution_id{1};
std::unordered_map<RawStack, CanonicalContext, RawStackHash, RawStackEqual> canonical_contexts;
std::atomic<std::uint64_t> definition_generation{0};
std::mutex publication_mutex;
std::uint64_t last_published_generation = 0;
std::mutex live_publisher_wait_mutex;
std::condition_variable live_publisher_wakeup;
std::thread live_publisher;
EmissionMode emission_mode = EmissionMode::full_stream;
StackTransport stack_transport = StackTransport::dictionary;
bool canonical_cache_enabled = true;

enum class MarkerTransition : std::uint8_t {
    generic,
    admission_clear,
    exact_set,
    exit_clear,
};

void publish_stack_marker(
    std::uint64_t invocation_id,
    std::uint32_t flags,
    MarkerTransition transition = MarkerTransition::generic) noexcept {
    if (!benchmark_phases) {
        mazewall_stack_marker(invocation_id, flags);
        return;
    }
    const auto started = std::chrono::steady_clock::now();
    mazewall_stack_marker(invocation_id, flags);
    const auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now() - started).count();
    const auto nanos = static_cast<std::uint64_t>(elapsed);
    if (invocation_id == 0) {
        benchmark_marker_clear_nanos.fetch_add(nanos, std::memory_order_relaxed);
    } else {
        benchmark_marker_set_nanos.fetch_add(nanos, std::memory_order_relaxed);
    }
    switch (transition) {
        case MarkerTransition::admission_clear:
            benchmark_marker_admission_clear_nanos.fetch_add(nanos, std::memory_order_relaxed);
            benchmark_marker_admission_clear_count.fetch_add(1, std::memory_order_relaxed);
            break;
        case MarkerTransition::exact_set:
            benchmark_marker_exact_set_nanos.fetch_add(nanos, std::memory_order_relaxed);
            benchmark_marker_exact_set_count.fetch_add(1, std::memory_order_relaxed);
            break;
        case MarkerTransition::exit_clear:
            benchmark_marker_exit_clear_nanos.fetch_add(nanos, std::memory_order_relaxed);
            benchmark_marker_exit_clear_count.fetch_add(1, std::memory_order_relaxed);
            break;
        case MarkerTransition::generic:
            break;
    }
}

constexpr std::size_t MAX_METADATA_CACHE_ENTRIES = 4096;
constexpr std::size_t MAX_CANONICAL_CONTEXTS = 4096;

std::size_t frame_retained_bytes(const LogicalFrame& frame) noexcept {
    return sizeof(LogicalFrame) + frame.loader.size() + frame.owner.size() + frame.name.size() + frame.descriptor.size();
}

std::size_t stack_retained_bytes(const std::vector<LogicalFrame>& frames) noexcept {
    std::size_t bytes = sizeof(LogicalStack) + sizeof(std::shared_ptr<const LogicalStack>) + sizeof(std::pair<const std::size_t, std::uint32_t>);
    for (const auto& frame : frames) bytes += frame_retained_bytes(frame);
    return bytes;
}

std::uint64_t elapsed_nanos(std::chrono::steady_clock::time_point started) noexcept {
    const auto elapsed = std::chrono::steady_clock::now() - started;
    const auto nanos = std::chrono::duration_cast<std::chrono::nanoseconds>(elapsed).count();
    return static_cast<std::uint64_t>(nanos > 0 ? nanos : 0);
}

void deallocate(char* value) noexcept {
    if (value != nullptr) global_jvmti->Deallocate(reinterpret_cast<unsigned char*>(value));
}

std::string loader_identity(JNIEnv* env, jclass owner) {
    jobject loader = nullptr;
    if (global_jvmti->GetClassLoader(owner, &loader) != JVMTI_ERROR_NONE || loader == nullptr) {
        return "bootstrap";
    }
    jlong tag = 0;
    if (global_jvmti->GetTag(loader, &tag) != JVMTI_ERROR_NONE) {
        env->DeleteLocalRef(loader);
        return "unknown";
    }
    if (tag == 0) {
        tag = static_cast<jlong>(next_loader_id.fetch_add(1, std::memory_order_relaxed));
        if (global_jvmti->SetTag(loader, tag) != JVMTI_ERROR_NONE) tag = 0;
    }
    env->DeleteLocalRef(loader);
    return tag == 0 ? "unknown" : "loader-" + std::to_string(tag);
}

bool resolve_frame(JNIEnv* env, const jvmtiFrameInfo& raw, LogicalFrame& result, jweak* class_guard = nullptr) {
    char* name = nullptr;
    char* descriptor = nullptr;
    char* owner_descriptor = nullptr;
    jclass owner = nullptr;
    jint modifiers = 0;
    bool ok =
        global_jvmti->GetMethodName(raw.method, &name, &descriptor, nullptr) == JVMTI_ERROR_NONE &&
        global_jvmti->GetMethodDeclaringClass(raw.method, &owner) == JVMTI_ERROR_NONE && owner != nullptr &&
        global_jvmti->GetClassSignature(owner, &owner_descriptor, nullptr) == JVMTI_ERROR_NONE &&
        global_jvmti->GetMethodModifiers(raw.method, &modifiers) == JVMTI_ERROR_NONE;
    // Keep a strong local class reference for the complete metadata copy. The weak
    // cache guard is created only after all JVMTI strings and modifiers are copied.
    jclass strong_owner = owner == nullptr ? nullptr : static_cast<jclass>(env->NewLocalRef(owner));
    if (owner != nullptr && strong_owner == nullptr) ok = false;
    if (ok) {
        result = LogicalFrame{
            loader_identity(env, strong_owner), owner_descriptor, name, descriptor,
            static_cast<std::int64_t>(raw.location), (modifiers & 0x100) != 0};
        if (class_guard != nullptr) {
            *class_guard = env->NewWeakGlobalRef(strong_owner);
            ok = *class_guard != nullptr;
        }
    }
    deallocate(owner_descriptor);
    deallocate(descriptor);
    deallocate(name);
    if (strong_owner != nullptr) env->DeleteLocalRef(strong_owner);
    if (owner != nullptr) env->DeleteLocalRef(owner);
    return ok;
}

struct FrameMetadataKey final {
    std::uintptr_t method = 0;
    std::uint64_t symbol_epoch = 1;

    bool operator==(const FrameMetadataKey&) const = default;
};

struct FrameMetadataKeyHash final {
    std::size_t operator()(const FrameMetadataKey& key) const noexcept {
        auto hash = std::hash<std::uintptr_t>{}(key.method);
        hash ^= std::hash<std::uint64_t>{}(key.symbol_epoch) + 0x9e3779b9 + (hash << 6) + (hash >> 2);
        return hash;
    }
};

struct CachedFrameMetadata final {
    LogicalFrame frame;
    jweak class_guard = nullptr;
    std::size_t retained_bytes = 0;
};

std::mutex metadata_cache_mutex;
std::unordered_map<FrameMetadataKey, CachedFrameMetadata, FrameMetadataKeyHash> metadata_cache;

bool resolve_frame_cached(
    JNIEnv* env,
    const jvmtiFrameInfo& raw,
    std::uint64_t symbol_epoch,
    LogicalFrame& result,
    jweak* class_guard = nullptr) {
    const FrameMetadataKey key{reinterpret_cast<std::uintptr_t>(raw.method), symbol_epoch};
    {
        std::lock_guard lock(metadata_cache_mutex);
        const auto found = metadata_cache.find(key);
        if (found != metadata_cache.end()) {
            if (env->IsSameObject(found->second.class_guard, nullptr) == JNI_TRUE) {
                env->DeleteWeakGlobalRef(found->second.class_guard);
                release_native_bytes(found->second.retained_bytes);
                metadata_cache.erase(found);
            } else {
                result = found->second.frame;
                result.location = static_cast<std::int64_t>(raw.location);
                if (class_guard != nullptr) *class_guard = env->NewWeakGlobalRef(found->second.class_guard);
                if (benchmark_phases) benchmark_metadata_cache_hits.fetch_add(1, std::memory_order_relaxed);
                return class_guard == nullptr || *class_guard != nullptr;
            }
        }
    }

    if (benchmark_phases) benchmark_metadata_cache_misses.fetch_add(1, std::memory_order_relaxed);
    LogicalFrame resolved;
    jweak cache_guard = nullptr;
    if (!resolve_frame(env, raw, resolved, &cache_guard)) return false;
    {
        std::lock_guard lock(metadata_cache_mutex);
        if (metadata_cache.size() >= MAX_METADATA_CACHE_ENTRIES) {
            const auto evicted = metadata_cache.begin();
            env->DeleteWeakGlobalRef(evicted->second.class_guard);
            release_native_bytes(evicted->second.retained_bytes);
            metadata_cache.erase(evicted);
        }
        const auto retained_bytes = sizeof(FrameMetadataKey) + frame_retained_bytes(resolved);
        if (!reserve_native_bytes(retained_bytes)) {
            env->DeleteWeakGlobalRef(cache_guard);
            result = std::move(resolved);
            result.location = static_cast<std::int64_t>(raw.location);
            return true;
        }
        const auto [inserted, was_inserted] = metadata_cache.emplace(key, CachedFrameMetadata{resolved, cache_guard, retained_bytes});
        if (!was_inserted) {
            env->DeleteWeakGlobalRef(cache_guard);
            release_native_bytes(retained_bytes);
        }
        result = inserted->second.frame;
        result.location = static_cast<std::int64_t>(raw.location);
        if (class_guard != nullptr) *class_guard = env->NewWeakGlobalRef(inserted->second.class_guard);
        return class_guard == nullptr || *class_guard != nullptr;
    }
}

std::uint32_t intern_stack(std::vector<LogicalFrame> frames, bool truncated, std::uint64_t symbol_epoch) {
    std::lock_guard lock(definitions_mutex);
    const auto hash = logical_stack_hash(frames, truncated, symbol_epoch);
    const auto [first, last] = logical_stack_index.equal_range(hash);
    for (auto candidate = first; candidate != last; ++candidate) {
        const auto id = candidate->second;
        const auto& stack = logical_stacks[id - 1];
        if (stack->truncated == truncated && stack->symbol_epoch == symbol_epoch && stack->frames == frames) return id;
    }
    const auto id = static_cast<std::uint32_t>(logical_stacks.size() + 1);
    if (id == 0) return 0;
    if (!reserve_native_bytes(stack_retained_bytes(frames))) return 0;
    logical_stacks.push_back(std::make_shared<LogicalStack>(LogicalStack{id, std::move(frames), truncated, symbol_epoch}));
    logical_stack_index.emplace(hash, id);
    definition_generation.fetch_add(1, std::memory_order_release);
    return id;
}

bool record_invocation(InvocationDefinition definition) {
    {
        std::lock_guard lock(definitions_mutex);
        if (!reserve_native_bytes(sizeof(InvocationDefinition))) return false;
        invocation_definitions.push_back(definition);
        definition_generation.fetch_add(1, std::memory_order_release);
    }
    if (live_definitions.load(std::memory_order_acquire)) request_live_publication();
    return true;
}

ExecutionContext execution_context(JNIEnv* env, jthread thread) noexcept {
    if (env == nullptr || thread == nullptr) return {};
    auto is_virtual = thread_is_virtual.load(std::memory_order_acquire);
    auto java_thread_id = thread_id.load(std::memory_order_acquire);
    if (is_virtual == nullptr || java_thread_id == nullptr) {
        jclass thread_class = env->GetObjectClass(thread);
        if (thread_class == nullptr) return {};
        is_virtual = env->GetMethodID(thread_class, "isVirtual", "()Z");
        java_thread_id = env->GetMethodID(thread_class, "threadId", "()J");
        env->DeleteLocalRef(thread_class);
        if (is_virtual != nullptr) thread_is_virtual.store(is_virtual, std::memory_order_release);
        if (java_thread_id != nullptr) thread_id.store(java_thread_id, std::memory_order_release);
    }
    if (is_virtual == nullptr || java_thread_id == nullptr) return {};
    const auto virtual_thread = env->CallBooleanMethod(thread, is_virtual) == JNI_TRUE;
    const auto id = env->CallLongMethod(thread, java_thread_id);
    if (env->ExceptionCheck()) {
        // Preserve the application's pending exception. Profiler cleanup must
        // never turn a metadata lookup failure into exception loss.
        return {};
    }
    if (id <= 0) return {};
    const auto kind = virtual_thread ? ExecutionKind::virtual_thread : ExecutionKind::platform;
    std::lock_guard lock(definitions_mutex);
    if (const auto existing = executions_by_java_thread_id.find(id); existing != executions_by_java_thread_id.end()) {
        if (env->IsSameObject(existing->second.thread, thread) == JNI_TRUE) return existing->second.context;
        if (existing->second.thread != nullptr) env->DeleteWeakGlobalRef(existing->second.thread);
        executions_by_java_thread_id.erase(existing);
    }
    const auto execution_id = next_execution_id.fetch_add(1, std::memory_order_relaxed);
    if (execution_id == 0) return {};
    const ExecutionContext context{execution_id, kind};
    const auto guard = env->NewWeakGlobalRef(thread);
    if (guard == nullptr) return {};
    executions_by_java_thread_id.emplace(id, ExecutionEntry{context, guard});
    execution_definitions.push_back(ExecutionDefinition{execution_id, kind});
    return context;
}

void JNICALL on_virtual_thread_end(jvmtiEnv*, JNIEnv* env, jthread thread) noexcept {
    auto method = thread_id.load(std::memory_order_acquire);
    if (method == nullptr) return;
    const auto id = env->CallLongMethod(thread, method);
    if (env->ExceptionCheck()) {
        // The VM owns the pending exception; this callback must not clear it.
        return;
    }
    std::lock_guard lock(definitions_mutex);
    const auto found = executions_by_java_thread_id.find(id);
    if (found == executions_by_java_thread_id.end() || env->IsSameObject(found->second.thread, thread) != JNI_TRUE) return;
    if (found->second.thread != nullptr) env->DeleteWeakGlobalRef(found->second.thread);
    executions_by_java_thread_id.erase(found);
}

void release_guards(JNIEnv* env, const std::vector<jweak>& guards) {
    for (const auto guard : guards) if (guard != nullptr) env->DeleteWeakGlobalRef(guard);
}

std::uint32_t find_canonical_stack(JNIEnv* env, const RawStackView& stack) {
    std::unique_lock lock(definitions_mutex, std::defer_lock);
    const auto lock_started = benchmark_started();
    lock.lock();
    if (benchmark_phases) benchmark_canonical_cache_lock_wait_nanos.fetch_add(elapsed_nanos(lock_started), std::memory_order_relaxed);
    const auto found = canonical_contexts.find(stack);
    if (found == canonical_contexts.end()) return 0;
    const auto guard_started = benchmark_started();
    for (const auto guard : found->second.class_guards) {
        if (env->IsSameObject(guard, nullptr) == JNI_TRUE) {
            if (benchmark_phases) benchmark_guard_validation_nanos.fetch_add(elapsed_nanos(guard_started), std::memory_order_relaxed);
            release_guards(env, found->second.class_guards);
            release_native_bytes(found->second.retained_bytes);
            canonical_contexts.erase(found);
            return 0;
        }
    }
    if (benchmark_phases) benchmark_guard_validation_nanos.fetch_add(elapsed_nanos(guard_started), std::memory_order_relaxed);
    return found->second.stack_id;
}

void remember_canonical_context(JNIEnv* env, RawStack stack, std::uint32_t stack_id, std::vector<jweak> guards) {
    std::lock_guard lock(definitions_mutex);
    if (const auto found = canonical_contexts.find(stack); found != canonical_contexts.end()) {
        release_guards(env, guards);
        return;
    }
    if (canonical_contexts.size() >= MAX_CANONICAL_CONTEXTS) {
        const auto evicted = canonical_contexts.begin();
        release_guards(env, evicted->second.class_guards);
        release_native_bytes(evicted->second.retained_bytes);
        canonical_contexts.erase(evicted);
    }
    const auto retained_bytes = sizeof(CanonicalContext) + stack.frames.size() * sizeof(RawFrame) + guards.size() * sizeof(jweak);
    if (!reserve_native_bytes(retained_bytes)) {
        release_guards(env, guards);
        return;
    }
    canonical_contexts.emplace(std::move(stack), CanonicalContext{stack_id, std::move(guards), retained_bytes});
}

void clear_symbolization_caches(JNIEnv* env) noexcept {
    if (env == nullptr) return;
    {
        std::lock_guard lock(metadata_cache_mutex);
        for (const auto& entry : metadata_cache) {
            env->DeleteWeakGlobalRef(entry.second.class_guard);
            release_native_bytes(entry.second.retained_bytes);
        }
        metadata_cache.clear();
    }
    {
        std::lock_guard lock(definitions_mutex);
        for (const auto& entry : canonical_contexts) {
            release_guards(env, entry.second.class_guards);
            release_native_bytes(entry.second.retained_bytes);
        }
        canonical_contexts.clear();
    }
}

using UnixOpen0 = jint(JNICALL*)(JNIEnv*, jclass, jlong, jint, jint);
using UnixOpenAt0 = jint(JNICALL*)(JNIEnv*, jclass, jint, jlong, jint, jint);
using UnixClose0 = void(JNICALL*)(JNIEnv*, jclass, jint);
using UnixUnlink0 = void(JNICALL*)(JNIEnv*, jclass, jlong);
using DispatcherIo0 = jint(JNICALL*)(JNIEnv*, jclass, jobject, jlong, jint);
using CloseIntFd = void(JNICALL*)(JNIEnv*, jclass, jint);
using NetSocket0 = jint(JNICALL*)(JNIEnv*, jclass, jboolean, jboolean, jboolean, jboolean);
using NetConnect0 = jint(JNICALL*)(JNIEnv*, jclass, jboolean, jobject, jobject, jint);
using NetAccept = jint(JNICALL*)(JNIEnv*, jclass, jobject, jobject, jobjectArray);

std::atomic<UnixOpen0> original_unix_open0{nullptr};
std::atomic<UnixOpenAt0> original_unix_openat0{nullptr};
std::atomic<UnixClose0> original_unix_close0{nullptr};
std::atomic<UnixUnlink0> original_unix_unlink0{nullptr};
std::atomic<DispatcherIo0> original_file_read0{nullptr};
std::atomic<DispatcherIo0> original_file_write0{nullptr};
std::atomic<CloseIntFd> original_file_close_int_fd{nullptr};
std::atomic<DispatcherIo0> original_socket_read0{nullptr};
std::atomic<DispatcherIo0> original_socket_write0{nullptr};
std::atomic<NetSocket0> original_net_socket0{nullptr};
std::atomic<NetConnect0> original_net_connect0{nullptr};
std::atomic<NetAccept> original_net_accept{nullptr};

thread_local mazewall::profiler::InvocationRegistry invocation_registry;

class InvocationScope final {
public:
    InvocationScope(JNIEnv* env, mazewall::profiler::BindingKind binding_kind) noexcept
        : binding_kind_(binding_kind) {
        current_env_ = env;
        scope_started_ = std::chrono::steady_clock::now();
        if (invocation_ids_exhausted.load(std::memory_order_relaxed)) {
            scope_failures.fetch_add(1, std::memory_order_relaxed);
            publish_stack_marker(0, 0);
            return;
        }
        const auto counter = next_invocation_id.fetch_add(1, std::memory_order_relaxed);
        if (counter == 0) {
            invocation_ids_exhausted.store(true, std::memory_order_relaxed);
            scope_failures.fetch_add(1, std::memory_order_relaxed);
            publish_stack_marker(0, 0);
            return;
        }
        const auto id = (static_cast<std::uint64_t>(session_tag) << 32) | counter;
        invocation_id_ = id;
        jthread current_thread = nullptr;
        if (global_jvmti == nullptr || global_jvmti->GetCurrentThread(&current_thread) != JVMTI_ERROR_NONE || current_thread == nullptr) {
            scope_failures.fetch_add(1, std::memory_order_relaxed);
            publish_stack_marker(0, 0);
            return;
        }
        current_thread_ = current_thread;
        execution_ = execution_context(env, current_thread);
        if (!execution_.valid()) {
            scope_failures.fetch_add(1, std::memory_order_relaxed);
            publish_stack_marker(0, 0);
            return;
        }
        flags_ = execution_.kind == ExecutionKind::virtual_thread ? 1U : 0U;
        virtual_scope_ = execution_.kind == ExecutionKind::virtual_thread;
        scope_ = invocation_registry.enter(id, execution_.id);
        // Clear the parent's attribution before JVMTI work. Any agent-internal
        // syscall is therefore UNKNOWN rather than incorrectly assigned.
        publish_stack_marker(0, 0, MarkerTransition::admission_clear);
        if (!scope_.valid()) {
            scope_failures.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        if (!profile_admits(binding_kind_)) {
            capture_yield_outcome_ = mazewall::profiler::CaptureYieldOutcome::excluded;
            publish_stack_marker(0, flags_);
            return;
        }
        const auto capture = capture_stack(env, scope_.id);
        const auto captured_context_id = capture.context_id;
        const auto captured_stack_id = capture.stack_id;
        const auto capture_failure = capture.failure;
        const auto captured_new_context = capture.new_context;
        capture_yield_outcome_ = capture_failure == 0
            ? mazewall::profiler::CaptureYieldOutcome::captured
            : mazewall::profiler::CaptureYieldOutcome::capture_failed;
        if (captured_context_id != scope_.id && !invocation_registry.replace_current(scope_, captured_context_id)) {
            scope_failures.fetch_add(1, std::memory_order_relaxed);
            publish_stack_marker(0, 0);
            return;
        }
        if (captured_new_context) {
            if (!record_invocation(InvocationDefinition{scope_.id, scope_.parent_id, captured_stack_id, capture_failure, execution_.id})) {
                scope_failures.fetch_add(1, std::memory_order_relaxed);
                publish_stack_marker(0, 0);
                return;
            }
        }
        publish_stack_marker(scope_.valid() ? scope_.id : 0, flags_, MarkerTransition::exact_set);
        invoke_callback_suppression_probe();
        if (binding_kind_ == mazewall::profiler::BindingKind::unix_openat0 && lifecycle_barrier != nullptr &&
            !lifecycle_barrier_gate_path.empty() && std::ifstream(lifecycle_barrier_gate_path).good() &&
            !lifecycle_barrier->wait_once()) {
            scope_failures.fetch_add(1, std::memory_order_relaxed);
        }
        // Test-only lifecycle fault injection. The barrier makes the captured definition
        // observable before replacing the JVM image; exec only returns on failure, in which
        // case the original native operation remains available to continue normally.
        if (binding_kind_ == mazewall::profiler::BindingKind::unix_openat0 &&
            !lifecycle_barrier_gate_path.empty() && std::ifstream(lifecycle_barrier_gate_path).good() &&
            force_exec_once.exchange(false, std::memory_order_acq_rel)) {
            const char* const arguments[] = {"sh", "-c", "for i in 1 2 3; do :; done; sleep 1", nullptr};
            ::execv("/bin/sh", const_cast<char* const*>(arguments));
            scope_failures.fetch_add(1, std::memory_order_relaxed);
        }
    }

    ~InvocationScope() {
        publish_stack_marker(0, 0, MarkerTransition::exit_clear);
        if (!scope_.valid()) {
            publish_stack_marker(invocation_registry.current_id(), flags_);
            record_virtual_duration();
            record_capture_yield(invocation_id_, binding_kind_, capture_yield_outcome_);
            release_current_thread();
            return;
        }
        jthread exit_thread = nullptr;
        const auto current_execution =
            global_jvmti != nullptr && global_jvmti->GetCurrentThread(&exit_thread) == JVMTI_ERROR_NONE
                ? execution_context_from_current(exit_thread)
                : ExecutionContext{};
        if (exit_thread != nullptr) current_env_->DeleteLocalRef(exit_thread);
        release_current_thread();
        if (!current_execution.valid() || current_execution.id != execution_.id ||
            !invocation_registry.matches_current_execution(execution_.id)) {
            scope_failures.fetch_add(1, std::memory_order_relaxed);
            invocation_registry.invalidate();
            record_virtual_duration();
            record_capture_yield(invocation_id_, binding_kind_, capture_yield_outcome_);
            return;
        }
        const bool restored = invocation_registry.leave(scope_);
        publish_stack_marker(restored ? invocation_registry.current_id() : 0, flags_);
        record_virtual_duration();
        record_capture_yield(invocation_id_, binding_kind_, capture_yield_outcome_);
    }

private:
    struct Capture final {
        std::uint32_t stack_id = 0;
        std::uint8_t failure = 1;
        std::uint64_t context_id = 0;
        bool new_context = false;
    };

    ExecutionContext execution_context_from_current(jthread thread) noexcept {
        return execution_context(current_env_, thread);
    }

    void release_current_thread() noexcept {
        if (current_thread_ != nullptr) {
            current_env_->DeleteLocalRef(current_thread_);
            current_thread_ = nullptr;
        }
    }

    void record_virtual_duration() noexcept {
        if (!virtual_scope_) return;
        const auto elapsed = std::chrono::steady_clock::now() - scope_started_;
        const auto nanos = std::chrono::duration_cast<std::chrono::nanoseconds>(elapsed).count();
        virtual_pinned_scopes.fetch_add(1, std::memory_order_relaxed);
        virtual_pinned_nanos.fetch_add(static_cast<std::uint64_t>(nanos > 0 ? nanos : 0), std::memory_order_relaxed);
    }

    void invoke_callback_suppression_probe() noexcept {
        if (binding_kind_ != mazewall::profiler::BindingKind::unix_openat0 ||
            !callback_suppression_probe_enabled.load(std::memory_order_acquire) ||
            !callback_suppression_probe_ready.load(std::memory_order_acquire) ||
            callback_suppression_probe_gate_path.empty()) {
            return;
        }

        // This test-only path models a supported native wrapper calling back into Java. The
        // outer marker and nesting state must be inactive before gate I/O or arbitrary callback
        // JVM work. A nested proxy may establish its own exact boundary, but this outer scope is
        // never restored implicitly after the callback returns.
        publish_stack_marker(0, 0, MarkerTransition::admission_clear);
        invocation_registry.suppress();
        scope_ = {};
        if (!std::ifstream(callback_suppression_probe_gate_path).good() ||
            callback_suppression_probe_fired.exchange(true, std::memory_order_acq_rel)) {
            return;
        }
        const auto callback_class = callback_suppression_probe_class.load(std::memory_order_acquire);
        const auto callback = callback_suppression_probe_method.load(std::memory_order_acquire);
        if (callback_class == nullptr || callback == nullptr) {
            scope_failures.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        current_env_->CallStaticVoidMethod(callback_class, callback);
        if (current_env_->ExceptionCheck()) {
            // A callback exception belongs to application code and must remain pending.
            scope_failures.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        // A deterministic unsupported syscall for the rootful callback fixture. The marker and
        // registry remain suppressed, so this must be observed as inactive rather than inherit
        // the outer native invocation.
        (void)::syscall(SYS_getpid);
    }

    Capture capture_stack(JNIEnv* env, std::uint64_t proposed_context_id) noexcept {
        if (binding_kind_ == mazewall::profiler::BindingKind::unix_openat0 &&
            force_capture_failure_once.load(std::memory_order_acquire) &&
            (capture_failure_gate_path.empty() || std::ifstream(capture_failure_gate_path).good()) &&
            force_capture_failure_once.exchange(false, std::memory_order_acq_rel)) {
            stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
            return {};
        }
        if (global_jvmti == nullptr) {
            stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
            return {};
        }

        if (current_thread_ == nullptr) {
            stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
            return {};
        }
        if (execution_context(env, current_thread_).id != execution_.id) {
            stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
            return Capture{0, 4, 0, false};
        }

        std::array<jvmtiFrameInfo, 129> frames{};
        jint frame_count = 0;
        const auto capture_lease = symbolization_generation.begin_capture();
        if (!capture_lease.valid()) {
            stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
            return Capture{0, 5, 0, false};
        }
        const auto capture_epoch = capture_lease.value;
        const auto stack_trace_started = benchmark_started();
        const auto result = global_jvmti->GetStackTrace(
            current_thread_,
            0,
            static_cast<jint>(frames.size()),
            frames.data(),
            &frame_count);
        if (benchmark_phases) benchmark_get_stack_trace_nanos.fetch_add(elapsed_nanos(stack_trace_started), std::memory_order_relaxed);
        if (result != JVMTI_ERROR_NONE || frame_count <= 0) {
            stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
            return {};
        }
        const bool truncated = frame_count == static_cast<jint>(frames.size());
        // A truncated dictionary entry remains useful for diagnostics, but the
        // session must never claim complete capture merely because no syscall
        // happened to reference it before shutdown.
        if (truncated) stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
        const auto retained = truncated ? frames.size() - 1 : static_cast<std::size_t>(frame_count);
        const auto raw_key_started = benchmark_started();
        const RawStackView raw{frames.data(), retained, flags_, capture_epoch};
        if (!truncated) {
            if (canonical_cache_enabled) {
                if (const auto existing = find_canonical_stack(env, raw); existing != 0) {
                    const auto validation_started = benchmark_started();
                    const auto generation_valid = symbolization_generation.validate(capture_lease);
                    if (benchmark_phases) benchmark_generation_validation_nanos.fetch_add(elapsed_nanos(validation_started), std::memory_order_relaxed);
                    if (!generation_valid) {
                        stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
                        return Capture{0, 5, 0, false};
                    }
                    if (benchmark_phases) {
                        benchmark_cache_hits.fetch_add(1, std::memory_order_relaxed);
                        benchmark_raw_key_nanos.fetch_add(elapsed_nanos(raw_key_started), std::memory_order_relaxed);
                    }
                    stack_capture_count.fetch_add(1, std::memory_order_relaxed);
                    return Capture{existing, 0, proposed_context_id, true};
                }
            }
            if (benchmark_phases) benchmark_cache_misses.fetch_add(1, std::memory_order_relaxed);
        }
        if (benchmark_phases) benchmark_raw_key_nanos.fetch_add(elapsed_nanos(raw_key_started), std::memory_order_relaxed);
        std::vector<LogicalFrame> logical;
        std::vector<jweak> class_guards;
        logical.reserve(retained);
        if (emission_mode == EmissionMode::unique_stack_syscall) class_guards.reserve(retained);
        const auto logical_metadata_started = benchmark_started();
        for (std::size_t index = 0; index < retained; ++index) {
            LogicalFrame frame;
            jweak guard = nullptr;
            if (!resolve_frame_cached(env, frames[index], raw.symbol_epoch, frame,
                    !truncated ? &guard : nullptr)) {
                release_guards(env, class_guards);
                if (benchmark_phases) benchmark_logical_metadata_nanos.fetch_add(elapsed_nanos(logical_metadata_started), std::memory_order_relaxed);
                stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
                return {};
            }
            if (guard != nullptr) class_guards.push_back(guard);
            logical.push_back(std::move(frame));
        }
        if (benchmark_phases) benchmark_logical_metadata_nanos.fetch_add(elapsed_nanos(logical_metadata_started), std::memory_order_relaxed);
        const auto validation_started = benchmark_started();
        const auto generation_valid = symbolization_generation.validate(capture_lease);
        if (benchmark_phases) benchmark_generation_validation_nanos.fetch_add(elapsed_nanos(validation_started), std::memory_order_relaxed);
        if (!generation_valid) {
            release_guards(env, class_guards);
            stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
            return Capture{0, 5, 0, false};
        }
        const auto stack_id = intern_stack(std::move(logical), truncated, raw.symbol_epoch);
        if (stack_id == 0) {
            release_guards(env, class_guards);
            stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
            return {};
        }
        stack_capture_count.fetch_add(1, std::memory_order_relaxed);
        if (!truncated && canonical_cache_enabled) {
            // A canonical stack is reusable, but an InvocationId is not: it carries the
            // opaque logical execution owner. Reusing an invocation from another virtual
            // thread would turn a correct stack match into wrong thread attribution.
            RawStack owned_raw;
            owned_raw.flags = raw.flags;
            owned_raw.symbol_epoch = raw.symbol_epoch;
            owned_raw.frames.reserve(raw.frame_count);
            for (std::size_t index = 0; index < raw.frame_count; ++index) {
                owned_raw.frames.push_back(RawFrame{reinterpret_cast<std::uintptr_t>(raw.frames[index].method), static_cast<std::int64_t>(raw.frames[index].location)});
            }
            remember_canonical_context(env, std::move(owned_raw), stack_id, std::move(class_guards));
        } else {
            release_guards(env, class_guards);
        }
        return Capture{stack_id, 0, proposed_context_id, true};
    }

    mazewall::profiler::InvocationRegistry::Scope scope_{};
    JNIEnv* current_env_ = nullptr;
    jthread current_thread_ = nullptr;
    ExecutionContext execution_{};
    std::uint32_t flags_ = 0;
    std::uint64_t invocation_id_ = 0;
    mazewall::profiler::BindingKind binding_kind_;
    mazewall::profiler::CaptureYieldOutcome capture_yield_outcome_ =
        mazewall::profiler::CaptureYieldOutcome::admission_failed;
    std::chrono::steady_clock::time_point scope_started_{};
    bool virtual_scope_ = false;
};

jint JNICALL proxy_unix_open0(JNIEnv* env, jclass owner, jlong path, jint flags, jint mode) {
    InvocationScope invocation(env, mazewall::profiler::BindingKind::unix_open0);
    const auto original = original_unix_open0.load(std::memory_order_acquire);
    return original == nullptr ? -1 : original(env, owner, path, flags, mode);
}

jint JNICALL proxy_unix_openat0(JNIEnv* env, jclass owner, jint dirfd, jlong path, jint flags, jint mode) {
    InvocationScope invocation(env, mazewall::profiler::BindingKind::unix_openat0);
    const auto original = original_unix_openat0.load(std::memory_order_acquire);
    return original == nullptr ? -1 : original(env, owner, dirfd, path, flags, mode);
}

void JNICALL proxy_unix_close0(JNIEnv* env, jclass owner, jint fd) {
    InvocationScope invocation(env, mazewall::profiler::BindingKind::unix_close0);
    const auto original = original_unix_close0.load(std::memory_order_acquire);
    if (original != nullptr) original(env, owner, fd);
}

void JNICALL proxy_unix_unlink0(JNIEnv* env, jclass owner, jlong path) {
    InvocationScope invocation(env, mazewall::profiler::BindingKind::unix_unlink0);
    const auto original = original_unix_unlink0.load(std::memory_order_acquire);
    if (original != nullptr) original(env, owner, path);
}

jint JNICALL proxy_file_read0(JNIEnv* env, jclass owner, jobject fd, jlong address, jint length) {
    InvocationScope invocation(env, mazewall::profiler::BindingKind::file_read0); return original_file_read0.load()(env, owner, fd, address, length);
}
jint JNICALL proxy_file_write0(JNIEnv* env, jclass owner, jobject fd, jlong address, jint length) {
    InvocationScope invocation(env, mazewall::profiler::BindingKind::file_write0); return original_file_write0.load()(env, owner, fd, address, length);
}
void JNICALL proxy_file_close_int_fd(JNIEnv* env, jclass owner, jint fd) {
    InvocationScope invocation(env, mazewall::profiler::BindingKind::file_close_int_fd); original_file_close_int_fd.load()(env, owner, fd);
}
jint JNICALL proxy_socket_read0(JNIEnv* env, jclass owner, jobject fd, jlong address, jint length) {
    InvocationScope invocation(env, mazewall::profiler::BindingKind::socket_read0); return original_socket_read0.load()(env, owner, fd, address, length);
}
jint JNICALL proxy_socket_write0(JNIEnv* env, jclass owner, jobject fd, jlong address, jint length) {
    InvocationScope invocation(env, mazewall::profiler::BindingKind::socket_write0); return original_socket_write0.load()(env, owner, fd, address, length);
}
jint JNICALL proxy_net_socket0(JNIEnv* env, jclass owner, jboolean ipv6, jboolean stream, jboolean reuse, jboolean loopback) {
    InvocationScope invocation(env, mazewall::profiler::BindingKind::net_socket0); return original_net_socket0.load()(env, owner, ipv6, stream, reuse, loopback);
}
jint JNICALL proxy_net_connect0(JNIEnv* env, jclass owner, jboolean ipv6, jobject fd, jobject address, jint port) {
    InvocationScope invocation(env, mazewall::profiler::BindingKind::net_connect0); return original_net_connect0.load()(env, owner, ipv6, fd, address, port);
}
jint JNICALL proxy_net_accept(JNIEnv* env, jclass owner, jobject fd, jobject accepted, jobjectArray addresses) {
    InvocationScope invocation(env, mazewall::profiler::BindingKind::net_accept); return original_net_accept.load()(env, owner, fd, accepted, addresses);
}

bool replace_binding(
    mazewall::profiler::BindingKind kind,
    void* address,
    void** new_address) noexcept {
    using mazewall::profiler::BindingKind;
    switch (kind) {
        case BindingKind::unix_open0:
            original_unix_open0.store(reinterpret_cast<UnixOpen0>(address), std::memory_order_release);
            *new_address = reinterpret_cast<void*>(proxy_unix_open0);
            break;
        case BindingKind::unix_openat0:
            original_unix_openat0.store(reinterpret_cast<UnixOpenAt0>(address), std::memory_order_release);
            *new_address = reinterpret_cast<void*>(proxy_unix_openat0);
            break;
        case BindingKind::unix_close0:
            original_unix_close0.store(reinterpret_cast<UnixClose0>(address), std::memory_order_release);
            *new_address = reinterpret_cast<void*>(proxy_unix_close0);
            break;
        case BindingKind::unix_unlink0:
            original_unix_unlink0.store(reinterpret_cast<UnixUnlink0>(address), std::memory_order_release);
            *new_address = reinterpret_cast<void*>(proxy_unix_unlink0);
            break;
        case BindingKind::file_read0:
            original_file_read0.store(reinterpret_cast<DispatcherIo0>(address)); *new_address = reinterpret_cast<void*>(proxy_file_read0); break;
        case BindingKind::file_write0:
            original_file_write0.store(reinterpret_cast<DispatcherIo0>(address)); *new_address = reinterpret_cast<void*>(proxy_file_write0); break;
        case BindingKind::file_close_int_fd:
            original_file_close_int_fd.store(reinterpret_cast<CloseIntFd>(address)); *new_address = reinterpret_cast<void*>(proxy_file_close_int_fd); break;
        case BindingKind::socket_read0:
            original_socket_read0.store(reinterpret_cast<DispatcherIo0>(address)); *new_address = reinterpret_cast<void*>(proxy_socket_read0); break;
        case BindingKind::socket_write0:
            original_socket_write0.store(reinterpret_cast<DispatcherIo0>(address)); *new_address = reinterpret_cast<void*>(proxy_socket_write0); break;
        case BindingKind::net_socket0:
            original_net_socket0.store(reinterpret_cast<NetSocket0>(address)); *new_address = reinterpret_cast<void*>(proxy_net_socket0); break;
        case BindingKind::net_connect0:
            original_net_connect0.store(reinterpret_cast<NetConnect0>(address)); *new_address = reinterpret_cast<void*>(proxy_net_connect0); break;
        case BindingKind::net_accept:
            original_net_accept.store(reinterpret_cast<NetAccept>(address)); *new_address = reinterpret_cast<void*>(proxy_net_accept); break;
        default:
            return false;
    }
    proxied_bind_count.fetch_add(1, std::memory_order_relaxed);
    return true;
}

void JNICALL on_native_method_bind(
    jvmtiEnv* jvmti,
    JNIEnv* env,
    jthread,
    jmethodID method,
    void* address,
    void** new_address) noexcept {
    native_bind_count.fetch_add(1, std::memory_order_relaxed);
    if (env == nullptr || new_address == nullptr) return;

    char* name = nullptr;
    char* descriptor = nullptr;
    jclass owner = nullptr;
    char* owner_descriptor = nullptr;
    if (jvmti->GetMethodName(method, &name, &descriptor, nullptr) != JVMTI_ERROR_NONE ||
        jvmti->GetMethodDeclaringClass(method, &owner) != JVMTI_ERROR_NONE ||
        jvmti->GetClassSignature(owner, &owner_descriptor, nullptr) != JVMTI_ERROR_NONE) {
        if (name != nullptr) jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
        if (descriptor != nullptr) jvmti->Deallocate(reinterpret_cast<unsigned char*>(descriptor));
        if (owner != nullptr) env->DeleteLocalRef(owner);
        return;
    }

    const auto kind = mazewall::profiler::BindingManifest::classify(owner_descriptor, name, descriptor);
    (void)replace_binding(kind, address, new_address);

    jvmti->Deallocate(reinterpret_cast<unsigned char*>(owner_descriptor));
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(descriptor));
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
    env->DeleteLocalRef(owner);
}

void JNICALL on_class_file_load(
    jvmtiEnv*,
    JNIEnv*,
    jclass class_being_redefined,
    jobject,
    const char*,
    jobject,
    jint,
    const unsigned char*,
    jint*,
    unsigned char**) noexcept {
    // Initial class loading can close at ClassPrepare. A replacement callback
    // has no post-install completion signal, so it remains unproven and exact
    // captures fail closed until an explicit resynchronization boundary.
    if (class_being_redefined != nullptr && !cooperative_replacement) {
        (void)symbolization_generation.begin_replacement(true);
    }
}

void JNICALL on_class_prepare(jvmtiEnv* jvmti, JNIEnv* env, jthread, jclass klass) noexcept {
    (void)symbolization_generation.complete_replacement();
    if (!callback_suppression_probe_enabled.load(std::memory_order_acquire) ||
        callback_suppression_probe_class.load(std::memory_order_acquire) != nullptr) {
        return;
    }
    char* signature = nullptr;
    if (jvmti == nullptr || jvmti->GetClassSignature(klass, &signature, nullptr) != JVMTI_ERROR_NONE || signature == nullptr) {
        return;
    }
    const bool is_probe_class = std::strcmp(
        signature,
        "Lio/mazewall/profiler/tierE/stress/TierESharedOpenatOracleWorkload;") == 0;
    (void)jvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
    if (!is_probe_class) return;
    const auto method = env->GetStaticMethodID(klass, "callbackSuppressionProbe", "()V");
    if (method == nullptr) {
        // Method lookup belongs wholly to this optional test fixture.
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    const auto global_class = static_cast<jclass>(env->NewGlobalRef(klass));
    if (global_class == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    jclass expected = nullptr;
    if (callback_suppression_probe_class.compare_exchange_strong(expected, global_class, std::memory_order_acq_rel)) {
        callback_suppression_probe_method.store(method, std::memory_order_release);
        callback_suppression_probe_ready.store(true, std::memory_order_release);
    } else {
        env->DeleteGlobalRef(global_class);
    }
}

void JNICALL on_vm_init(jvmtiEnv*, JNIEnv* env, jthread) noexcept {
    if (capture_profile.is_full()) return;
    if (!validate_capture_profile(env)) {
        std::fprintf(stderr, "mazewall-stack-agent: capture profile validation failed after VM initialization\\n");
        return;
    }
    capture_profile_admitted.store(true, std::memory_order_release);
}

bool configure_native_bind_events(jvmtiEnv* jvmti) noexcept {
    jvmtiCapabilities capabilities{};
    capabilities.can_generate_native_method_bind_events = 1;
    capabilities.can_tag_objects = 1;
    capabilities.can_support_virtual_threads = 1;
    capabilities.can_generate_all_class_hook_events = 1;
    if (jvmti->AddCapabilities(&capabilities) != JVMTI_ERROR_NONE) {
        return false;
    }

    jvmtiEventCallbacks callbacks{};
    callbacks.NativeMethodBind = on_native_method_bind;
    callbacks.ClassFileLoadHook = on_class_file_load;
    callbacks.ClassPrepare = on_class_prepare;
    callbacks.VMInit = on_vm_init;
    callbacks.VirtualThreadEnd = on_virtual_thread_end;
    if (jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE) {
        return false;
    }

    return jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, nullptr) == JVMTI_ERROR_NONE &&
        jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr) == JVMTI_ERROR_NONE &&
        jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_NATIVE_METHOD_BIND, nullptr) == JVMTI_ERROR_NONE &&
        jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, nullptr) == JVMTI_ERROR_NONE &&
        jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_END, nullptr) == JVMTI_ERROR_NONE;
}

template<typename T>
void append_le(std::vector<std::uint8_t>& output, T value) {
    using Unsigned = std::make_unsigned_t<T>;
    auto bits = static_cast<Unsigned>(value);
    for (std::size_t index = 0; index < sizeof(T); ++index) {
        output.push_back(static_cast<std::uint8_t>((bits >> (index * 8)) & 0xff));
    }
}

void append_string(std::vector<std::uint8_t>& output, const std::string& value) {
    if (value.size() > UINT16_MAX) throw std::runtime_error("JVMTI string exceeds dictionary wire limit");
    append_le<std::uint16_t>(output, static_cast<std::uint16_t>(value.size()));
    output.insert(output.end(), value.begin(), value.end());
}

std::size_t begin_record(std::vector<std::uint8_t>& output, std::uint8_t type) {
    output.push_back(type);
    const auto length_offset = output.size();
    append_le<std::uint32_t>(output, 0);
    return length_offset;
}

void finish_record(std::vector<std::uint8_t>& output, std::size_t length_offset) {
    const auto length = output.size() - length_offset - sizeof(std::uint32_t);
    if (length > UINT32_MAX) throw std::runtime_error("dictionary record exceeds wire limit");
    for (std::size_t index = 0; index < sizeof(std::uint32_t); ++index) {
        output[length_offset + index] = static_cast<std::uint8_t>((length >> (index * 8)) & 0xff);
    }
}

void append_stack_payload(std::vector<std::uint8_t>& record, const LogicalStack& stack) {
    append_le<std::uint32_t>(record, stack.id);
    record.push_back(stack.truncated ? 1 : 0);
    append_le<std::uint16_t>(record, static_cast<std::uint16_t>(stack.frames.size()));
    for (const auto& frame : stack.frames) {
        append_string(record, frame.loader);
        append_string(record, frame.owner);
        append_string(record, frame.name);
        append_string(record, frame.descriptor);
        append_le<std::int64_t>(record, frame.location);
        record.push_back(frame.native_method ? 1 : 0);
    }
}

struct DefinitionSnapshot final {
    std::vector<std::shared_ptr<const LogicalStack>> stacks;
    std::vector<InvocationDefinition> invocations;
    std::vector<ExecutionDefinition> executions;
    std::uint64_t generation = 0;
    std::size_t retained_bytes = 0;
};

std::size_t estimate_retained_bytes(const DefinitionSnapshot& snapshot) {
    std::size_t bytes = snapshot.invocations.size() * sizeof(InvocationDefinition) +
        snapshot.executions.size() * sizeof(ExecutionDefinition);
    for (const auto& stack : snapshot.stacks) {
        bytes += sizeof(LogicalStack) + stack->frames.size() * sizeof(LogicalFrame);
        for (const auto& frame : stack->frames) {
            bytes += frame.loader.size() + frame.owner.size() + frame.name.size() + frame.descriptor.size();
        }
    }
    return bytes;
}

bool checked_add(std::size_t& total, std::size_t value) noexcept {
    if (value > NATIVE_RETAINED_LIMIT_BYTES - total) return false;
    total += value;
    return true;
}

bool estimate_stack_wire_bytes(const LogicalStack& stack, std::size_t& total) noexcept {
    if (!checked_add(total, sizeof(std::uint32_t) + sizeof(std::uint8_t) + sizeof(std::uint16_t))) return false;
    for (const auto& frame : stack.frames) {
        if (!checked_add(total, sizeof(std::uint16_t) + frame.loader.size())) return false;
        if (!checked_add(total, sizeof(std::uint16_t) + frame.owner.size())) return false;
        if (!checked_add(total, sizeof(std::uint16_t) + frame.name.size())) return false;
        if (!checked_add(total, sizeof(std::uint16_t) + frame.descriptor.size())) return false;
        if (!checked_add(total, sizeof(std::int64_t) + sizeof(std::uint8_t))) return false;
    }
    return true;
}

bool estimate_snapshot_wire_bytes(const DefinitionSnapshot& snapshot, std::size_t& total) noexcept {
    total = 6;
    if (stack_transport == StackTransport::dictionary) {
        for (const auto& stack : snapshot.stacks) {
            if (!checked_add(total, 5) || !estimate_stack_wire_bytes(*stack, total)) return false;
        }
    }
    for (const auto& execution : snapshot.executions) {
        if (!checked_add(total, 5 + sizeof(execution.id) + sizeof(std::uint8_t))) return false;
    }
    for (const auto& invocation : snapshot.invocations) {
        if (!checked_add(total, 5 + sizeof(invocation.id) + sizeof(invocation.parent_id) +
                sizeof(invocation.stack_id) + sizeof(invocation.failure) + sizeof(invocation.execution_id))) return false;
        if (stack_transport == StackTransport::inline_per_invocation && invocation.failure == 0) {
            if (invocation.stack_id == 0 || invocation.stack_id > snapshot.stacks.size()) {
                std::fprintf(
                    stderr,
                    "mazewall-stack-agent: inline definition references stack id %u with %zu snapshot stacks (failure=%u)\n",
                    invocation.stack_id,
                    snapshot.stacks.size(),
                    invocation.failure);
                return false;
            }
            if (!estimate_stack_wire_bytes(*snapshot.stacks[invocation.stack_id - 1], total)) return false;
        }
    }
    if (!checked_add(total, 5 + 4 * sizeof(std::uint64_t))) return false;
    return !live_definitions.load(std::memory_order_acquire) || checked_add(total, 40);
}

DefinitionSnapshot snapshot_definitions() {
    const auto lock_started = benchmark_started();
    std::lock_guard lock(definitions_mutex);
    if (benchmark_phases) {
        benchmark_definition_lock_wait_nanos.fetch_add(elapsed_nanos(lock_started), std::memory_order_relaxed);
    }
    DefinitionSnapshot snapshot{
        logical_stacks,
        invocation_definitions,
        execution_definitions,
        definition_generation.load(std::memory_order_acquire),
    };
    snapshot.retained_bytes = estimate_retained_bytes(snapshot);
    auto observed = benchmark_peak_retained_bytes.load(std::memory_order_relaxed);
    while (observed < snapshot.retained_bytes &&
        !benchmark_peak_retained_bytes.compare_exchange_weak(observed, snapshot.retained_bytes, std::memory_order_relaxed)) {}
    return snapshot;
}

bool write_definitions(bool producer_closed) noexcept {
    if (definitions_path.empty()) return true;
    try {
        const auto publication_started = benchmark_started();
        const auto snapshot = snapshot_definitions();
        std::lock_guard publication_lock(publication_mutex);
        if ((!producer_closed && snapshot.generation <= last_published_generation) ||
            (producer_closed && snapshot.generation < last_published_generation)) {
            return true;
        }
        const auto temporary_path = definitions_path + ".tmp";
        std::ofstream stream(temporary_path, std::ios::binary | std::ios::trunc);
        if (!stream) {
            std::fprintf(stderr, "mazewall-stack-agent: definition publication failed opening temporary file\n");
            return false;
        }
        std::size_t encoded_capacity = 0;
        if (!estimate_snapshot_wire_bytes(snapshot, encoded_capacity)) {
            std::fprintf(
                stderr,
                "mazewall-stack-agent: definition_publication_failure=encoded_snapshot_exceeds_retained_limit\n");
            return false;
        }
        ScopedNativeReservation encoded_reservation(encoded_capacity);
        if (!encoded_reservation.reserved()) {
            std::fprintf(stderr, "mazewall-stack-agent: definition publication exhausted retained-state budget\n");
            return false;
        }
        const auto serialization_started = benchmark_started();
        std::vector<std::uint8_t> encoded;
        encoded.reserve(encoded_capacity);
        encoded.insert(
            encoded.end(),
            {'M', 'Z', 'S', 'D', static_cast<std::uint8_t>(stack_transport == StackTransport::dictionary ? 2 : 3), 0});
        std::uint64_t committed_watermark = 0;
        if (stack_transport == StackTransport::dictionary) {
            for (const auto& stack : snapshot.stacks) {
                const auto record = begin_record(encoded, 1);
                append_stack_payload(encoded, *stack);
                finish_record(encoded, record);
            }
        }
        for (const auto& execution : snapshot.executions) {
            const auto record = begin_record(encoded, 4);
            append_le<std::uint64_t>(encoded, execution.id);
            encoded.push_back(static_cast<std::uint8_t>(execution.kind));
            finish_record(encoded, record);
        }
        for (const auto& invocation : snapshot.invocations) {
            const auto record = begin_record(encoded,
                stack_transport == StackTransport::inline_per_invocation && invocation.failure == 0 ? 5 : 2);
            append_le<std::uint64_t>(encoded, invocation.id);
            append_le<std::uint64_t>(encoded, invocation.parent_id);
            append_le<std::uint32_t>(encoded, invocation.stack_id);
            encoded.push_back(invocation.failure);
            append_le<std::uint64_t>(encoded, invocation.execution_id);
            if (stack_transport == StackTransport::inline_per_invocation && invocation.failure == 0) {
                if (invocation.stack_id == 0 || invocation.stack_id > snapshot.stacks.size()) {
                    throw std::runtime_error("inline invocation references an unknown stack");
                }
                append_stack_payload(encoded, *snapshot.stacks[invocation.stack_id - 1]);
            }
            finish_record(encoded, record);
        }
        const auto stats_record = begin_record(encoded, 3);
        append_le<std::uint64_t>(encoded, stack_capture_failures.load(std::memory_order_relaxed));
        append_le<std::uint64_t>(encoded, scope_failures.load(std::memory_order_relaxed));
        append_le<std::uint64_t>(encoded, virtual_pinned_scopes.load(std::memory_order_relaxed));
        append_le<std::uint64_t>(encoded, virtual_pinned_nanos.load(std::memory_order_relaxed));
        finish_record(encoded, stats_record);
        if (!snapshot.invocations.empty()) committed_watermark = snapshot.invocations.back().id;
        if (live_definitions.load(std::memory_order_acquire)) {
            std::vector<std::uint8_t> envelope;
            envelope.insert(envelope.end(), {'M', 'Z', 'S', 'E'});
            append_le<std::uint16_t>(envelope, 2);
            append_le<std::uint16_t>(envelope, producer_closed ? 1 : 0);
            append_le<std::uint64_t>(envelope, session_tag);
            append_le<std::uint64_t>(envelope, next_publication_id.fetch_add(1, std::memory_order_relaxed));
            append_le<std::uint64_t>(envelope, committed_watermark);
            append_le<std::uint64_t>(envelope, static_cast<std::uint64_t>(encoded.size()));
            envelope.insert(envelope.end(), encoded.begin(), encoded.end());
            encoded = std::move(envelope);
        }
        if (benchmark_phases) {
            benchmark_serialization_nanos.fetch_add(elapsed_nanos(serialization_started), std::memory_order_relaxed);
            benchmark_publication_bytes.fetch_add(encoded.size(), std::memory_order_relaxed);
        }
        stream.write(reinterpret_cast<const char*>(encoded.data()), static_cast<std::streamsize>(encoded.size()));
        stream.flush();
        if (!stream.good()) {
            std::fprintf(stderr, "mazewall-stack-agent: definition publication failed writing temporary file\n");
            return false;
        }
        stream.close();
        if (std::rename(temporary_path.c_str(), definitions_path.c_str()) != 0) {
            std::fprintf(stderr, "mazewall-stack-agent: definition publication failed replacing snapshot\n");
            return false;
        }
        last_published_generation = snapshot.generation;
        if (benchmark_phases) {
            benchmark_publication_count.fetch_add(1, std::memory_order_relaxed);
            benchmark_publication_latency_nanos.fetch_add(elapsed_nanos(publication_started), std::memory_order_relaxed);
        }
        return true;
    } catch (const std::exception& failure) {
        std::fprintf(stderr, "mazewall-stack-agent: definition publication threw: %s\n", failure.what());
        if (benchmark_phases) benchmark_failed_publications.fetch_add(1, std::memory_order_relaxed);
        return false;
    } catch (...) {
        std::fprintf(stderr, "mazewall-stack-agent: definition publication threw a non-standard exception\n");
        if (benchmark_phases) benchmark_failed_publications.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
}

void request_live_publication() noexcept {
    live_publication_requests.fetch_add(1, std::memory_order_release);
    live_publisher_wakeup.notify_one();
}

void run_live_publisher() noexcept {
    std::uint64_t observed_requests = 0;
    std::unique_lock lock(live_publisher_wait_mutex);
    while (!live_publisher_stop.load(std::memory_order_acquire)) {
        live_publisher_wakeup.wait(lock, [&] {
            return live_publisher_stop.load(std::memory_order_acquire) ||
                live_publication_requests.load(std::memory_order_acquire) != observed_requests;
        });
        if (live_publisher_stop.load(std::memory_order_acquire)) return;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(100);
        while (!live_publisher_stop.load(std::memory_order_acquire) &&
            std::chrono::steady_clock::now() < deadline) {
            live_publisher_wakeup.wait_until(lock, deadline);
        }
        if (live_publisher_stop.load(std::memory_order_acquire)) return;
        const auto requested = live_publication_requests.load(std::memory_order_acquire);
        if (requested > observed_requests + 1 && benchmark_phases) {
            benchmark_coalesced_publications.fetch_add(requested - observed_requests - 1, std::memory_order_relaxed);
        }
        observed_requests = requested;
        lock.unlock();
        (void)write_definitions();
        lock.lock();
    }
}

void start_live_publisher() {
    if (!live_definitions.load(std::memory_order_acquire) || live_publisher.joinable()) return;
    live_publisher_stop.store(false, std::memory_order_release);
    live_publisher = std::thread(run_live_publisher);
}

void stop_live_publisher() noexcept {
    live_publisher_stop.store(true, std::memory_order_release);
    live_publisher_wakeup.notify_one();
    if (live_publisher.joinable()) live_publisher.join();
}

bool parse_options(const char* options) {
    if (options == nullptr || *options == '\0') return true;
    std::string remaining(options);
    std::size_t start = 0;
    while (start <= remaining.size()) {
        const auto end = remaining.find(',', start);
        const auto option = remaining.substr(start, end == std::string::npos ? end : end - start);
        if (option.starts_with("definitions=")) {
            definitions_path = option.substr(std::strlen("definitions="));
            if (definitions_path.empty()) return false;
        } else if (option.starts_with("capture-yield=")) {
            capture_yield_path = option.substr(std::strlen("capture-yield="));
            if (capture_yield_path.empty()) return false;
        } else if (option.starts_with("lifecycle-barrier=")) {
            const auto path = option.substr(std::strlen("lifecycle-barrier="));
            if (path.empty()) return false;
            try {
                lifecycle_barrier = std::make_unique<mazewall::profiler::LifecycleBarrier>(
                    path,
                    std::chrono::seconds(10));
            } catch (...) {
                return false;
            }
        } else if (option.starts_with("lifecycle-gate=")) {
            lifecycle_barrier_gate_path = option.substr(std::strlen("lifecycle-gate="));
            if (lifecycle_barrier_gate_path.empty()) return false;
        } else if (option == "force-capture-failure-once") {
            force_capture_failure_once.store(true, std::memory_order_release);
        } else if (option.starts_with("capture-failure-gate=")) {
            capture_failure_gate_path = option.substr(std::strlen("capture-failure-gate="));
            if (capture_failure_gate_path.empty()) return false;
        } else if (option == "force-exec-once") {
            force_exec_once.store(true, std::memory_order_release);
        } else if (option == "callback-suppression-probe") {
            callback_suppression_probe_enabled.store(true, std::memory_order_release);
        } else if (option.starts_with("callback-suppression-gate=")) {
            callback_suppression_probe_gate_path = option.substr(std::strlen("callback-suppression-gate="));
            if (callback_suppression_probe_gate_path.empty()) return false;
        } else if (option.starts_with("session=")) {
            try {
                const auto value = std::stoul(option.substr(std::strlen("session=")), nullptr, 16);
                if (value == 0 || value > INT32_MAX) return false;
                session_tag = static_cast<std::uint32_t>(value);
            } catch (...) {
                return false;
            }
        } else if (option == "mode=full-stream") {
            emission_mode = EmissionMode::full_stream;
        } else if (option == "mode=unique-stack-syscall") {
            emission_mode = EmissionMode::unique_stack_syscall;
        } else if (option == "stack-transport=dictionary") {
            stack_transport = StackTransport::dictionary;
        } else if (option == "stack-transport=inline-per-invocation") {
            stack_transport = StackTransport::inline_per_invocation;
        } else if (option.starts_with("retained-budget-bytes=")) {
            try {
                const auto value = std::stoull(option.substr(std::strlen("retained-budget-bytes=")));
                if (value == 0 || value > NATIVE_RETAINED_LIMIT_BYTES) return false;
                native_retained_limit.store(static_cast<std::size_t>(value), std::memory_order_release);
            } catch (...) {
                return false;
            }
        } else if (option == "benchmark-phases") {
            benchmark_phases = true;
        } else if (option == "capture-profile=jdk25-unix-openat0") {
            capture_profile = *mazewall::profiler::CaptureProfile::parse("jdk25-unix-openat0");
        } else if (option.starts_with("capture-bindings=")) {
            const auto parsed = mazewall::profiler::CaptureProfile::parse(option.substr(std::strlen("capture-bindings=")));
            if (!parsed.has_value() || parsed->is_full()) return false;
            capture_profile = *parsed;
        } else if (option == "canonical-cache=enabled") {
            canonical_cache_enabled = true;
        } else if (option == "canonical-cache=disabled") {
            canonical_cache_enabled = false;
        } else if (option == "live-definitions") {
            live_definitions.store(true, std::memory_order_release);
        } else {
            return false;
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    if (!capture_yield_path.empty()) {
        try {
            capture_yield_recorder = std::make_unique<mazewall::profiler::CaptureYieldRecorder>(65'536);
        } catch (...) {
            return false;
        }
    }
    if (!capture_profile.is_full() && !benchmark_phases) return false;
    capture_profile_admitted.store(capture_profile.is_full(), std::memory_order_release);
    return true;
}

} // namespace

extern "C" void mazewall_symbolization_replacement_begin() noexcept {
    cooperative_replacement = true;
    (void)symbolization_generation.begin_cooperative_replacement();
}

extern "C" void mazewall_symbolization_replacement_end() noexcept {
    if (!cooperative_replacement) return;
    (void)symbolization_generation.end_cooperative_replacement();
    cooperative_replacement = false;
}

extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void*) {
    if (vm == nullptr || !parse_options(options)) {
        std::fprintf(stderr, "mazewall-stack-agent: load rejected invalid VM or options\\n");
        return JNI_ERR;
    }

    jvmtiEnv* jvmti = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK || jvmti == nullptr) {
        std::fprintf(stderr, "mazewall-stack-agent: load rejected missing JVMTI 1.2\\n");
        return JNI_ERR;
    }

    global_jvmti = jvmti;
    if (!configure_native_bind_events(jvmti)) {
        std::fprintf(stderr, "mazewall-stack-agent: load rejected native bind configuration\\n");
        return JNI_ERR;
    }
    start_live_publisher();
    return JNI_OK;
}

extern "C" JNIEXPORT void JNICALL Agent_OnUnload(JavaVM* vm) {
    stop_live_publisher();
    const bool definitions_written = write_definitions(true);
    const bool capture_yield_written = write_capture_yield();
    const char* const capture_yield_status = capture_yield_path.empty()
        ? "disabled"
        : (capture_yield_written ? "written" : "failed");
    std::fprintf(
        stderr,
        "mazewall-stack-agent: native-binds=%llu proxied-binds=%llu stack-captures=%llu stack-failures=%llu scope-failures=%llu definitions=%s capture-yield=%s capture-yield-dropped=%llu\n",
        static_cast<unsigned long long>(native_bind_count.load(std::memory_order_relaxed)),
        static_cast<unsigned long long>(proxied_bind_count.load(std::memory_order_relaxed)),
        static_cast<unsigned long long>(stack_capture_count.load(std::memory_order_relaxed)),
        static_cast<unsigned long long>(stack_capture_failures.load(std::memory_order_relaxed)),
        static_cast<unsigned long long>(scope_failures.load(std::memory_order_relaxed)),
        definitions_written ? "written" : "failed",
        capture_yield_status,
        static_cast<unsigned long long>(capture_yield_contention_drops.load(std::memory_order_relaxed)));
    for (std::size_t index = 0;
         index < mazewall::profiler::BindingManifest::supported_bindings.size();
         ++index) {
        const auto binding = mazewall::profiler::BindingManifest::supported_bindings[index];
        std::fprintf(
            stderr,
            "mazewall-stack-agent: capture-profile-binding=%.*s admitted=%llu excluded=%llu\n",
            static_cast<int>(mazewall::profiler::BindingManifest::name(binding).size()),
            mazewall::profiler::BindingManifest::name(binding).data(),
            static_cast<unsigned long long>(capture_profile_admitted_counts[index].load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(capture_profile_excluded_counts[index].load(std::memory_order_relaxed)));
    }
    if (benchmark_phases) {
        std::fprintf(
            stderr,
            "tier-e-benchmark: stack_transport=%s get_stack_trace_ns=%llu raw_key_ns=%llu raw_hash_ns=%llu raw_equality_ns=%llu generation_validation_ns=%llu guard_validation_ns=%llu canonical_cache_lock_wait_ns=%llu logical_metadata_ns=%llu marker_set_ns=%llu marker_clear_ns=%llu serialization_ns=%llu publication_bytes=%llu publication_latency_ns=%llu lock_wait_ns=%llu peak_retained_bytes=%llu native_retained_bytes=%llu native_retention_exhaustions=%llu publication_count=%llu coalesced_publications=%llu failed_publications=%llu cache_hits=%llu cache_misses=%llu metadata_cache_hits=%llu metadata_cache_misses=%llu marker_admission_clear_ns=%llu marker_exact_set_ns=%llu marker_exit_clear_ns=%llu marker_admission_clear_count=%llu marker_exact_set_count=%llu marker_exit_clear_count=%llu\n",
            stack_transport == StackTransport::dictionary ? "dictionary" : "inline-per-invocation",
            static_cast<unsigned long long>(benchmark_get_stack_trace_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_raw_key_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_raw_hash_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_raw_equality_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_generation_validation_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_guard_validation_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_canonical_cache_lock_wait_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_logical_metadata_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_marker_set_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_marker_clear_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_serialization_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_publication_bytes.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_publication_latency_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_definition_lock_wait_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_peak_retained_bytes.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(native_retained_bytes.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(native_retention_exhaustions.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_publication_count.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_coalesced_publications.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_failed_publications.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_cache_hits.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_cache_misses.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_metadata_cache_hits.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_metadata_cache_misses.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_marker_admission_clear_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_marker_exact_set_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_marker_exit_clear_nanos.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_marker_admission_clear_count.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_marker_exact_set_count.load(std::memory_order_relaxed)),
            static_cast<unsigned long long>(benchmark_marker_exit_clear_count.load(std::memory_order_relaxed)));
    }
    JNIEnv* env = nullptr;
    if (vm != nullptr && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_OK) {
        if (const auto callback_class = callback_suppression_probe_class.exchange(nullptr, std::memory_order_acq_rel);
            callback_class != nullptr) {
            env->DeleteGlobalRef(callback_class);
        }
        callback_suppression_probe_ready.store(false, std::memory_order_release);
        callback_suppression_probe_method.store(nullptr, std::memory_order_release);
        clear_symbolization_caches(env);
    }
}
