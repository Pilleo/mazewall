#include "live_stack_jni.hpp"
#include "live_stack_context.hpp"
#include "live_stack_projection.hpp"
#include "live_stack_checked_projection.hpp"
#include "live_startup_binding.hpp"
#include "live_java_observation.hpp"

#include <array>
#include <atomic>
#include <climits>
#include <condition_variable>
#include <memory>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <fcntl.h>
#include <link.h>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <vector>

namespace mazewall::live::jni {
namespace {
// Process-lifetime ownership: no unattached unload cleanup or premature unpin.
jclass helper_class = nullptr;
std::atomic<jclass> published_helper{nullptr};
jobject helper_loader = nullptr;
jobject helper_module = nullptr;
std::array<jmethodID, 5> helper_methods{};
JavaVM* vm = nullptr;
jvmtiEnv* tooling = nullptr;
jmethodID deployment_inputs = nullptr;
#ifndef MAZEWALL_MANAGED_T4_FIXTURE
jmethodID managed_identity = nullptr;
jmethodID managed_deployment_inputs = nullptr;
#endif
jmethodID identity_equals = nullptr;
std::mutex preparation_mutex;
std::mutex binding_mutex;
enum class PreparationPhase { Unchecked, Checking, Checked, InitializationFailed };
PreparationPhase preparation_phase = PreparationPhase::Unchecked; // binding_mutex owns phase and publication.
struct Binding { jclass owner = nullptr; jmethodID method = nullptr; bool valid = false; };
std::array<Binding, 2> bindings{};

struct CheckedDeployment {
    jobject identity = nullptr;
    uint64_t cookie = 0, ticks = 0;
    uint32_t pid = 0, mask = 0;
    Deployment deployment;
};
std::unique_ptr<CheckedDeployment> checked;
jclass executable_class = nullptr;
bool helper_intact = true; // binding_mutex; an observed helper mutation is terminal for this startup.
#ifdef MAZEWALL_LIVE_JNI_TEST
std::atomic<bool> test_initialization_failure{false};
std::atomic<uint64_t> test_initialization_calls{0}, test_binding_entered{0};
std::mutex test_mutex;
std::condition_variable test_condition;
int test_pause = 0, test_hit = 0;
bool test_released = false;
std::atomic<uint64_t> test_allocations{0}, test_fail_allocation{0}, test_owner_refs{0};
std::atomic<uint64_t> test_deployment_refs{0};
std::atomic<uint64_t> test_mapping_calls{0};
std::array<uint64_t, MW_LIVE_MAX_CLASSES> test_loaders{}, test_modules{};
std::array<Binding, 2> test_rebind_bindings{};
std::array<void*, 2> test_rebind_addresses{};
jthrowable test_throwable = nullptr;
int test_cleanup_limit = -1;
bool test_dependency_mismatch = false;
bool test_null_global = false;
bool test_enable_failure = false;
thread_local mw_live_request_v1 test_worker_request{};
// Single test-worker publisher; coordinator consumes only after release publication.
mw_live_request_v1 test_fifo_request{};
std::atomic<bool> test_fifo_published{false};
mw_live_request_v1 test_retired_request{}; // Written by the worker; coordinator reads only after join.
thread_local uint32_t test_observed_status = MW_LIVE_ABSENT;
thread_local uint64_t test_probe_entries = 0, test_probe_bodies = 0, test_original_calls = 0;
thread_local bool test_probe_resource_fault = false;
bool test_preparation_true = false;
uint64_t test_preparation_calls = 0;
bool test_preparation_inactive = false;
jlong JNICALL test_control(JNIEnv*, jclass, jint, jlong, jobject) noexcept;
void test_boundary(int point) {
    std::unique_lock lock(test_mutex);
    if (test_pause != point) return;
    test_hit = point; test_condition.notify_all();
    test_condition.wait(lock, [] { return test_released; });
}
#else
void test_boundary(int) noexcept {}
#endif
bool allocation_point(JNIEnv* env) noexcept {
#ifdef MAZEWALL_LIVE_JNI_TEST
    if (++test_allocations == test_fail_allocation) {
        if (!env->ExceptionCheck()) env->Throw(test_throwable);
        return false;
    }
#else
    (void)env;
#endif
    return true;
}
bool initialize_checked(const Startup& startup) noexcept {
#ifdef MAZEWALL_LIVE_JNI_TEST
    ++test_initialization_calls;
    auto facts = startup;
    if (test_initialization_failure) facts.hooks_ready = false;
    return initialize(facts);
#else
    return initialize(startup);
#endif
}

void fail(JNIEnv* env, const char* message) noexcept {
    if (env->ExceptionCheck()) return;
    const auto type = env->FindClass("java/lang/UnsupportedOperationException");
    if (type) env->ThrowNew(type, message);
}
void out_of_memory(JNIEnv* env) noexcept {
    if (env->ExceptionCheck()) return;
    auto type = env->FindClass("java/lang/OutOfMemoryError"); if (type) env->ThrowNew(type, "live preparation allocation");
}
bool text(JNIEnv* env, jstring value, std::string& out) {
    if (!value || env->ExceptionCheck()) return false;
    const char* bytes = env->GetStringUTFChars(value, nullptr);
    if (!bytes) { out_of_memory(env); return false; }
    try { out = bytes; } catch (...) { env->ReleaseStringUTFChars(value, bytes); throw; }
    env->ReleaseStringUTFChars(value, bytes);
    return !env->ExceptionCheck();
}
bool used_slot(const Deployment& d, size_t owner, uintptr_t slot, size_t target, uintptr_t offset) noexcept {
    const auto& source = d.images[owner]; const auto& destination = d.images[target];
    if (source.base > UINTPTR_MAX - slot || destination.base > UINTPTR_MAX - offset ||
        !range(d, source, source.base + slot, sizeof(uintptr_t), false) ||
        !range(d, destination, destination.base + offset, 1, true)) return false;
    uintptr_t actual = 0;
#ifdef MAZEWALL_LIVE_JNI_TEST
    if (test_dependency_mismatch && owner == 7 && slot == 0x16100) {
        // Fault DSO only: substitute the actual used writable stored target, observe it, then restore it.
        bool writable = false;
        for (const auto& mapping : d.mappings) if (source.base + slot >= mapping.low && source.base + slot + sizeof(uintptr_t) <= mapping.high && mapping.write) writable = true;
        if (!writable) return false;
        auto* address = reinterpret_cast<volatile uintptr_t*>(source.base + slot);
        const auto saved = *address;
        *address = 0;
        actual = *address;
        *address = saved;
        return actual == destination.base + offset;
    }
#endif
    std::memcpy(&actual, reinterpret_cast<void*>(source.base + slot), sizeof(actual));
    return actual == destination.base + offset;
}
bool dispatch_owners(JNIEnv* env, jvmtiEnv* actual_tooling,
                     const JNINativeInterface_* expected_jni, const jvmtiInterface_1_* expected_jvmti) noexcept {
    // Compare actual retained environments; a byte-identical copied table is still foreign.
    return env && actual_tooling && expected_jni && expected_jvmti &&
        env->functions == expected_jni && actual_tooling->functions == expected_jvmti;
}
bool targets(JNIEnv* env, Deployment& d, std::string* problem = nullptr) {
    const auto deny = [&](const std::string& message) { if (problem) *problem = message; return false; };
    d.mappings.clear();
    if (!read_mappings(d.mappings)) return deny("cannot inspect actual process mappings");
    if (!verify_t4_images(d, problem)) return deny("checked t4 process image association changed");
    for (size_t n : {0u, 2u, 5u, 6u, 7u, 8u, 9u}) {
        struct stat current{};
        if (stat(d.paths[n].c_str(), &current) || current.st_ino != d.images[n].file.st_ino || current.st_dev != d.images[n].file.st_dev) return deny("mapped file identity: " + d.paths[n]);
        if (!build_identity(d, d.images[n], d.builds[n], problem)) return false;
    }
    // Original storage is the agent's one existing interception authority.
    for (uint32_t binding = 1; binding <= 2; ++binding) {
        const auto offset = binding == 1 ? 0xe950u : 0xe9e0u;
        if (!range(d, d.images[7], d.images[7].base + offset, 1, true) ||
            reinterpret_cast<uintptr_t>(original(binding)) != d.images[7].base + offset) return deny("selected original entry " + std::to_string(binding));
    }
    if (!used_slot(d, 7, 0x15ee0, 8, 0x127990)) return deny("used open64 slot");
    if (!used_slot(d, 7, 0x16100, 8, 0x127a90)) return deny("stored openat64 slot");
    if (!used_slot(d, 7, 0x15c18, 8, 0x2a980)) return deny("used errno slot");
    if (!used_slot(d, 7, 0x15f40, 6, 0x1a0a0)) return deny("used JNU_NewObjectByName slot");
    // Check the actual caller table as well as the source-bound static slots.
    const auto table = reinterpret_cast<uintptr_t>(env->functions);
    if (!dispatch_owners(env, tooling,
            reinterpret_cast<const JNINativeInterface_*>(d.images[5].base + 0x178caa0),
            reinterpret_cast<const jvmtiInterface_1_*>(d.images[5].base + 0x1795fa0)) ||
        !range(d, d.images[5], table, sizeof(JNINativeInterface_), false)) return deny("actual caller JNI table identity/range");
    constexpr uintptr_t slots[] = {0x30, 0x68, 0xb8, 0xd0, 0xe8, 0x108};
    constexpr uintptr_t entries[] = {0xb03f90, 0xb01220, 0xaf7300, 0xaf6980, 0xb0f040, 0xb05e90};
    for (size_t n = 0; n < 6; ++n) if (!used_slot(d, 5, 0x178caa0 + slots[n], 5, entries[n])) return deny("actual JNI table entry " + std::to_string(n));
    Dl_info self{};
    return dladdr(reinterpret_cast<void*>(vm_init), &self) && reinterpret_cast<uintptr_t>(self.dli_fbase) == d.images[0].base;
}
uint64_t process_ticks() {
    std::ifstream input("/proc/self/stat"); std::string line;
    if (!std::getline(input, line)) return 0;
    auto end = line.rfind(')'); if (end == std::string::npos) return 0;
    std::istringstream fields(line.substr(end + 2)); std::string token;
    for (int field = 3; field <= 22; ++field) { if (!(fields >> token)) return 0; }
    size_t used = 0; const auto ticks = std::stoull(token, &used);
    return used == token.size() ? ticks : 0;
}

enum class GuardState { Inactive, Probing, Acknowledged, Admitted, Poisoned };
struct Guard {
    GuardState state = GuardState::Inactive;
    bool prepared = false;
    uint64_t attempt = 0, acknowledgment = 0;
    jmethodID method = nullptr;
};
thread_local Guard guard;
jboolean JNICALL probe_body(JNIEnv*, jclass) noexcept {
    return guard.prepared && guard.state == GuardState::Acknowledged && guard.attempt &&
        guard.acknowledgment == guard.attempt && guard.method ? JNI_TRUE : JNI_FALSE;
}
#ifdef MAZEWALL_LIVE_JNI_TEST
// Separately compiled JNI resource-fault entry: the production body stays scalar/read-only.
jboolean JNICALL test_probe_resource_entry(JNIEnv* env, jclass owner) noexcept {
    if (!published_helper.load()) {
        ++test_preparation_calls;
        test_preparation_inactive = owner && env->IsSameObject(owner, helper_class) &&
            guard.state == GuardState::Inactive && !guard.prepared && !guard.attempt &&
            !guard.acknowledgment && !guard.method && !probe_eligible();
#if defined(MAZEWALL_LIVE_PREPARATION_FAULT) && MAZEWALL_LIVE_PREPARATION_FAULT == 1
        return JNI_TRUE;
#elif defined(MAZEWALL_LIVE_PREPARATION_FAULT) && MAZEWALL_LIVE_PREPARATION_FAULT == 2
        const auto error = env->FindClass("java/lang/OutOfMemoryError");
        if (error) env->ThrowNew(error, "test-only probe initialization failure");
        return JNI_FALSE;
#endif
        if (test_preparation_true) return JNI_TRUE;
    }
    if (test_probe_resource_fault) { env->Throw(test_throwable); return JNI_FALSE; }
    ++test_probe_bodies;
    return probe_body(env, owner);
}
#endif
void unsupported(JNIEnv* env) noexcept {
    if (env->ExceptionCheck()) return;
    const auto type = env->FindClass("java/lang/UnsupportedOperationException");
    if (type != nullptr) env->ThrowNew(type, "live stack Unsupported: T2/T3 setup not implemented");
}
struct PendingGlobal {
    JNIEnv* env;
    jobject ref;
    PendingGlobal(JNIEnv* e, jobject object) : env(e), ref(object) {
#ifdef MAZEWALL_LIVE_JNI_TEST
        if (ref) ++test_deployment_refs;
#endif
    }
    PendingGlobal(const PendingGlobal&) = delete;
    PendingGlobal& operator=(const PendingGlobal&) = delete;
    ~PendingGlobal() {
        if (ref) {
            env->DeleteGlobalRef(ref);
#ifdef MAZEWALL_LIVE_JNI_TEST
            --test_deployment_refs;
#endif
        }
    }
    jobject release() noexcept { auto result = ref; ref = nullptr; return result; }
};
struct CheckingAttempt {
    ~CheckingAttempt() {
        std::lock_guard lock(binding_mutex);
        switch (preparation_phase) {
            case PreparationPhase::Checking: preparation_phase = PreparationPhase::Unchecked; break;
            case PreparationPhase::Unchecked: case PreparationPhase::Checked: case PreparationPhase::InitializationFailed: break;
        }
    }
};
struct Refusal { std::string message; };
class LongArrayBuffer final {
public:
    LongArrayBuffer(JNIEnv* env, jlongArray array) : env_(env), array_(array), values_(env->GetLongArrayElements(array, nullptr)) {
        if (!values_) out_of_memory(env);
    }
    ~LongArrayBuffer() { if (values_) env_->ReleaseLongArrayElements(array_, values_, 0); }
    LongArrayBuffer(const LongArrayBuffer&) = delete;
    LongArrayBuffer& operator=(const LongArrayBuffer&) = delete;
    jlong* values() noexcept { return values_; }
private:
    JNIEnv* env_;
    jlongArray array_;
    jlong* values_;
};
enum class ProjectionDispatch { Legacy, ExtendedRefused, Invalid
#ifdef MAZEWALL_MANAGED_T4_FIXTURE
    , FixtureChecked
#endif
    , ProtectedChecked
};
ProjectionDispatch dispatch_projection(JNIEnv* env, jobjectArray inputs, ParsedProjection* checked_projection = nullptr,
        std::vector<mazewall::profiler::integrity::FileIdentity>* measured = nullptr) {
    if (env->ExceptionCheck() || !inputs) return ProjectionDispatch::Invalid;
    if (const auto* snapshot = mazewall::profiler::integrity::startup_snapshot()) {
        if (!checked_projection || !measured
                || !mazewall::profiler::integrity::compare_java_observation(env, inputs, snapshot->manifest)
                || !mazewall::profiler::integrity::verify_artifacts(snapshot->manifest, *measured)) return ProjectionDispatch::Invalid;
        constexpr std::array<const char*, 21> roles{
            "agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm",
            "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext",
            "libsyslookup", "libzip", "libsimdsort"};
        ParsedProjection protected_projection;
        protected_projection.version = ProjectionVersion::T4V2;
        protected_projection.profile = ProjectionProfile::Closed;
        protected_projection.mask = 3;
        protected_projection.zip = snapshot->manifest.conditional_images[0] ? ProjectionBranch::Present : ProjectionBranch::Absent;
        protected_projection.simd = snapshot->manifest.conditional_images[1] ? ProjectionBranch::Present : ProjectionBranch::Absent;
        for (std::size_t i = 0; i < roles.size(); ++i) {
            if (i >= 19 && !snapshot->manifest.conditional_images[i - 19]) continue;
            const auto& artifact = snapshot->manifest.artifacts[i];
            protected_projection.roles.emplace_back(roles[i]);
            protected_projection.paths.push_back(artifact.path);
            protected_projection.builds.push_back(artifact.build_id);
        }
        *checked_projection = std::move(protected_projection);
        return ProjectionDispatch::ProtectedChecked;
    }
    const auto count = env->GetArrayLength(inputs);
    if (env->ExceptionCheck()) return ProjectionDispatch::Invalid;
    if (count == 21) {
        constexpr char protected_magic[] = "mazewall-observed-java/1";
        auto first = static_cast<jstring>(env->GetObjectArrayElement(inputs, 0));
        if (env->ExceptionCheck()) return ProjectionDispatch::Invalid;
        bool protected_header = false;
        if (first) {
            const auto length = env->GetStringLength(first);
            if (env->ExceptionCheck()) { env->DeleteLocalRef(first); return ProjectionDispatch::Invalid; }
            if (length == static_cast<jsize>(sizeof(protected_magic) - 1)) {
                std::array<jchar, sizeof(protected_magic) - 1> chars{};
                env->GetStringRegion(first, 0, length, chars.data());
                if (env->ExceptionCheck()) { env->DeleteLocalRef(first); return ProjectionDispatch::Invalid; }
                protected_header = true;
                for (std::size_t i = 0; i < chars.size(); ++i)
                    if (chars[i] != static_cast<jchar>(protected_magic[i])) { protected_header = false; break; }
            }
            env->DeleteLocalRef(first);
        }
        return protected_header ? ProjectionDispatch::Invalid : ProjectionDispatch::Legacy;
    }
    if (count < 59 || count > 68) return ProjectionDispatch::Invalid;
    auto magic = static_cast<jstring>(env->GetObjectArrayElement(inputs, 0));
    if (!magic || env->ExceptionCheck()) { env->DeleteLocalRef(magic); return ProjectionDispatch::Invalid; }
    if (env->GetStringLength(magic) > 4096 || env->ExceptionCheck()) { env->DeleteLocalRef(magic); return ProjectionDispatch::Invalid; }
    if (env->GetStringUTFLength(magic) > 4096 || env->ExceptionCheck()) { env->DeleteLocalRef(magic); return ProjectionDispatch::Invalid; }
    std::string header;
    bool header_ok = text(env, magic, header); env->DeleteLocalRef(magic);
    if (!header_ok || (header != "mazewall-t4/1" && header != "mazewall-t4/2")) return ProjectionDispatch::Invalid;
    std::vector<std::string> values; values.reserve(static_cast<size_t>(count)); values.push_back(std::move(header));
    for (jsize n = 1; n < count; ++n) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(inputs, n));
        if (!value || env->ExceptionCheck()) { env->DeleteLocalRef(value); return ProjectionDispatch::Invalid; }
        if (env->GetStringLength(value) > 4096 || env->ExceptionCheck()) { env->DeleteLocalRef(value); return ProjectionDispatch::Invalid; }
        if (env->GetStringUTFLength(value) > 4096 || env->ExceptionCheck()) { env->DeleteLocalRef(value); return ProjectionDispatch::Invalid; }
        std::string item;
        bool ok = text(env, value, item); env->DeleteLocalRef(value);
        if (!ok) return ProjectionDispatch::Invalid;
        values.push_back(std::move(item));
    }
    ParsedProjection parsed;
    if (!(values[0] == "mazewall-t4/1" ? parse_t4_v1(values, parsed) : parse_t4_v2(values, parsed))) return ProjectionDispatch::Invalid;
#ifdef MAZEWALL_MANAGED_T4_FIXTURE
    if (checked_projection) {
        if (parsed.version != ProjectionVersion::T4V2 || parsed.profile != ProjectionProfile::Closed) return ProjectionDispatch::Invalid;
        *checked_projection = std::move(parsed);
        return ProjectionDispatch::FixtureChecked;
    }
#else
    (void)checked_projection;
#endif
    return ProjectionDispatch::ExtendedRefused;
}
jlongArray JNICALL deployment(JNIEnv* env, jclass, jobject identity) noexcept {
    if (env->ExceptionCheck()) return nullptr;
    try {
        std::lock_guard lock(preparation_mutex);
        if (!published_helper.load() || !tooling || startup_variant() < 0 || !identity) { unsupported(env); return nullptr; }
        bool existing = false;
        uint64_t serial = 0;
        {
            std::lock_guard bindings_lock(binding_mutex);
            switch (preparation_phase) {
                case PreparationPhase::Unchecked: preparation_phase = PreparationPhase::Checking; break;
                case PreparationPhase::Checked: existing = true; break;
                case PreparationPhase::Checking: throw Refusal{"live deployment: checking in progress"};
                case PreparationPhase::InitializationFailed: throw Refusal{"live deployment: initialization consumed and failed"};
            }
            serial = replacement_serial();
        }
        CheckingAttempt attempt;
        if (existing) {
            if (getpid() != static_cast<pid_t>(checked->pid) || !env->CallBooleanMethod(checked->identity, identity_equals, identity) || env->ExceptionCheck()) {
                fail(env, "live deployment: checked association mismatch"); return nullptr;
            }
            std::lock_guard bindings_lock(binding_mutex);
            if (!helper_intact || !bindings[0].valid || !bindings[1].valid || !targets(env, checked->deployment)) throw Refusal{"live deployment: bindings changed"};
        } else {
            auto selected_inputs = deployment_inputs;
#ifndef MAZEWALL_MANAGED_T4_FIXTURE
            const bool managed = env->CallStaticBooleanMethod(helper_class, managed_identity, identity) == JNI_TRUE;
            if (env->ExceptionCheck()) return nullptr;
            if (managed != (mazewall::profiler::integrity::startup_snapshot() != nullptr)) {
                fail(env, "live deployment: identity kind does not match startup binding");
                return nullptr;
            }
            if (managed) selected_inputs = managed_deployment_inputs;
#endif
            const auto inputs = static_cast<jobjectArray>(env->CallStaticObjectMethod(helper_class, selected_inputs, identity, startup_variant()));
            if (env->ExceptionCheck()) return nullptr;
            if (!inputs) throw Refusal{"live deployment: preparation inputs unavailable"};
            ParsedProjection t4;
            std::vector<mazewall::profiler::integrity::FileIdentity> measured;
            const auto projection = dispatch_projection(env, inputs, &t4, &measured);
            Deployment candidate;
            std::string mask;
            if (projection == ProjectionDispatch::Legacy) {
                for (jsize n = 0; n < 20; ++n) {
                    auto value = static_cast<jstring>(env->GetObjectArrayElement(inputs, n));
                    const bool ok = text(env, value, n % 2 ? candidate.builds[n / 2] : candidate.paths[n / 2]);
                    env->DeleteLocalRef(value); if (!ok) { env->DeleteLocalRef(inputs); return nullptr; }
                }
                auto mask_text = static_cast<jstring>(env->GetObjectArrayElement(inputs, 20));
                if (!text(env, mask_text, mask)) { env->DeleteLocalRef(mask_text); env->DeleteLocalRef(inputs); return nullptr; }
                env->DeleteLocalRef(mask_text);
            } else if (projection == ProjectionDispatch::ProtectedChecked
#ifdef MAZEWALL_MANAGED_T4_FIXTURE
                    || projection == ProjectionDispatch::FixtureChecked
#endif
            ) {
                std::string image_problem;
                candidate.strict_close = projection == ProjectionDispatch::ProtectedChecked;
                if (!inspect_t4_images(t4, candidate, &image_problem)) {
                    env->DeleteLocalRef(inputs);
                    throw Refusal{"live deployment: fixture images refused " + image_problem};
                }
                if (projection == ProjectionDispatch::ProtectedChecked) {
                    if (measured.size() != 30 || candidate.t4_images.size() != t4.roles.size())
                        throw Refusal{"live deployment: measured image count"};
                    struct stat executable{};
                    if (stat("/proc/self/exe", &executable) != 0
                            || static_cast<std::uint64_t>(executable.st_dev) != measured[1].device
                            || static_cast<std::uint64_t>(executable.st_ino) != measured[1].inode)
                        throw Refusal{"live deployment: measured executable changed"};
                    std::size_t image = 0;
                    for (std::size_t role = 0; role < 21; ++role) {
                        if (role == 19 && t4.zip == ProjectionBranch::Absent) continue;
                        if (role == 20 && t4.simd == ProjectionBranch::Absent) continue;
                        const auto& mapped = candidate.t4_images[image++].file;
                        if (static_cast<std::uint64_t>(mapped.st_dev) != measured[role].device
                                || static_cast<std::uint64_t>(mapped.st_ino) != measured[role].inode)
                            throw Refusal{"live deployment: measured mapped image changed"};
                    }
                }
                mask = std::to_string(t4.mask);
            } else {
                env->DeleteLocalRef(inputs);
                fail(env, "live deployment: malformed preparation inputs");
                return nullptr;
            }
            env->DeleteLocalRef(inputs);
            if (mask != "1" && mask != "2" && mask != "3") { fail(env, "live deployment: routes"); return nullptr; }
            std::string problem;
#ifdef MAZEWALL_LIVE_JNI_TEST
            ++test_mapping_calls;
#endif
            if (dl_iterate_phdr(image_callback, &candidate)) { fail(env, "live deployment: loader metadata inspection failed"); return nullptr; }
            const auto pid = getpid(); const auto ticks = process_ticks();
            if (pid <= 0 || !ticks) { fail(env, "live deployment: process identity unavailable"); return nullptr; }
            if (!allocation_point(env)) return nullptr;
            const auto result = env->NewLongArray(3); if (!result) { out_of_memory(env); return nullptr; }
            if (env->ExceptionCheck()) return nullptr;
            if (!allocation_point(env)) return nullptr;
            LongArrayBuffer buffer(env, result); if (!buffer.values()) return nullptr;
            if (!allocation_point(env)) return nullptr;
            PendingGlobal pin{env, env->NewGlobalRef(identity)}; if (!pin.ref) { out_of_memory(env); return nullptr; }
            if (env->ExceptionCheck()) return nullptr;
            const auto binding_mask = static_cast<uint32_t>(mask[0] - '0');
            if (!allocation_point(env)) return nullptr;
            auto payload = std::make_unique<CheckedDeployment>();
            payload->ticks = ticks; payload->pid = pid; payload->mask = binding_mask;
            payload->deployment = std::move(candidate);
            test_boundary(1); // Outside binding lock: a concurrent observed replacement must reject checking.
            std::unique_lock bindings_lock(binding_mutex);
            const auto observed_serial = replacement_serial();
            int helper_fact = -1, binding0_fact = -1, binding1_fact = -1, same_owner_fact = -1;
            bool refused = serial != observed_serial;
            if (!refused) { helper_fact = helper_intact ? 1 : 0; refused = helper_fact == 0; }
            if (!refused) { binding0_fact = bindings[0].valid ? 1 : 0; refused = binding0_fact == 0; }
            if (!refused) { binding1_fact = bindings[1].valid ? 1 : 0; refused = binding1_fact == 0; }
            if (!refused) { same_owner_fact = env->IsSameObject(bindings[0].owner, bindings[1].owner) ? 1 : 0; refused = same_owner_fact == 0; }
            if (refused) {
                bindings_lock.unlock();
                throw Refusal{"live deployment: replacement during checking or missing actual binding expected_serial=" +
                    std::to_string(serial) + " observed_serial=" + std::to_string(observed_serial) +
                    " helper=" + std::to_string(helper_fact) + " binding0=" + std::to_string(binding0_fact) +
                    " binding1=" + std::to_string(binding1_fact) + " same_owner=" + std::to_string(same_owner_fact)};
            }
            if (!targets(env, payload->deployment, &problem)) throw Refusal{"live deployment: " + problem};
            test_boundary(2); // Native-only commit; callbacks cannot expose replacement storage across it.
            uint64_t cookie = 0;
            if (!mint_id(cookie).ok()) throw Refusal{"live deployment: process cookie unavailable"};
            // All fallible objects/results and target checks precede this one bootstrap incarnation.
            payload->cookie = cookie;
            buffer.values()[0] = cookie; buffer.values()[1] = ticks; buffer.values()[2] = pid;
            payload->identity = pin.release();
            checked = std::move(payload);
            preparation_phase = PreparationPhase::InitializationFailed; // initialize consumes its one-shot even on failure.
            if (!initialize_checked({cookie, ticks, static_cast<uint32_t>(pid), binding_mask, true, true, true})) {
                throw Refusal{"live deployment: native initialization refused"};
            }
            preparation_phase = PreparationPhase::Checked;
            return result;
        }
        const auto result = env->NewLongArray(3); if (!result) { out_of_memory(env); return nullptr; }
        const jlong tuple[] = {static_cast<jlong>(checked->cookie), static_cast<jlong>(checked->ticks), static_cast<jlong>(checked->pid)};
        env->SetLongArrayRegion(result, 0, 3, tuple); return env->ExceptionCheck() ? nullptr : result;
    } catch (const Refusal& refusal) { fail(env, refusal.message.c_str());
    } catch (const std::bad_alloc&) {
        if (!env->ExceptionCheck()) { auto type = env->FindClass("java/lang/OutOfMemoryError"); if (type) env->ThrowNew(type, "live deployment allocation"); }
    } catch (...) { fail(env, "live deployment: native inspection failed"); }
    return nullptr;
}
struct ScalarAssociation {
    uint64_t installation = 0, generation = 0, serial = 0, cookie = 0, ticks = 0;
    uint32_t pid = 0, mask = 0;
};
std::array<ScalarAssociation, MW_LIVE_MAX_WORKERS> associations{};
std::mutex association_mutex;
struct ObjectToken { jobject object; uint64_t token; };
struct ClassIdentity { jclass owner; uint64_t token, loader, module; };
struct MethodIdentity { uint64_t token; size_t owner; std::string name, descriptor; jint modifiers; };
struct EnrollmentOwner {
    std::vector<jobject> refs;
    std::vector<ObjectToken> loaders, modules;
    std::vector<ClassIdentity> classes;
    std::unordered_map<jmethodID, MethodIdentity> methods;
    ScalarAssociation identity;
    size_t association = MW_LIVE_MAX_WORKERS;
    EnrollmentOwner() = default;
    EnrollmentOwner(const EnrollmentOwner&) = delete;
    EnrollmentOwner& operator=(const EnrollmentOwner&) = delete;
};
void release_refs(JNIEnv* env, EnrollmentOwner& owner, int limit = -1) noexcept {
    for (auto& ref : owner.refs) {
        if (!ref) continue;
        if (limit == 0) return;
        env->DeleteGlobalRef(ref); ref = nullptr;
#ifdef MAZEWALL_LIVE_JNI_TEST
        --test_owner_refs;
#endif
        if (limit > 0) --limit;
    }
}
void release_association(const EnrollmentOwner& owner) noexcept {
    std::lock_guard lock(association_mutex);
    if (owner.association < associations.size() && associations[owner.association].installation == owner.identity.installation)
        associations[owner.association] = {};
}
class PendingEnrollment final {
public:
    explicit PendingEnrollment(JNIEnv* env) : env_(env), owner_(std::make_unique<EnrollmentOwner>()) {}
    ~PendingEnrollment() { if (owner_) { release_refs(env_, *owner_); release_association(*owner_); } }
    PendingEnrollment(const PendingEnrollment&) = delete;
    PendingEnrollment& operator=(const PendingEnrollment&) = delete;
    PendingEnrollment(PendingEnrollment&&) = default;
    PendingEnrollment& operator=(PendingEnrollment&&) = delete;
    EnrollmentOwner& get() noexcept { return *owner_; }
    EnrollmentOwner* transfer() noexcept { return owner_.release(); }
private:
    JNIEnv* env_;
    std::unique_ptr<EnrollmentOwner> owner_;
};
bool cleanup_enrollment(void* pointer) noexcept {
    JNIEnv* env = nullptr;
    if (!published_helper.load() || !vm || vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK || !env) return false;
    auto* owner = static_cast<EnrollmentOwner*>(pointer);
    if (!owner || getpid() != static_cast<pid_t>(owner->identity.pid)) return false;
    int limit = -1;
#ifdef MAZEWALL_LIVE_JNI_TEST
    limit = test_cleanup_limit;
#endif
    release_refs(env, *owner, limit);
    for (auto ref : owner->refs) if (ref) return false;
    release_association(*owner);
    delete owner;
    return true;
}
struct LocalFrame {
    JNIEnv* env;
    bool active;
    explicit LocalFrame(JNIEnv* e) : env(e), active(e->PushLocalFrame(16) == JNI_OK) { if (!active) out_of_memory(e); }
    ~LocalFrame() { if (active) env->PopLocalFrame(nullptr); }
};
uint64_t token() {
    uint64_t id = 0;
    if (!mint_id(id).ok()) throw Refusal{"live enrollment: semantic token exhausted/contended"};
    return id;
}
jobject pin(JNIEnv* env, EnrollmentOwner& owner, jobject object) {
    if (!allocation_point(env)) return nullptr;
    auto ref =
#ifdef MAZEWALL_LIVE_JNI_TEST
        test_null_global ? nullptr :
#endif
        env->NewGlobalRef(object);
    if (!ref) { out_of_memory(env); return nullptr; }
    if (env->ExceptionCheck()) { env->DeleteGlobalRef(ref); return nullptr; }
    // Capacity is allocated before pinning, so ownership bookkeeping cannot fail here.
    owner.refs.push_back(ref);
#ifdef MAZEWALL_LIVE_JNI_TEST
    ++test_owner_refs;
#endif
    return ref;
}
uint64_t intern(JNIEnv* env, EnrollmentOwner& owner, std::vector<ObjectToken>& identities, jobject object, bool bootstrap) {
    for (const auto& entry : identities) if (env->IsSameObject(entry.object, object)) return entry.token;
    const auto id = token();
    const auto ref = bootstrap ? nullptr : pin(env, owner, object);
    if (!bootstrap && !ref) return 0;
    identities.push_back({ref, id});
    return id;
}
ScalarAssociation checked_association(JNIEnv* env) {
    std::lock_guard setup(preparation_mutex);
    std::lock_guard lock(binding_mutex);
    if (!published_helper.load() || preparation_phase != PreparationPhase::Checked || !checked || getpid() != static_cast<pid_t>(checked->pid) ||
        startup_variant() < 0 || !helper_class || !helper_intact || !bindings[0].valid || !bindings[1].valid || !targets(env, checked->deployment))
        throw Refusal{"live enrollment: checked deployment unavailable or changed"};
    return {0, 0, replacement_serial(), checked->cookie, checked->ticks, checked->pid, checked->mask};
}
jlongArray JNICALL enroll(JNIEnv* env, jclass, jlong installation, jobjectArray owners, jobjectArray methods) noexcept {
    if (env->ExceptionCheck()) return nullptr;
    try {
        if (!published_helper.load() || !tooling || !executable_class) { unsupported(env); return nullptr; }
        auto identity = checked_association(env); // Hash/mapping operations precede the administrative gate.
        Enrollment enrollment(static_cast<uint64_t>(installation));
        if (!enrollment.outcome().ok()) throw Refusal{"live enrollment: reservation is not current staging ownership"};
        if (!owners || !methods) throw Refusal{"live enrollment: null arrays"};
        const auto class_count = env->GetArrayLength(owners), method_count = env->GetArrayLength(methods);
        if (class_count < 1 || class_count > static_cast<jsize>(MW_LIVE_MAX_CLASSES) || method_count < 1 || method_count > static_cast<jsize>(MW_LIVE_MAX_METHODS))
            throw Refusal{"live enrollment: class/method bounds"};
        if (!allocation_point(env)) return nullptr;
        PendingEnrollment pending(env); auto& owner = pending.get();
        identity.installation = static_cast<uint64_t>(installation); identity.serial = enrollment.serial(); owner.identity = identity;
        if (!allocation_point(env)) return nullptr;
        owner.refs.reserve(static_cast<size_t>(class_count) * 3);
        if (!allocation_point(env)) return nullptr;
        owner.classes.reserve(class_count);
        if (!allocation_point(env)) return nullptr;
        owner.loaders.reserve(class_count);
        if (!allocation_point(env)) return nullptr;
        owner.modules.reserve(class_count);
        if (!allocation_point(env)) return nullptr;
        owner.methods.reserve(method_count);
        if (!allocation_point(env)) return nullptr;
        std::vector<uint64_t> method_tokens; method_tokens.reserve(method_count);
        for (jsize n = 0; n < class_count; ++n) {
            LocalFrame frame(env); if (!frame.active) return nullptr;
            auto actual = static_cast<jclass>(env->GetObjectArrayElement(owners, n));
            if (!actual || env->ExceptionCheck()) throw Refusal{"live enrollment: null owner"};
            for (const auto& prior : owner.classes) if (env->IsSameObject(prior.owner, actual)) throw Refusal{"live enrollment: duplicate actual owner"};
            jint status = 0;
            if (tooling->GetClassStatus(actual, &status) != JVMTI_ERROR_NONE || !(status & JVMTI_CLASS_STATUS_PREPARED)) throw Refusal{"live enrollment: unprepared owner"};
            auto retained = static_cast<jclass>(pin(env, owner, actual)); if (!retained) return nullptr;
            jobject loader = nullptr;
            if (tooling->GetClassLoader(actual, &loader) != JVMTI_ERROR_NONE) throw Refusal{"live enrollment: loader unavailable"};
            const auto loader_token = intern(env, owner, owner.loaders, loader, loader == nullptr);
            if (!loader_token) return nullptr;
            auto module = env->GetModule(actual);
            if (env->ExceptionCheck()) return nullptr;
            if (!module) throw Refusal{"live enrollment: actual module unavailable"};
            const auto module_token = intern(env, owner, owner.modules, module, false); if (!module_token) return nullptr;
            owner.classes.push_back({retained, token(), loader_token, module_token});
        }
        for (jsize n = 0; n < method_count; ++n) {
            LocalFrame frame(env); if (!frame.active) return nullptr;
            auto reflected = env->GetObjectArrayElement(methods, n);
            if (!reflected || !env->IsInstanceOf(reflected, executable_class) || env->ExceptionCheck()) throw Refusal{"live enrollment: invalid reflected executable"};
            auto method = env->FromReflectedMethod(reflected);
            if (env->ExceptionCheck()) return nullptr;
            if (!method) throw Refusal{"live enrollment: actual reflected method conversion failed"};
            if (owner.methods.contains(method)) throw Refusal{"live enrollment: duplicate actual method"};
            jclass declaring = nullptr; jboolean obsolete = JNI_TRUE; jint modifiers = 0;
            if (tooling->GetMethodDeclaringClass(method, &declaring) != JVMTI_ERROR_NONE ||
                tooling->IsMethodObsolete(method, &obsolete) != JVMTI_ERROR_NONE || obsolete ||
                tooling->GetMethodModifiers(method, &modifiers) != JVMTI_ERROR_NONE) throw Refusal{"live enrollment: obsolete or unavailable method"};
            size_t index = 0;
            for (; index < owner.classes.size(); ++index) if (env->IsSameObject(owner.classes[index].owner, declaring)) break;
            if (index == owner.classes.size()) throw Refusal{"live enrollment: actual declaring owner missing (inherited mismatch)"};
            char* name = nullptr; char* descriptor = nullptr;
            if (tooling->GetMethodName(method, &name, &descriptor, nullptr) != JVMTI_ERROR_NONE) throw Refusal{"live enrollment: method metadata unavailable"};
            struct Metadata {
                char* name; char* descriptor;
                ~Metadata() { if (name) tooling->Deallocate(reinterpret_cast<unsigned char*>(name)); if (descriptor) tooling->Deallocate(reinterpret_cast<unsigned char*>(descriptor)); }
            } metadata{name, descriptor};
            if (!allocation_point(env)) return nullptr;
            const auto method_token = token();
            owner.methods.emplace(method, MethodIdentity{method_token, index, name, descriptor, modifiers});
            method_tokens.push_back(method_token);
        }
        // Selected bindings must be the exact enrolled actual Class and method ID.
        {
            std::lock_guard lock(binding_mutex);
            for (size_t index = 0; index < bindings.size(); ++index) {
                if (!(identity.mask & (1u << index))) continue;
                const auto& binding = bindings[index];
                auto found = owner.methods.find(binding.method);
                if (!binding.valid || found == owner.methods.end() || !env->IsSameObject(owner.classes[found->second.owner].owner, binding.owner))
                    throw Refusal{"live enrollment: selected actual binding not enrolled"};
            }
        }
        {
            std::lock_guard lock(association_mutex);
            size_t index = 0;
            while (index < associations.size() && associations[index].installation) ++index;
            if (index == associations.size()) throw Refusal{"live enrollment: bounded association capacity"};
            owner.association = index; associations[index] = identity;
        }
        if (!allocation_point(env)) return nullptr;
        const auto result = env->NewLongArray(3 + class_count + method_count); if (!result) { out_of_memory(env); return nullptr; }
        if (env->ExceptionCheck()) return nullptr;
        if (!allocation_point(env)) return nullptr; // Last fallible result-write boundary, before core ownership transfer.
        test_boundary(3);
        LongArrayBuffer buffer(env, result); auto* values = buffer.values(); if (!values) return nullptr;
        values[0] = 0; values[1] = class_count; values[2] = method_count;
        for (jsize n = 0; n < class_count; ++n) values[3 + n] = owner.classes[n].token;
        for (jsize n = 0; n < method_count; ++n) values[3 + class_count + n] = method_tokens[n];
        Outcome outcome{Status::Stale, Reason::GenerationInvalid}; uint64_t generation = 0;
        {
            std::lock_guard lock(binding_mutex);
            if (preparation_phase == PreparationPhase::Checked && helper_intact && replacement_serial() == identity.serial && bindings[0].valid && bindings[1].valid)
                outcome = enrollment.commit(&owner, cleanup_enrollment, class_count, method_tokens.data(), method_count, generation);
            if (outcome.ok()) {
                owner.identity.generation = generation;
#ifdef MAZEWALL_LIVE_JNI_TEST
                for (size_t n = 0; n < owner.classes.size(); ++n) { test_loaders[n] = owner.classes[n].loader; test_modules[n] = owner.classes[n].module; }
#endif
                { std::lock_guard association_lock(association_mutex); associations[owner.association] = owner.identity; }
                values[0] = generation;
                (void)pending.transfer();
            }
        }
        // Buffer release/copy-back is nonallocating; core owns the transferred refs even if Java later abandons its result.
        if (!outcome.ok()) throw Refusal{"live enrollment: commit refused (replacement/revocation/contention)"};
        return result;
    } catch (const Refusal& refusal) { fail(env, refusal.message.c_str()); }
      catch (const std::bad_alloc&) { out_of_memory(env); }
      catch (...) { fail(env, "live enrollment: native setup failure"); }
    return nullptr;
}
void JNICALL worker(JNIEnv* env, jclass) noexcept { (void)prepare_worker(env); }
void JNICALL seal(JNIEnv* env, jclass, jlong installation, jlong generation) noexcept {
    if (env->ExceptionCheck()) return;
    try {
        const auto current = checked_association(env);
        ScalarAssociation found;
        {
            std::lock_guard lock(association_mutex);
            for (const auto& entry : associations) if (entry.installation == static_cast<uint64_t>(installation) && entry.generation == static_cast<uint64_t>(generation)) { found = entry; break; }
        }
        if (!found.generation || found.serial != current.serial || found.cookie != current.cookie || found.ticks != current.ticks || found.pid != current.pid || found.mask != current.mask)
            throw Refusal{"live Seal: checked generation association mismatch"};
        test_boundary(4);
        std::lock_guard lock(binding_mutex);
        if (replacement_serial() != found.serial || preparation_phase != PreparationPhase::Checked || !helper_intact || !bindings[0].valid || !bindings[1].valid)
            throw Refusal{"live Seal: replacement or binding mismatch"};
    } catch (const Refusal& refusal) { fail(env, refusal.message.c_str()); }
      catch (...) { fail(env, "live Seal: native inspection failure"); }
}
#ifdef MAZEWALL_LIVE_JNI_TEST
jboolean JNICALL test_replaced_probe(JNIEnv*, jclass) noexcept { return JNI_FALSE; }
jlong JNICALL test_control(JNIEnv* env, jclass, jint operation, jlong value, jobject object) noexcept {
    try {
    switch (operation) {
        case 43: {
            if (test_fifo_published.load(std::memory_order_relaxed) || !test_worker_request.worker_id || !test_worker_request.task_id) return -1;
            test_fifo_request = test_worker_request;
            test_fifo_published.store(true, std::memory_order_release);
            return 0;
        }
        case 44: {
            if (!test_fifo_published.load(std::memory_order_acquire)) return 1;
            const auto bytes = static_cast<jbyteArray>(object);
            if (!bytes || env->GetArrayLength(bytes) != static_cast<jsize>(sizeof(test_fifo_request))) return -1;
            env->SetByteArrayRegion(bytes, 0, sizeof(test_fifo_request), reinterpret_cast<const jbyte*>(&test_fifo_request));
            return env->ExceptionCheck() ? -1 : 0;
        }
        case 45: case 46: {
            const auto path = static_cast<jstring>(object);
            if (!path) return -EINVAL;
            const char* bytes = env->GetStringUTFChars(path, nullptr);
            if (!bytes) return -ENOMEM;
            int result;
            if (operation == 45) {
                result = ::mkfifo(bytes, 0600);
                if (result < 0) result = -errno;
            } else {
                const int fd = ::open(bytes, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
                if (fd < 0) result = -errno;
                else { result = ::close(fd); if (result < 0) result = -errno; }
            }
            env->ReleaseStringUTFChars(path, bytes);
            return result;
        }
        case 47: {
            auto bytes = static_cast<jbyteArray>(object);
            if (!bytes || value <= 0) return -EINVAL;
            const jsize length = env->GetArrayLength(bytes);
            if (length <= 0 || length > 4096) return -EINVAL;
            std::array<jbyte, 4096> buffer{};
            const int fd = ::open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
            if (fd < 0) return -errno;
            const ssize_t count = ::pread(fd, buffer.data(), length, static_cast<off_t>(value));
            const int read_error = count < 0 ? errno : 0;
            const int close_result = ::close(fd);
            const int close_error = close_result < 0 ? errno : 0;
            if (read_error) return -read_error;
            if (close_error) return -close_error;
            env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(count), buffer.data());
            return env->ExceptionCheck() ? -EIO : count;
        }
        case 41: {
            if (value == 1 && test_throwable) env->Throw(test_throwable);
            return static_cast<jlong>(dispatch_projection(env, static_cast<jobjectArray>(object)));
        }
        case 42: return static_cast<jlong>(test_mapping_calls.load());
        case 24: test_enable_failure = value != 0; return 0;
        case 25: return static_cast<jlong>(syscall(SYS_gettid));
        case 26: {
            ScalarAssociation found;
            { std::lock_guard lock(association_mutex);
              for (const auto& a : associations) if (a.installation == static_cast<uint64_t>(value)) found = a; }
            mw_live_request_v1 q{};
            q.abi_version = 1; q.struct_bytes = sizeof(q); q.operation = 3;
            q.process_cookie = found.cookie; q.process_start_ticks = found.ticks; q.tgid = found.pid;
            q.installation_id = found.installation; q.code_generation = found.generation;
            q.tid = syscall(SYS_gettid);
            mw_live_receipt_v1 r{};
            if (mazewall_live_lifecycle_v1(&q, sizeof(q), &r, sizeof(r))) return -1;
            q.worker_id = r.worker_id; q.operation = 0; test_worker_request = q;
            return r.worker_id;
        }
        case 27: case 28: case 34: {
            auto q = operation == 34 ? test_retired_request : test_worker_request;
            q.operation = operation == 27 ? 4 : operation == 28 ? 5 : 7;
            if (operation == 34) q.task_id = 0;
            mw_live_receipt_v1 r{};
            const auto status = mazewall_live_lifecycle_v1(&q, sizeof(q), &r, sizeof(r));
            if (!status && operation == 27) test_worker_request.task_id = r.task_id;
            if (!status && operation == 28) test_retired_request = test_worker_request;
            return status;
        }
        case 29: return test_observed_status;
        case 30: case 40: {
            const auto path = static_cast<jstring>(object);
            const auto bytes = env->GetStringUTFChars(path, nullptr);
            if (!bytes) return -1;
            test_observed_status = MW_LIVE_ABSENT;
            const auto result = operation == 30 ? test_invoke_open(env, bindings[0].owner, bytes)
                : test_invoke_openat(env, bindings[1].owner, bytes);
            env->ReleaseStringUTFChars(path, bytes);
            return result;
        }
        case 31: return test_probe_entries;
        case 32: return test_probe_bodies;
        case 35: test_probe_resource_fault = value != 0; return 0;
        case 36: return test_original_calls;
        case 38: return env->IsSameObject(object, test_throwable);
        case 39: return test_acquisition_calls();
        case 1: test_initialization_failure = value != 0; return 0;
        case 2: return test_initialization_calls;
        case 3: {
            std::lock_guard bindings_lock(binding_mutex);
            test_rebind_bindings = bindings;
            for (uint32_t n = 0; n < 2; ++n) test_rebind_addresses[n] = original(n + 1);
            std::lock_guard lock(test_mutex); test_pause = value; test_hit = 0; test_released = false; return 0;
        }
        case 4: { std::lock_guard lock(test_mutex); return test_hit; }
        case 5: { std::lock_guard lock(test_mutex); test_released = true; test_pause = 0; test_condition.notify_all(); return 0; }
        case 6: case 18: {
            const auto binding = operation == 18 ? 0u : static_cast<size_t>(value - 1);
            if (binding >= bindings.size()) return -1;
            jclass owner = nullptr; jmethodID method = nullptr; void* address = nullptr;
            { std::lock_guard lock(test_mutex); owner = test_rebind_bindings[binding].owner; method = test_rebind_bindings[binding].method; address = test_rebind_addresses[binding]; }
            if (operation == 18) address = substituted_original(1);
            char* name = nullptr; char* descriptor = nullptr;
            if (!owner || !method || tooling->GetMethodName(method, &name, &descriptor, nullptr) != JVMTI_ERROR_NONE) return -1;
            JNINativeMethod replacement_method{name, descriptor, address};
            const auto result = env->RegisterNatives(owner, &replacement_method, 1);
            tooling->Deallocate(reinterpret_cast<unsigned char*>(name)); tooling->Deallocate(reinterpret_cast<unsigned char*>(descriptor));
            return result;
        }
        case 7:
            if (test_throwable) env->DeleteGlobalRef(test_throwable);
            test_throwable = object ? static_cast<jthrowable>(env->NewGlobalRef(object)) : nullptr;
            test_allocations = 0; test_fail_allocation = value; return 0;
        case 8: return test_allocations;
        case 9: return test_owner_refs;
        case 10: test_cleanup_limit = value; return 0;
        case 11: { auto actual = static_cast<jclass>(object); return tooling->RetransformClasses(1, &actual); }
        case 12: return replacement_serial();
        case 13: return test_binding_entered;
        case 14: return value >= 0 && value < MW_LIVE_MAX_CLASSES ? static_cast<jlong>(test_loaders[value]) : -1;
        case 15: return value >= 0 && value < MW_LIVE_MAX_CLASSES ? static_cast<jlong>(test_modules[value]) : -1;
        case 17: {
            mw_live_request_v1 request{}; mw_live_receipt_v1 receipt{}; uint32_t status = 0;
            { std::lock_guard lock(preparation_mutex); if (!checked) return -1;
              request.abi_version = 1; request.struct_bytes = sizeof(request); request.operation = 8;
              request.process_cookie = checked->cookie; request.process_start_ticks = checked->ticks; request.tgid = checked->pid;
              request.installation_id = value; }
            std::thread unattached([&] { status = mazewall_live_lifecycle_v1(&request, sizeof(request), &receipt, sizeof(receipt)); });
            unattached.join();
            const auto* payload = reinterpret_cast<const unsigned char*>(&receipt) + 16;
            for (size_t n = 0; n < sizeof(receipt) - 16; ++n) if (payload[n]) return -1;
            return (static_cast<uint64_t>(status) << 32) | receipt.reason;
        }
        case 19: test_dependency_mismatch = value != 0; return 0;
        case 20: { JNINativeMethod method{const_cast<char*>("probe0"), const_cast<char*>("()Z"), reinterpret_cast<void*>(test_replaced_probe)}; return env->RegisterNatives(helper_class, &method, 1); }
        case 21: return test_deployment_refs;
        case 22: { std::lock_guard lock(association_mutex); jlong count = 0; for (const auto& entry : associations) if (entry.installation) ++count; return count; }
        case 23: test_null_global = value != 0; return 0;
    }
    } catch (...) { return -1; }
    return -1;
}
#endif

bool refuse(const char* cause) noexcept {
    std::fprintf(stderr, "mazewall live startup: %s\n", cause);
    return false;
}
}

bool vm_init(JNIEnv* env, jvmtiEnv* jvmti) noexcept {
    if (env == nullptr || jvmti == nullptr || env->ExceptionCheck()) return refuse("pending exception or missing environment");
    if (published_helper.load()) return refuse("duplicate helper initialization");
    if (guard.state != GuardState::Inactive || guard.prepared || guard.attempt || guard.acknowledgment || guard.method)
        return refuse("incompatible probe preparation guard");
    const auto helper = env->FindClass("io/mazewall/ffi/internal/LiveStackBootstrap");
    if (helper == nullptr || env->ExceptionCheck()) return refuse("missing helper");
    const auto loader_class = env->FindClass("java/lang/ClassLoader");
    if (loader_class == nullptr || env->ExceptionCheck()) return refuse("loader class unavailable");
    const auto system_method = env->GetStaticMethodID(loader_class, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    if (system_method == nullptr || env->ExceptionCheck()) return refuse("system loader method unavailable");
    const auto system_loader = env->CallStaticObjectMethod(loader_class, system_method);
    if (system_loader == nullptr || env->ExceptionCheck()) return refuse("system loader unavailable");
    jobject defining_loader = nullptr;
    if (jvmti->GetClassLoader(helper, &defining_loader) != JVMTI_ERROR_NONE ||
        !env->IsSameObject(defining_loader, system_loader)) return refuse("wrong defining loader");
    const auto unnamed_method = env->GetMethodID(loader_class, "getUnnamedModule", "()Ljava/lang/Module;");
    if (unnamed_method == nullptr || env->ExceptionCheck()) return refuse("unnamed module method unavailable");
    const auto expected_module = env->CallObjectMethod(system_loader, unnamed_method);
    if (expected_module == nullptr || env->ExceptionCheck()) return refuse("unnamed module unavailable");
    const auto module = env->GetModule(helper);
    if (module == nullptr || env->ExceptionCheck() || !env->IsSameObject(module, expected_module)) return refuse("wrong helper module");
    jint class_modifiers = 0;
    if (jvmti->GetClassModifiers(helper, &class_modifiers) != JVMTI_ERROR_NONE ||
        (class_modifiers & 0x0011) != 0x0010) return refuse("helper must be package-private final");

    JNINativeMethod methods[] = {
        {const_cast<char*>("probe0"), const_cast<char*>("()Z"), reinterpret_cast<void*>(
#ifdef MAZEWALL_LIVE_JNI_TEST
            test_probe_resource_entry
#else
            probe_body
#endif
        )},
        {const_cast<char*>("checkDeployment0"), const_cast<char*>("(Lio/mazewall/ffi/internal/LiveStackBootstrap$LiveDeploymentIdentity;)[J"), reinterpret_cast<void*>(deployment)},
        {const_cast<char*>("enroll0"), const_cast<char*>("(J[Ljava/lang/Class;[Ljava/lang/reflect/Executable;)[J"), reinterpret_cast<void*>(enroll)},
        {const_cast<char*>("prepareWorker0"), const_cast<char*>("()V"), reinterpret_cast<void*>(worker)},
        {const_cast<char*>("checkSeal0"), const_cast<char*>("(JJ)V"), reinterpret_cast<void*>(seal)}
    };
    std::array<jmethodID, 5> identities{};
    for (std::size_t i = 0; i < identities.size(); ++i) {
        // GetStaticMethodID initializes the actual helper before admission.
        identities[i] = env->GetStaticMethodID(helper, methods[i].name, methods[i].signature);
        if (identities[i] == nullptr || env->ExceptionCheck()) return refuse("registration signature mismatch");
        jint modifiers = 0;
        jclass owner = nullptr;
        if (jvmti->GetMethodModifiers(identities[i], &modifiers) != JVMTI_ERROR_NONE ||
            modifiers != 0x010a ||
            jvmti->GetMethodDeclaringClass(identities[i], &owner) != JVMTI_ERROR_NONE ||
            !env->IsSameObject(owner, helper)) return refuse("helper method must be exact private static ordinary native");
        env->DeleteLocalRef(owner);
    }
    const auto reflected_probe = env->ToReflectedMethod(helper, identities[0], JNI_TRUE);
    if (reflected_probe == nullptr || env->ExceptionCheck()) return refuse("probe reflection unavailable");
    const auto method_class = env->GetObjectClass(reflected_probe);
    if (method_class == nullptr || env->ExceptionCheck()) return refuse("probe method class unavailable");
    const auto annotations_method = env->GetMethodID(method_class, "getDeclaredAnnotations", "()[Ljava/lang/annotation/Annotation;");
    if (annotations_method == nullptr || env->ExceptionCheck()) return refuse("probe annotations unavailable");
    const auto annotations = static_cast<jobjectArray>(env->CallObjectMethod(reflected_probe, annotations_method));
    if (annotations == nullptr || env->ExceptionCheck() || env->GetArrayLength(annotations) != 0) return refuse("probe must be nonintrinsic and unannotated");
    // This source-owned helper has no intrinsic annotations or mutable fields.
    jint field_count = 0;
    jfieldID* fields = nullptr;
    if (jvmti->GetClassFields(helper, &field_count, &fields) != JVMTI_ERROR_NONE) return refuse("helper fields unavailable");
    if (fields != nullptr) jvmti->Deallocate(reinterpret_cast<unsigned char*>(fields));
    if (field_count != 0) return refuse("unexpected helper field identity");
    helper_class = static_cast<jclass>(env->NewGlobalRef(helper));
    if (helper_class == nullptr || env->ExceptionCheck()) return refuse("helper pin failed");
    helper_loader = env->NewGlobalRef(defining_loader);
    if (helper_loader == nullptr || env->ExceptionCheck()) return refuse("loader pin failed");
    helper_module = env->NewGlobalRef(module);
    if (helper_module == nullptr || env->ExceptionCheck()) return refuse("module pin failed");
    if (env->RegisterNatives(helper_class, methods, 5) != JNI_OK || env->ExceptionCheck()) return refuse("RegisterNatives failed");
    helper_methods = identities;
    deployment_inputs = env->GetStaticMethodID(helper_class, "deploymentInputs", "(Lio/mazewall/ffi/internal/LiveStackBootstrap$LiveDeploymentIdentity;I)[Ljava/lang/String;");
    // Negative helper fixtures intentionally omit this preparation implementation.
    if (!deployment_inputs || env->ExceptionCheck()) return refuse("deployment preparation signature mismatch");
#ifndef MAZEWALL_MANAGED_T4_FIXTURE
    managed_identity = env->GetStaticMethodID(helper_class, "managedIdentity", "(Lio/mazewall/ffi/internal/LiveStackBootstrap$LiveDeploymentIdentity;)Z");
    if (!managed_identity || env->ExceptionCheck()) return refuse("deployment identity kind signature mismatch");
    managed_deployment_inputs = env->GetStaticMethodID(helper_class, "managedDeploymentInputs", "(Lio/mazewall/ffi/internal/LiveStackBootstrap$LiveDeploymentIdentity;I)[Ljava/lang/String;");
    if (!managed_deployment_inputs || env->ExceptionCheck()) return refuse("managed observation signature mismatch");
#endif
    const auto object_class = env->FindClass("java/lang/Object");
    if (!object_class || env->ExceptionCheck()) return refuse("identity equality unavailable");
    identity_equals = env->GetMethodID(object_class, "equals", "(Ljava/lang/Object;)Z");
    if (!identity_equals || env->ExceptionCheck() || env->GetJavaVM(&vm) != JNI_OK) return refuse("preparation cache unavailable");
    tooling = jvmti;
    const auto executable = env->FindClass("java/lang/reflect/Executable");
    if (!executable || env->ExceptionCheck()) return refuse("executable class unavailable");
    executable_class = static_cast<jclass>(env->NewGlobalRef(executable));
    if (!executable_class || env->ExceptionCheck()) return refuse("executable class pin failed");
#ifdef MAZEWALL_LIVE_JNI_TEST
    const auto workload = env->FindClass("io/mazewall/ffi/internal/LiveStackContextWorkload");
    JNINativeMethod controls[] = {{const_cast<char*>("control0"), const_cast<char*>("(IJLjava/lang/Object;)J"), reinterpret_cast<void*>(test_control)}};
    if (!workload || env->RegisterNatives(workload, controls, 1) || env->ExceptionCheck()) return refuse("test-only control registration");
#endif
    // Registration alone leaves native signature preparation lazy; finish it outside any activation.
    const auto prepared_probe = env->CallStaticBooleanMethod(helper_class, helper_methods[0]);
    if (env->ExceptionCheck()) return refuse("probe preparation exception");
    if (prepared_probe != JNI_FALSE) return refuse("probe preparation must return false");
    // Publish the complete write-once cache; concurrent bind hooks must not read partial JNI identities.
    published_helper.store(helper_class);
    return true;
}

bool prepare_worker(JNIEnv* env) noexcept {
    if (!env || env->ExceptionCheck()) return false;
    try {
        const auto association = checked_association(env);
        if (env->ExceptionCheck()) return false;
        jthread actual = nullptr;
        if (!tooling || tooling->GetCurrentThread(&actual) != JVMTI_ERROR_NONE || !actual)
            throw Refusal{"live worker: actual current JVM thread unavailable"};
        const bool virtual_thread = env->IsVirtualThread(actual);
        if (env->ExceptionCheck()) return false;
        if (virtual_thread) { env->DeleteLocalRef(actual); throw Refusal{"live worker: virtual thread unsupported"}; }
        if (!prepare_diagnostics(env, actual)) {
            if (env->ExceptionCheck()) return false;
            env->DeleteLocalRef(actual); throw Refusal{"live worker: diagnostic platform identity unavailable"};
        }
        const auto tid = syscall(SYS_gettid);
        if (getpid() != static_cast<pid_t>(association.pid) || tid <= 0 || static_cast<uint64_t>(tid) > UINT32_MAX) {
            env->DeleteLocalRef(actual); throw Refusal{"live worker: actual OS identity mismatch"};
        }
        const auto error =
#ifdef MAZEWALL_LIVE_JNI_TEST
            test_enable_failure ? JVMTI_ERROR_INTERNAL :
#endif
            tooling->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_ENTRY, actual);
        env->DeleteLocalRef(actual);
        if (error != JVMTI_ERROR_NONE) {
            const auto message = "live worker: MethodEntry enable failed: " + std::to_string(error);
            fail(env, message.c_str()); return false;
        }
        {
            std::lock_guard lock(binding_mutex);
            if (preparation_phase != PreparationPhase::Checked || !helper_intact ||
                replacement_serial() != association.serial || !targets(env, checked->deployment))
                throw Refusal{"live worker: checked JNI targets changed"};
        }
        if (env->ExceptionCheck()) return false;
        guard.prepared = true;
        current_platform_thread();
        return true;
    } catch (const Refusal& refusal) { fail(env, refusal.message.c_str()); }
      catch (const std::bad_alloc&) { out_of_memory(env); }
      catch (...) { fail(env, "live worker: native setup failure"); }
    return false;
}
void poison_guard() noexcept {
    poison();
    guard.state = GuardState::Poisoned;
    guard.acknowledgment = 0;
}
bool begin_activation() noexcept {
    switch (guard.state) {
        case GuardState::Inactive:
            guard.state = GuardState::Probing;
            guard.attempt = guard.acknowledgment = 0;
            guard.method = nullptr;
            return true;
        case GuardState::Probing: case GuardState::Acknowledged:
        case GuardState::Admitted: case GuardState::Poisoned:
            poison_guard(); return false;
    }
    return false;
}
void end_activation(bool owner) noexcept {
    leave();
    if (owner) {
        guard.state = GuardState::Inactive;
        guard.attempt = guard.acknowledgment = 0;
        guard.method = nullptr;
    }
}
void method_entry(jmethodID method) noexcept {
    switch (guard.state) {
        case GuardState::Inactive: return;
        case GuardState::Probing:
            if (guard.prepared && guard.attempt && guard.method && method == guard.method && !guard.acknowledgment) {
                guard.acknowledgment = guard.attempt;
#ifdef MAZEWALL_LIVE_JNI_TEST
                ++test_probe_entries;
#endif
                guard.state = GuardState::Acknowledged;
                return;
            }
            poison_guard(); return;
        case GuardState::Acknowledged: case GuardState::Admitted: case GuardState::Poisoned:
            poison_guard(); return;
    }
}
bool probe(JNIEnv* env) noexcept {
    if (!env || env->ExceptionCheck()) { poison_guard(); return false; }
    if (guard.state != GuardState::Probing || !guard.prepared || !published_helper.load() || !probe_eligible()) {
        poison_guard(); return false;
    }
    if (!mint_id(guard.attempt).ok()) { poison_guard(); return false; }
    guard.method = helper_methods[0];
    const auto result = env->CallStaticBooleanMethod(helper_class, guard.method);
    if (env->ExceptionCheck()) { poison_guard(); return false; }
    if (!result || guard.state != GuardState::Acknowledged || !guard.attempt ||
        guard.acknowledgment != guard.attempt || !probe_eligible()) {
        poison_guard(); return false;
    }
    guard.acknowledgment = 0;
    guard.state = GuardState::Admitted;
    return true;
}
void thread_end() noexcept {
    poison_guard();
    guard = {};
    current_thread_exited();
}
#ifdef MAZEWALL_LIVE_JNI_TEST
void test_observe_original() noexcept {
    ++test_original_calls;
    mw_live_snapshot_v1 snapshot{};
    test_observed_status = mazewall_live_copy_v1(&test_worker_request, sizeof(test_worker_request),
                                                &snapshot, sizeof(snapshot));
}
#endif
void publish_capture(uint32_t binding, const jvmtiFrameInfo* frames, jint count, bool failed, const Arguments& arguments) noexcept {
    if (guard.state != GuardState::Admitted || !probe_eligible()) return;
    if (failed || count <= 0 || !frames) { publish(binding, nullptr, 0, Reason::CaptureFailed); return; }
    if (count > static_cast<jint>(MW_LIVE_MAX_FRAMES)) { publish(binding, nullptr, 0, Reason::Overflow); return; }
    const auto* owner = static_cast<const EnrollmentOwner*>(current_enrollment_owner());
    if (!owner) { poison_guard(); return; }
    std::array<mw_live_frame_v1, MW_LIVE_MAX_FRAMES> translated{};
    for (jint n = 0; n < count; ++n) {
        const auto method = owner->methods.find(frames[n].method);
        if (method == owner->methods.end()) { publish(binding, nullptr, 0, Reason::UnknownMethod); return; }
        translated[n] = {method->second.token, frames[n].location};
    }
    if (guard.state == GuardState::Admitted && probe_eligible())
        publish(binding, translated.data(), static_cast<uint32_t>(count), Reason::None, arguments);
}

#ifdef MAZEWALL_MANAGED_T4_FIXTURE
namespace {
struct FixtureBindFacts {
    unsigned binding = 0;
    int initial_exception = -1, loader_status = -1, loader_null = -1, declaring_status = -1;
    int object_present = -1, module_present = -1, base_present = -1, later_exception = -1;
    int same_module = -1, same_declaring = -1, actual = -1, pin_present = -1;
    int phase = -1, prior_owner = -1, same_method = -1, same_owner = -1, valid = -1;
};
constexpr unsigned fixture_bind_capacity = 16;
thread_local FixtureBindFacts fixture_bind_stack[fixture_bind_capacity];
thread_local unsigned fixture_bind_depth = 0;
}
#endif
BindingUpdate::BindingUpdate(JNIEnv* env, jvmtiEnv* jvmti, jmethodID method, jclass owner, uint32_t binding) noexcept {
    jclass pin = nullptr;
    bool actual = false;
    jobject loader = nullptr; jclass declaring = nullptr;
#ifdef MAZEWALL_MANAGED_T4_FIXTURE
    const unsigned depth = fixture_bind_depth++;
    FixtureBindFacts overflow_facts;
    auto& facts = depth < fixture_bind_capacity ? fixture_bind_stack[depth] : overflow_facts;
    facts = {};
    facts.binding = binding;
    bool eligible = env && jvmti;
    if (eligible) { facts.initial_exception = env->ExceptionCheck() ? 1 : 0; eligible = facts.initial_exception == 0; }
    if (eligible) eligible = binding >= 1 && binding <= 2;
    if (eligible) { facts.loader_status = jvmti->GetClassLoader(owner, &loader); eligible = facts.loader_status == JVMTI_ERROR_NONE; }
    if (eligible) { facts.loader_null = loader ? 0 : 1; eligible = facts.loader_null == 1; }
    if (eligible) { facts.declaring_status = jvmti->GetMethodDeclaringClass(method, &declaring); eligible = facts.declaring_status == JVMTI_ERROR_NONE; }
    if (eligible) {
        const auto object = env->FindClass("java/lang/Object");
        const auto module = env->GetModule(owner); const auto base = object ? env->GetModule(object) : nullptr;
        facts.object_present = object ? 1 : 0; facts.module_present = module ? 1 : 0; facts.base_present = base ? 1 : 0;
        facts.later_exception = env->ExceptionCheck() ? 1 : 0;
        bool same = facts.later_exception == 0 && module && base;
        if (same) { facts.same_module = env->IsSameObject(module, base) ? 1 : 0; same = facts.same_module == 1; }
        if (same) { facts.same_declaring = env->IsSameObject(declaring, owner) ? 1 : 0; same = facts.same_declaring == 1; }
        actual = same;
        if (actual) pin = static_cast<jclass>(env->NewGlobalRef(owner));
        if (object) env->DeleteLocalRef(object);
        if (module) env->DeleteLocalRef(module);
        if (base) env->DeleteLocalRef(base);
    }
    facts.actual = actual ? 1 : 0; facts.pin_present = pin ? 1 : 0;
#else
    if (env && jvmti && !env->ExceptionCheck() && binding >= 1 && binding <= 2 &&
        jvmti->GetClassLoader(owner, &loader) == JVMTI_ERROR_NONE && !loader &&
        jvmti->GetMethodDeclaringClass(method, &declaring) == JVMTI_ERROR_NONE) {
        const auto object = env->FindClass("java/lang/Object");
        const auto module = env->GetModule(owner); const auto base = object ? env->GetModule(object) : nullptr;
        actual = !env->ExceptionCheck() && module && base && env->IsSameObject(module, base) && env->IsSameObject(declaring, owner);
        if (actual) pin = static_cast<jclass>(env->NewGlobalRef(owner));
        if (object) env->DeleteLocalRef(object);
        if (module) env->DeleteLocalRef(module);
        if (base) env->DeleteLocalRef(base);
    }
#endif
    if (env) { if (declaring) env->DeleteLocalRef(declaring); if (loader) env->DeleteLocalRef(loader); }
#ifdef MAZEWALL_LIVE_JNI_TEST
    ++test_binding_entered;
#endif
    binding_mutex.lock();
#ifdef MAZEWALL_MANAGED_T4_FIXTURE
    facts.phase = static_cast<int>(preparation_phase);
#endif
    switch (preparation_phase) {
        case PreparationPhase::Unchecked: break;
        case PreparationPhase::Checking: case PreparationPhase::Checked: case PreparationPhase::InitializationFailed: replacement(); break;
    }
    if (binding >= 1 && binding <= 2) {
        auto& entry = bindings[binding - 1];
#ifdef MAZEWALL_MANAGED_T4_FIXTURE
        bool valid = actual && pin;
        if (valid) facts.prior_owner = entry.owner ? 1 : 0;
        if (valid && entry.owner) {
            facts.same_method = entry.method == method ? 1 : 0;
            valid = facts.same_method == 1;
            if (valid) { facts.same_owner = env->IsSameObject(entry.owner, owner) ? 1 : 0; valid = facts.same_owner == 1; }
        }
        entry.valid = valid;
        facts.valid = entry.valid ? 1 : 0;
#else
        entry.valid = actual && pin && (!entry.owner || (entry.method == method && env->IsSameObject(entry.owner, owner)));
#endif
        if (!entry.owner) { entry.owner = pin; pin = nullptr; }
        entry.method = method;
    }
    if (pin) env->DeleteGlobalRef(pin);
}
BindingUpdate::~BindingUpdate() {
    binding_mutex.unlock();
#ifdef MAZEWALL_MANAGED_T4_FIXTURE
    const unsigned depth = --fixture_bind_depth;
    if (depth >= fixture_bind_capacity) {
        std::fprintf(stderr, "MANAGED_T4_BIND_UPDATE overflow=1 depth=%u facts=not_evaluated\n", depth);
        return;
    }
    const auto& f = fixture_bind_stack[depth];
    std::fprintf(stderr, "MANAGED_T4_BIND_UPDATE selected=%u initial_exception=%d loader_status=%d loader_null=%d declaring_status=%d object=%d module=%d base=%d later_exception=%d same_module=%d same_declaring=%d actual=%d pin=%d phase=%d prior_owner=%d same_method=%d same_owner=%d valid=%d\n",
        f.binding, f.initial_exception, f.loader_status, f.loader_null, f.declaring_status, f.object_present,
        f.module_present, f.base_present, f.later_exception, f.same_module, f.same_declaring, f.actual, f.pin_present,
        f.phase, f.prior_owner, f.same_method, f.same_owner, f.valid);
#endif
}
void observed_replacement(JNIEnv* env, jclass owner) noexcept {
    std::lock_guard lock(binding_mutex);
    replacement();
    const auto helper = published_helper.load();
    if (helper && env->IsSameObject(owner, helper)) { helper_intact = false; shutdown(); }
}
void observe_helper_binding(JNIEnv* env, jclass owner, jmethodID method) noexcept {
    const auto helper = published_helper.load();
    if (!helper || !env || !env->IsSameObject(owner, helper)) return;
    std::lock_guard lock(binding_mutex);
    for (auto expected : helper_methods) if (expected && expected == method) { helper_intact = false; replacement(); shutdown(); break; }
}
}
