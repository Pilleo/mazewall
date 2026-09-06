#include <jni.h>
#include <jvmti.h>

#include <atomic>
#include <array>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <mutex>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <unordered_map>
#include <utility>
#include <vector>

#include "binding_manifest.hpp"
#include "invocation_registry.hpp"

extern "C" __attribute__((noinline, visibility("default"))) void mazewall_stack_marker(
    std::uint64_t invocation_id,
    std::uint32_t flags) noexcept {
    asm volatile("" : : "r"(invocation_id), "r"(flags) : "memory");
}

namespace {

std::atomic<std::uint64_t> native_bind_count{0};
std::atomic<std::uint64_t> proxied_bind_count{0};
std::atomic<std::uint64_t> stack_capture_count{0};
std::atomic<std::uint64_t> stack_capture_failures{0};
std::atomic<std::uint64_t> scope_failures{0};
std::atomic<std::uint32_t> next_invocation_id{1};
std::atomic<bool> invocation_ids_exhausted{false};
std::uint32_t session_tag = 1;
std::atomic<std::uint64_t> next_loader_id{1};
std::atomic<jmethodID> thread_is_virtual{nullptr};
jvmtiEnv* global_jvmti = nullptr;
std::string definitions_path;


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
};

struct InvocationDefinition final {
    std::uint64_t id = 0;
    std::uint64_t parent_id = 0;
    std::uint32_t stack_id = 0;
    std::uint8_t failure = 0;
};

enum class EmissionMode : std::uint8_t {
    full_stream,
    unique_stack_syscall,
};

struct RawFrame final {
    std::uintptr_t method = 0;
    std::int64_t location = 0;

    bool operator==(const RawFrame&) const = default;
};

struct RawStack final {
    std::vector<RawFrame> frames;
    std::uint32_t flags = 0;

    bool operator==(const RawStack&) const = default;
};

struct RawStackHash final {
    std::size_t operator()(const RawStack& stack) const noexcept {
        std::size_t hash = stack.flags;
        for (const auto& frame : stack.frames) {
            hash ^= std::hash<std::uintptr_t>{}(frame.method) + 0x9e3779b9 + (hash << 6) + (hash >> 2);
            hash ^= std::hash<std::int64_t>{}(frame.location) + 0x9e3779b9 + (hash << 6) + (hash >> 2);
        }
        return hash;
    }
};

struct CanonicalContext final {
    std::uint64_t id = 0;
    std::vector<jweak> class_guards;
};

std::mutex definitions_mutex;
std::vector<LogicalStack> logical_stacks;
std::vector<InvocationDefinition> invocation_definitions;
std::unordered_map<RawStack, CanonicalContext, RawStackHash> canonical_contexts;
EmissionMode emission_mode = EmissionMode::full_stream;

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
    if (ok) {
        result = LogicalFrame{
            loader_identity(env, owner), owner_descriptor, name, descriptor,
            static_cast<std::int64_t>(raw.location), (modifiers & 0x100) != 0};
        if (class_guard != nullptr) {
            *class_guard = env->NewWeakGlobalRef(owner);
            ok = *class_guard != nullptr;
        }
    }
    deallocate(owner_descriptor);
    deallocate(descriptor);
    deallocate(name);
    if (owner != nullptr) env->DeleteLocalRef(owner);
    return ok;
}

std::uint32_t intern_stack(std::vector<LogicalFrame> frames, bool truncated) {
    std::lock_guard lock(definitions_mutex);
    for (const auto& stack : logical_stacks) {
        if (stack.truncated == truncated && stack.frames == frames) return stack.id;
    }
    const auto id = static_cast<std::uint32_t>(logical_stacks.size() + 1);
    if (id == 0) return 0;
    logical_stacks.push_back(LogicalStack{id, std::move(frames), truncated});
    return id;
}

void record_invocation(InvocationDefinition definition) {
    std::lock_guard lock(definitions_mutex);
    invocation_definitions.push_back(definition);
}

void release_guards(JNIEnv* env, const std::vector<jweak>& guards) {
    for (const auto guard : guards) if (guard != nullptr) env->DeleteWeakGlobalRef(guard);
}

std::uint64_t find_canonical_context(JNIEnv* env, const RawStack& stack) {
    std::lock_guard lock(definitions_mutex);
    const auto found = canonical_contexts.find(stack);
    if (found == canonical_contexts.end()) return 0;
    for (const auto guard : found->second.class_guards) {
        if (env->IsSameObject(guard, nullptr) == JNI_TRUE) {
            release_guards(env, found->second.class_guards);
            canonical_contexts.erase(found);
            return 0;
        }
    }
    return found->second.id;
}

std::uint64_t remember_canonical_context(JNIEnv* env, const RawStack& stack, std::uint64_t candidate, std::vector<jweak> guards) {
    std::lock_guard lock(definitions_mutex);
    if (const auto found = canonical_contexts.find(stack); found != canonical_contexts.end()) {
        release_guards(env, guards);
        return found->second.id;
    }
    canonical_contexts.emplace(stack, CanonicalContext{candidate, std::move(guards)});
    return candidate;
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
    explicit InvocationScope(JNIEnv* env) noexcept {
        if (invocation_ids_exhausted.load(std::memory_order_relaxed)) {
            scope_failures.fetch_add(1, std::memory_order_relaxed);
            mazewall_stack_marker(0, 0);
            return;
        }
        const auto counter = next_invocation_id.fetch_add(1, std::memory_order_relaxed);
        if (counter == 0) {
            invocation_ids_exhausted.store(true, std::memory_order_relaxed);
            scope_failures.fetch_add(1, std::memory_order_relaxed);
            mazewall_stack_marker(0, 0);
            return;
        }
        const auto id = (static_cast<std::uint64_t>(session_tag) << 32) | counter;
        scope_ = invocation_registry.enter(id);
        // Clear the parent's attribution before JVMTI work. Any agent-internal
        // syscall is therefore UNKNOWN rather than incorrectly assigned.
        mazewall_stack_marker(0, 0);
        if (!scope_.valid()) {
            scope_failures.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        const auto capture = capture_stack(env, scope_.id);
        if (capture.context_id != scope_.id && !invocation_registry.replace_current(scope_, capture.context_id)) {
            scope_failures.fetch_add(1, std::memory_order_relaxed);
            mazewall_stack_marker(0, 0);
            return;
        }
        if (capture.new_context) {
            record_invocation(InvocationDefinition{scope_.id, scope_.parent_id, capture.stack_id, capture.failure});
        }
        mazewall_stack_marker(scope_.valid() ? scope_.id : 0, flags_);
    }

    ~InvocationScope() {
        mazewall_stack_marker(0, 0);
        if (!scope_.valid()) {
            mazewall_stack_marker(invocation_registry.current_id(), flags_);
            return;
        }
        const bool restored = invocation_registry.leave(scope_);
        mazewall_stack_marker(restored ? invocation_registry.current_id() : 0, flags_);
    }

private:
    struct Capture final {
        std::uint32_t stack_id = 0;
        std::uint8_t failure = 1;
        std::uint64_t context_id = 0;
        bool new_context = false;
    };

    Capture capture_stack(JNIEnv* env, std::uint64_t proposed_context_id) noexcept {
        if (global_jvmti == nullptr) {
            stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
            return {};
        }

        jthread current_thread = nullptr;
        if (global_jvmti->GetCurrentThread(&current_thread) != JVMTI_ERROR_NONE || current_thread == nullptr) {
            stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
            return {};
        }
        auto is_virtual = thread_is_virtual.load(std::memory_order_acquire);
        if (is_virtual == nullptr) {
            jclass thread_class = env->GetObjectClass(current_thread);
            if (thread_class != nullptr) {
                is_virtual = env->GetMethodID(thread_class, "isVirtual", "()Z");
                env->DeleteLocalRef(thread_class);
                if (is_virtual != nullptr) thread_is_virtual.store(is_virtual, std::memory_order_release);
            }
        }
        if (is_virtual != nullptr && env->CallBooleanMethod(current_thread, is_virtual) == JNI_TRUE) flags_ |= 1;
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            flags_ = 0;
        }

        std::array<jvmtiFrameInfo, 129> frames{};
        jint frame_count = 0;
        const auto result = global_jvmti->GetStackTrace(
            current_thread,
            0,
            static_cast<jint>(frames.size()),
            frames.data(),
            &frame_count);
        if (result != JVMTI_ERROR_NONE || frame_count <= 0) {
            stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
            env->DeleteLocalRef(current_thread);
            return {};
        }
        const bool truncated = frame_count == static_cast<jint>(frames.size());
        // A truncated dictionary entry remains useful for diagnostics, but the
        // session must never claim complete capture merely because no syscall
        // happened to reference it before shutdown.
        if (truncated) stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
        const auto retained = truncated ? frames.size() - 1 : static_cast<std::size_t>(frame_count);
        RawStack raw;
        raw.flags = flags_;
        raw.frames.reserve(retained);
        for (std::size_t index = 0; index < retained; ++index) {
            raw.frames.push_back(RawFrame{reinterpret_cast<std::uintptr_t>(frames[index].method), static_cast<std::int64_t>(frames[index].location)});
        }
        if (!truncated && emission_mode == EmissionMode::unique_stack_syscall) {
            if (const auto existing = find_canonical_context(env, raw); existing != 0) {
                env->DeleteLocalRef(current_thread);
                stack_capture_count.fetch_add(1, std::memory_order_relaxed);
                return Capture{0, 0, existing, false};
            }
        }
        std::vector<LogicalFrame> logical;
        std::vector<jweak> class_guards;
        logical.reserve(retained);
        if (emission_mode == EmissionMode::unique_stack_syscall) class_guards.reserve(retained);
        for (std::size_t index = 0; index < retained; ++index) {
            LogicalFrame frame;
            jweak guard = nullptr;
            if (!resolve_frame(env, frames[index], frame,
                    emission_mode == EmissionMode::unique_stack_syscall ? &guard : nullptr)) {
                release_guards(env, class_guards);
                stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
                env->DeleteLocalRef(current_thread);
                return {};
            }
            if (guard != nullptr) class_guards.push_back(guard);
            logical.push_back(std::move(frame));
        }
        env->DeleteLocalRef(current_thread);
        const auto stack_id = intern_stack(std::move(logical), truncated);
        if (stack_id == 0) {
            release_guards(env, class_guards);
            stack_capture_failures.fetch_add(1, std::memory_order_relaxed);
            return {};
        }
        stack_capture_count.fetch_add(1, std::memory_order_relaxed);
        if (!truncated && emission_mode == EmissionMode::unique_stack_syscall) {
            const auto context_id = remember_canonical_context(env, raw, proposed_context_id, std::move(class_guards));
            if (context_id != proposed_context_id) return Capture{0, 0, context_id, false};
        } else {
            release_guards(env, class_guards);
        }
        return Capture{stack_id, 0, proposed_context_id, true};
    }

    mazewall::profiler::InvocationRegistry::Scope scope_{};
    std::uint32_t flags_ = 0;
};

jint JNICALL proxy_unix_open0(JNIEnv* env, jclass owner, jlong path, jint flags, jint mode) {
    InvocationScope invocation(env);
    const auto original = original_unix_open0.load(std::memory_order_acquire);
    return original == nullptr ? -1 : original(env, owner, path, flags, mode);
}

jint JNICALL proxy_unix_openat0(JNIEnv* env, jclass owner, jint dirfd, jlong path, jint flags, jint mode) {
    InvocationScope invocation(env);
    const auto original = original_unix_openat0.load(std::memory_order_acquire);
    return original == nullptr ? -1 : original(env, owner, dirfd, path, flags, mode);
}

void JNICALL proxy_unix_close0(JNIEnv* env, jclass owner, jint fd) {
    InvocationScope invocation(env);
    const auto original = original_unix_close0.load(std::memory_order_acquire);
    if (original != nullptr) original(env, owner, fd);
}

void JNICALL proxy_unix_unlink0(JNIEnv* env, jclass owner, jlong path) {
    InvocationScope invocation(env);
    const auto original = original_unix_unlink0.load(std::memory_order_acquire);
    if (original != nullptr) original(env, owner, path);
}

jint JNICALL proxy_file_read0(JNIEnv* env, jclass owner, jobject fd, jlong address, jint length) {
    InvocationScope invocation(env); return original_file_read0.load()(env, owner, fd, address, length);
}
jint JNICALL proxy_file_write0(JNIEnv* env, jclass owner, jobject fd, jlong address, jint length) {
    InvocationScope invocation(env); return original_file_write0.load()(env, owner, fd, address, length);
}
void JNICALL proxy_file_close_int_fd(JNIEnv* env, jclass owner, jint fd) {
    InvocationScope invocation(env); original_file_close_int_fd.load()(env, owner, fd);
}
jint JNICALL proxy_socket_read0(JNIEnv* env, jclass owner, jobject fd, jlong address, jint length) {
    InvocationScope invocation(env); return original_socket_read0.load()(env, owner, fd, address, length);
}
jint JNICALL proxy_socket_write0(JNIEnv* env, jclass owner, jobject fd, jlong address, jint length) {
    InvocationScope invocation(env); return original_socket_write0.load()(env, owner, fd, address, length);
}
jint JNICALL proxy_net_socket0(JNIEnv* env, jclass owner, jboolean ipv6, jboolean stream, jboolean reuse, jboolean loopback) {
    InvocationScope invocation(env); return original_net_socket0.load()(env, owner, ipv6, stream, reuse, loopback);
}
jint JNICALL proxy_net_connect0(JNIEnv* env, jclass owner, jboolean ipv6, jobject fd, jobject address, jint port) {
    InvocationScope invocation(env); return original_net_connect0.load()(env, owner, ipv6, fd, address, port);
}
jint JNICALL proxy_net_accept(JNIEnv* env, jclass owner, jobject fd, jobject accepted, jobjectArray addresses) {
    InvocationScope invocation(env); return original_net_accept.load()(env, owner, fd, accepted, addresses);
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

bool configure_native_bind_events(jvmtiEnv* jvmti) noexcept {
    jvmtiCapabilities capabilities{};
    capabilities.can_generate_native_method_bind_events = 1;
    capabilities.can_tag_objects = 1;
    if (jvmti->AddCapabilities(&capabilities) != JVMTI_ERROR_NONE) {
        return false;
    }

    jvmtiEventCallbacks callbacks{};
    callbacks.NativeMethodBind = on_native_method_bind;
    if (jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE) {
        return false;
    }

    return jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_NATIVE_METHOD_BIND, nullptr) == JVMTI_ERROR_NONE;
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

void append_record(std::ofstream& stream, std::uint8_t type, const std::vector<std::uint8_t>& payload) {
    stream.put(static_cast<char>(type));
    std::vector<std::uint8_t> length;
    append_le<std::uint32_t>(length, static_cast<std::uint32_t>(payload.size()));
    stream.write(reinterpret_cast<const char*>(length.data()), static_cast<std::streamsize>(length.size()));
    stream.write(reinterpret_cast<const char*>(payload.data()), static_cast<std::streamsize>(payload.size()));
}

bool write_definitions() noexcept {
    if (definitions_path.empty()) return true;
    try {
        std::ofstream stream(definitions_path, std::ios::binary | std::ios::trunc);
        if (!stream) return false;
        stream.write("MZSD", 4);
        stream.put(1);
        stream.put(0);
        std::lock_guard lock(definitions_mutex);
        for (const auto& stack : logical_stacks) {
            std::vector<std::uint8_t> payload;
            append_le<std::uint32_t>(payload, stack.id);
            payload.push_back(stack.truncated ? 1 : 0);
            append_le<std::uint16_t>(payload, static_cast<std::uint16_t>(stack.frames.size()));
            for (const auto& frame : stack.frames) {
                append_string(payload, frame.loader);
                append_string(payload, frame.owner);
                append_string(payload, frame.name);
                append_string(payload, frame.descriptor);
                append_le<std::int64_t>(payload, frame.location);
                payload.push_back(frame.native_method ? 1 : 0);
            }
            append_record(stream, 1, payload);
        }
        for (const auto& invocation : invocation_definitions) {
            std::vector<std::uint8_t> payload;
            append_le<std::uint64_t>(payload, invocation.id);
            append_le<std::uint64_t>(payload, invocation.parent_id);
            append_le<std::uint32_t>(payload, invocation.stack_id);
            payload.push_back(invocation.failure);
            append_record(stream, 2, payload);
        }
        std::vector<std::uint8_t> stats;
        append_le<std::uint64_t>(stats, stack_capture_failures.load(std::memory_order_relaxed));
        append_le<std::uint64_t>(stats, scope_failures.load(std::memory_order_relaxed));
        append_record(stream, 3, stats);
        stream.flush();
        return stream.good();
    } catch (...) {
        return false;
    }
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
        } else {
            return false;
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void*) {
    if (vm == nullptr || !parse_options(options)) {
        return JNI_ERR;
    }

    jvmtiEnv* jvmti = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK || jvmti == nullptr) {
        return JNI_ERR;
    }

    global_jvmti = jvmti;
    return configure_native_bind_events(jvmti) ? JNI_OK : JNI_ERR;
}

extern "C" JNIEXPORT void JNICALL Agent_OnUnload(JavaVM*) {
    const bool definitions_written = write_definitions();
    std::fprintf(
        stderr,
        "mazewall-stack-agent: native-binds=%llu proxied-binds=%llu stack-captures=%llu stack-failures=%llu scope-failures=%llu definitions=%s\n",
        static_cast<unsigned long long>(native_bind_count.load(std::memory_order_relaxed)),
        static_cast<unsigned long long>(proxied_bind_count.load(std::memory_order_relaxed)),
        static_cast<unsigned long long>(stack_capture_count.load(std::memory_order_relaxed)),
        static_cast<unsigned long long>(stack_capture_failures.load(std::memory_order_relaxed)),
        static_cast<unsigned long long>(scope_failures.load(std::memory_order_relaxed)),
        definitions_written ? "written" : "failed");
}
