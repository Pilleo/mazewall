#ifdef MAZEWALL_LIVE_PARTICIPANT_TEST
#include <jni.h>
extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM*, char*, void*) { return JNI_OK; }
#else
#include "../src/agent.cpp"
#include "../src/live_stack_jni.cpp"
#include "live_stack_context_abi.h"

#include <cassert>
#include <iostream>
#include <sys/wait.h>

#ifdef MAZEWALL_LIVE_PREPARATION_TEST
// A real embedded host JVM, not the component JNI tables below or a readiness publisher.
int main(int argc, char** argv) {
    assert(argc == 3);
    const rlimit no_core{0, 0};
    assert(setrlimit(RLIMIT_CORE, &no_core) == 0);
    std::string classpath = std::string("-Djava.class.path=") + argv[2];
    JavaVMOption options[] = {{classpath.data(), nullptr}, {const_cast<char*>("-XX:-CreateCoredumpOnCrash"), nullptr}};
    JavaVMInitArgs args{};
    args.version = JNI_VERSION_21; args.nOptions = 2; args.options = options;
    JavaVM* actual_vm = nullptr;
    JNIEnv* env = nullptr;
    assert(JNI_CreateJavaVM(&actual_vm, reinterpret_cast<void**>(&env), &args) == JNI_OK);
    jvmtiEnv* actual_tooling = nullptr;
    assert(actual_vm->GetEnv(reinterpret_cast<void**>(&actual_tooling), JVMTI_VERSION_1_2) == JNI_OK);
    namespace jni = mazewall::live::jni;
    const std::string selected = argv[1];
    if (selected == "dispatch-owner") {
        const auto* expected_jni = env->functions;
        const auto* expected_tooling = actual_tooling->functions;
        assert(jni::dispatch_owners(env, actual_tooling, expected_jni, expected_tooling));
        assert(!jni::dispatch_owners(env, nullptr, expected_jni, expected_tooling));
        assert(!jni::dispatch_owners(nullptr, actual_tooling, expected_jni, expected_tooling));
        assert(!jni::dispatch_owners(env, actual_tooling, nullptr, expected_tooling));
        assert(!jni::dispatch_owners(env, actual_tooling, expected_jni, nullptr));
        const auto copied_table = *expected_tooling;
        actual_tooling->functions = &copied_table;
        const bool admitted_copy = jni::dispatch_owners(env, actual_tooling, expected_jni, expected_tooling);
        assert(actual_tooling->functions == &copied_table);
        actual_tooling->functions = expected_tooling;
        assert(!admitted_copy && "actual retained JVMTI environment admitted a copied table");
        assert(jni::dispatch_owners(env, actual_tooling, expected_jni, expected_tooling));
        assert(!mazewall::live::probe_eligible());
        std::cout << "actual JVMTI table substitution refused; restored; no authority\n";
        return 0;
    }
    const auto type = env->FindClass("java/lang/OutOfMemoryError");
    const auto constructor = env->GetMethodID(type, "<init>", "()V");
    const auto exact = static_cast<jthrowable>(env->NewObject(type, constructor));
    assert(exact && !env->ExceptionCheck());
    jni::test_throwable = exact;
    jni::test_preparation_true = selected == "true";
    jni::test_probe_resource_fault = selected == "throw";
    if (selected == "pending") assert(env->Throw(exact) == JNI_OK);
    if (selected == "prepared") jni::guard.prepared = true;
    if (selected == "active") jni::guard.state = jni::GuardState::Probing;
    const auto before = jni::guard;
    const bool ok = jni::vm_init(env, actual_tooling);
    const bool success = selected == "false";
    assert(ok == success);
    assert(bool(jni::published_helper.load()) == success);
    const bool invoked = selected == "false" || selected == "true" || selected == "throw";
    assert(jni::test_preparation_calls == (invoked ? 1u : 0u));
    if (invoked) assert(jni::test_preparation_inactive);
    assert(jni::guard.state == before.state && jni::guard.prepared == before.prepared);
    assert(!jni::guard.attempt && !jni::guard.acknowledgment && !jni::guard.method);
    assert(!mazewall::live::probe_eligible());
    const bool exceptional = selected == "throw" || selected == "pending";
    assert(bool(env->ExceptionCheck()) == exceptional);
    if (exceptional) {
        // Both operations are permitted with an exception pending; never clear/replace it.
        const auto caught = env->ExceptionOccurred();
        assert(env->IsSameObject(caught, exact) && env->ExceptionCheck());
    }
    if (success) {
        assert(!jni::vm_init(env, actual_tooling));
        assert(jni::test_preparation_calls == 1 && jni::published_helper.load());
    }
    std::cout << "actual JNI preparation " << selected << ": calls=" << jni::test_preparation_calls
              << " published=" << success << " acknowledgment=0 exact-exception=" << exceptional << "\n";
    // No Java workload is admitted on setup failure; the startup callback treats false as fatal.
    if (success) std::cout << "preparation-workload: admitted\n";
}
#else
namespace {
int original_calls = 0;
bool pending_exception = false;
jboolean JNICALL exception_check(JNIEnv*) { errno = EDOM; return pending_exception ? JNI_TRUE : JNI_FALSE; }
jint JNICALL scalar_original(JNIEnv*, jclass, jlong, jint, jint) {
    ++original_calls;
    errno = EACCES;
    return 73; // A scalar test result, never an owned descriptor.
}
void guard_outcomes() {
    JNINativeInterface_ table{};
    table.ExceptionCheck = exception_check;
    JNIEnv env{&table};
    live_startup_mode = LiveStartupMode::Only;
    original_unix_open0.store(scalar_original);
    pending_exception = true;
    errno = EINTR;
    assert(proxy_unix_open0(&env, nullptr, 0, 0, 0) == -1);
    assert(original_calls == 0 && errno == EINTR && pending_exception);
    pending_exception = false;
    errno = EPERM;
    assert(proxy_unix_open0(&env, nullptr, 0, 0, 0) == 73);
    assert(original_calls == 1 && errno == EACCES && !pending_exception);
}
jvmtiError JNICALL capture_raw(jvmtiEnv*, jthread thread, jint start, jint capacity,
                              jvmtiFrameInfo* frames, jint* count) {
    assert(thread == reinterpret_cast<jthread>(1) && start == 0 && capacity == 129);
    ++original_calls;
    *count = 129;
    frames[128].location = 73;
    return JVMTI_ERROR_NONE;
}
int diagnostic_id_calls = 0;
jboolean JNICALL throwing_virtual_query(JNIEnv*, jobject, jmethodID, va_list) {
    pending_exception = true;
    return JNI_FALSE;
}
jlong JNICALL diagnostic_id_query(JNIEnv*, jobject, jmethodID, va_list) { ++diagnostic_id_calls; return 73; }
void capture_acquisition() {
    jvmtiInterface_1_ table{};
    table.GetStackTrace = capture_raw;
    jvmtiEnv tooling{&table};
    global_jvmti = &tooling;
    original_calls = 0;
    const auto raw = acquire_stack(reinterpret_cast<jthread>(1));
    assert(!raw.failed && raw.count == 129 && raw.frames[128].location == 73);
    assert(original_calls == 1);
    JNINativeInterface_ jni{};
    jni.ExceptionCheck = exception_check;
    jni.CallBooleanMethodV = throwing_virtual_query;
    jni.CallLongMethodV = diagnostic_id_query;
    JNIEnv env{&jni};
    thread_is_virtual.store(reinterpret_cast<jmethodID>(1));
    thread_id.store(reinterpret_cast<jmethodID>(2));
    const auto context = execution_context(&env, reinterpret_cast<jthread>(1));
    assert(!context.valid() && pending_exception && diagnostic_id_calls == 0);
}
// Component fakes only: these cache scalars and synthetic enrollment never prove JVM readiness.
enum class Stimulus { Good, Missing, Wrong, Duplicate, Stale, False, DirectC, Callback,
                      EntryException, ProbeEntryException, BodyException, ReturnException, PreBodyException, Nested, NestedException,
                      Cancel, Replacement, Shutdown, CancelException, ReplacementException, ShutdownException };
Stimulus stimulus = Stimulus::Good;
int probe_calls = 0, acquisition_calls = 0, body_calls = 0;
mw_live_request_v1 copy_request{};
uint32_t original_status = 99;
uint64_t component_token = 0;
int delegated_errno = 0;
jint component_frame_count = 1;
bool component_capture_failed = false, component_capture_callback = false, component_unknown_tail = false;
bool component_original_exception = false;
bool component_weak_failure = false;
int component_original_error = EPERM;
bool component_openat = false;
jthrowable throwable = reinterpret_cast<jthrowable>(0x1234);
jthrowable pending_object = nullptr;
jboolean JNICALL component_exception(JNIEnv*) { errno = EDOM; return pending_object ? JNI_TRUE : JNI_FALSE; }
jboolean JNICALL same_object(JNIEnv*, jobject a, jobject b) { return a == b; }
void JNICALL delete_local(JNIEnv*, jobject) {}
jweak JNICALL component_weak_ref(JNIEnv*, jobject object) { return component_weak_failure ? nullptr : object; }
jvmtiError JNICALL actual_thread(jvmtiEnv*, jthread* thread) { *thread = reinterpret_cast<jthread>(1); return JVMTI_ERROR_NONE; }
jvmtiError JNICALL component_capture(jvmtiEnv*, jthread, jint, jint max, jvmtiFrameInfo* frames, jint* count) {
    assert(max == 129); ++acquisition_calls;
    for (jint n = 0; n < component_frame_count; ++n) frames[n] = {reinterpret_cast<jmethodID>(1), -1};
    if (component_unknown_tail) frames[component_frame_count - 1].method = reinterpret_cast<jmethodID>(2);
    *count = component_frame_count;
    if (component_capture_callback) mazewall::live::jni::method_entry(reinterpret_cast<jmethodID>(2));
    return component_capture_failed ? JVMTI_ERROR_INTERNAL : JVMTI_ERROR_NONE;
}
jint JNICALL component_original(JNIEnv*, jclass, jlong, jint, jint) {
    ++original_calls;
    delegated_errno = errno;
    mw_live_snapshot_v1 snapshot{};
    original_status = mazewall_live_copy_v1(&copy_request, sizeof(copy_request), &snapshot, sizeof(snapshot));
    if (original_status == MW_LIVE_AVAILABLE) {
        assert(snapshot.frame_count == static_cast<uint32_t>(component_frame_count) && snapshot.frames[0].method_token == component_token);
        assert(snapshot.binding == (component_openat ? 2u : 1u));
    }
    if (component_original_exception) pending_object = throwable;
    errno = component_original_error;
    return 73;
}
jint JNICALL component_original_at(JNIEnv* env, jclass owner, jint dirfd, jlong path, jint flags, jint mode) {
    assert(dirfd == -100);
    return component_original(env, owner, path, flags, mode);
}
jint component_invoke(JNIEnv* env) {
    return component_openat ? proxy_unix_openat0(env, nullptr, -100, 0, 0, 0) : proxy_unix_open0(env, nullptr, 0, 0, 0);
}
jboolean JNICALL component_probe(JNIEnv* env, jclass, jmethodID method, va_list) {
    namespace jni = mazewall::live::jni;
    ++probe_calls;
    assert(method == reinterpret_cast<jmethodID>(1));
    if (stimulus == Stimulus::PreBodyException) { pending_object = throwable; return JNI_FALSE; }
    if (stimulus != Stimulus::Missing && stimulus != Stimulus::DirectC)
        jni::method_entry(stimulus == Stimulus::Wrong ? reinterpret_cast<jmethodID>(2) : method);
    if (stimulus == Stimulus::Duplicate) jni::method_entry(method);
    if (stimulus == Stimulus::Callback) jni::method_entry(reinterpret_cast<jmethodID>(2));
    if (stimulus == Stimulus::Stale) --jni::guard.acknowledgment;
    if (stimulus == Stimulus::BodyException) pending_object = throwable;
    if (stimulus == Stimulus::ProbeEntryException) { pending_object = throwable; return JNI_FALSE; }
    if (stimulus == Stimulus::Nested || stimulus == Stimulus::NestedException) {
        if (stimulus == Stimulus::NestedException) pending_object = throwable;
        assert(component_invoke(env) == (pending_object ? -1 : 73));
    }
    if (stimulus == Stimulus::Cancel || stimulus == Stimulus::CancelException) {
        auto q = copy_request; q.operation = 6;
        q.worker_id = q.task_id = q.code_generation = q.tid = 0;
        mw_live_receipt_v1 receipt{};
        assert(mazewall_live_lifecycle_v1(&q, sizeof(q), &receipt, sizeof(receipt)) == 0);
    }
    if (stimulus == Stimulus::Replacement || stimulus == Stimulus::ReplacementException) mazewall::live::replacement();
    if (stimulus == Stimulus::Shutdown || stimulus == Stimulus::ShutdownException) mazewall::live::shutdown();
    if (stimulus == Stimulus::CancelException || stimulus == Stimulus::ReplacementException || stimulus == Stimulus::ShutdownException)
        pending_object = throwable;
    ++body_calls;
    const auto result = jni::probe_body(env, nullptr);
    if (stimulus == Stimulus::ReturnException) pending_object = throwable;
    return stimulus == Stimulus::False ? JNI_FALSE : result;
}
bool component_cleanup(void* p) noexcept {
    delete static_cast<mazewall::live::jni::EnrollmentOwner*>(p); return true;
}
void component_setup() {
    namespace live = mazewall::live;
    namespace jni = mazewall::live::jni;
    assert(live::initialize({91, 37, static_cast<uint32_t>(getpid()), 3, true, true, true}));
    mw_live_request_v1 q{};
    q.abi_version = 1; q.struct_bytes = sizeof(q); q.process_cookie = 91;
    q.process_start_ticks = 37; q.tgid = getpid(); q.operation = 1; q.worker_capacity = 1;
    const auto call = [&] {
        mw_live_receipt_v1 r{};
        assert(mazewall_live_lifecycle_v1(&q, sizeof(q), &r, sizeof(r)) == 0); return r;
    };
    q.installation_id = call().installation_id; q.worker_capacity = 0;
    auto* owner = new jni::EnrollmentOwner;
    assert(live::mint_id(component_token).ok());
    owner->methods.emplace(reinterpret_cast<jmethodID>(1), jni::MethodIdentity{component_token, 0, "", "", 0});
    { live::Enrollment e(q.installation_id);
      assert(e.commit(owner, component_cleanup, 1, &component_token, 1, q.code_generation).ok()); }
    q.operation = 2; call();
    live::current_platform_thread();
    q.operation = 3; q.tid = syscall(SYS_gettid); q.worker_id = call().worker_id;
    q.operation = 4; q.task_id = call().task_id; q.operation = 0; copy_request = q;
    jni::published_helper.store(reinterpret_cast<jclass>(1));
    jni::helper_class = reinterpret_cast<jclass>(1);
    jni::helper_methods[0] = reinterpret_cast<jmethodID>(1);
    jni::guard.prepared = true;
}
void guard_component_case(Stimulus selected) {
    component_setup(); stimulus = selected;
    original_calls = probe_calls = body_calls = acquisition_calls = 0;
    original_status = 99; pending_object = selected == Stimulus::EntryException ? throwable : nullptr;
    JNINativeInterface_ table{};
    table.ExceptionCheck = component_exception; table.IsSameObject = same_object;
    table.CallStaticBooleanMethodV = component_probe; table.DeleteLocalRef = delete_local;
    JNIEnv env{&table};
    jvmtiInterface_1_ tooling_table{};
    tooling_table.GetCurrentThread = actual_thread; tooling_table.GetStackTrace = component_capture;
    jvmtiEnv tooling{&tooling_table}; global_jvmti = &tooling;
    original_unix_open0.store(component_original);
    original_unix_openat0.store(component_original_at);
    live_startup_mode = LiveStartupMode::Only;
    errno = EINTR;
    const auto result = component_invoke(&env);
    const int result_errno = errno;
    const bool exceptional = selected == Stimulus::EntryException || selected == Stimulus::BodyException ||
        selected == Stimulus::ProbeEntryException || selected == Stimulus::ReturnException ||
        selected == Stimulus::CancelException || selected == Stimulus::ReplacementException || selected == Stimulus::ShutdownException ||
        selected == Stimulus::PreBodyException || selected == Stimulus::NestedException;
    assert(result == (exceptional ? -1 : 73));
    assert(original_calls == (exceptional ? 0 : selected == Stimulus::Nested ? 2 : 1));
    assert(result_errno == (exceptional ? EINTR : EPERM));
    assert(exceptional ? env.IsSameObject(pending_object, throwable) : pending_object == nullptr);
    if (!exceptional) assert(delegated_errno == EINTR);
    assert(probe_calls == (selected == Stimulus::EntryException ? 0 : 1));
    assert(acquisition_calls == (selected == Stimulus::Good ? 1 : 0));
    if (!exceptional) assert((original_status == MW_LIVE_AVAILABLE) == (selected == Stimulus::Good));
    assert(mazewall::live::jni::guard.state == mazewall::live::jni::GuardState::Inactive);
    assert(!mazewall::live::probe_eligible());
}
jvmtiError JNICALL metadata_failure(jvmtiEnv*, jmethodID, char**, char**, char**) { return JVMTI_ERROR_INTERNAL; }
void capture_component_case(int selected) {
    component_setup();
    original_calls = probe_calls = body_calls = acquisition_calls = 0;
    pending_object = nullptr;
    stimulus = selected == 8 ? Stimulus::Missing : Stimulus::Good;
    component_frame_count = selected == 1 || selected == 3 ? 128 : selected == 2 ? 129 : 1;
    component_unknown_tail = selected == 3;
    component_capture_callback = selected == 4;
    component_capture_failed = selected == 5;
    component_original_exception = selected == 10;
    component_original_error = selected == 11 ? EACCES : selected == 12 ? EINTR : EPERM;
    JNINativeInterface_ table{};
    table.ExceptionCheck = component_exception; table.IsSameObject = same_object;
    table.CallStaticBooleanMethodV = component_probe; table.DeleteLocalRef = delete_local;
    table.NewWeakGlobalRef = component_weak_ref; table.DeleteWeakGlobalRef = delete_local;
    JNIEnv env{&table};
    jvmtiInterface_1_ tooling_table{};
    tooling_table.GetCurrentThread = actual_thread; tooling_table.GetStackTrace = component_capture;
    tooling_table.GetMethodName = metadata_failure;
    jvmtiEnv tooling{&tooling_table}; global_jvmti = &tooling;
    original_unix_open0.store(component_original);
    original_unix_openat0.store(component_original_at);
    live_startup_mode = (selected >= 6 && selected <= 8) || selected >= 13 ? LiveStartupMode::Shared : LiveStartupMode::Only;
    if (live_startup_mode == LiveStartupMode::Shared) prepared_worker_execution = ExecutionContext{1, ExecutionKind::platform};
    if (selected == 7) {
        capture_profile = *mazewall::profiler::CaptureProfile::parse(component_openat ? "unix-open0" : "jdk25-unix-openat0");
        capture_profile_admitted.store(true);
    }
    if (selected == 9) assert(mazewall::live::test_gate(copy_request.worker_id, true));
    if (selected == 13) {
        component_weak_failure = true;
        metadata_cache.emplace(FrameMetadataKey{1, 1}, CachedFrameMetadata{
            LogicalFrame{"bootstrap", "LC;", "m", "()V", -1, true}, reinterpret_cast<jweak>(1), 0});
    }
    if (selected == 14) {
        RawStack raw; raw.frames.push_back(RawFrame{1, -1});
        canonical_contexts.emplace(std::move(raw), CanonicalContext{1, {}, 0});
        native_retained_limit.store(0); // Reject dictionary publication, not capture or live ownership.
    }
    errno = EBUSY;
    assert(component_invoke(&env) == 73);
    const int result_errno = errno;
    if (selected == 9) assert(mazewall::live::test_gate(copy_request.worker_id, false));
    assert(original_calls == 1 && delegated_errno == EBUSY && result_errno == component_original_error);
    assert(acquisition_calls == 1);
    const bool available = selected == 0 || selected == 1 || selected == 6 || selected == 7 || selected >= 10;
    assert((original_status == MW_LIVE_AVAILABLE) == available);
    if (selected == 2 || selected == 3 || selected == 5) assert(original_status == MW_LIVE_INCOMPLETE);
    if (selected == 6 || selected == 8 || selected == 13) {
        assert(stack_capture_failures.load() == 1);
        assert(scope_failures.load() == 2 && invocation_definitions.empty());
        assert(invocation_registry.current_id() == 0 && stack_capture_count.load() == 0);
    }
    if (selected == 14) {
        assert(stack_capture_count.load() == 1 && stack_capture_failures.load() == 0);
        assert(scope_failures.load() == 1 && invocation_definitions.empty());
        assert(native_retention_exhaustions.load() == 1 && invocation_registry.current_id() == 0);
    }
    if (selected == 7) {
        assert(stack_capture_failures.load() == 0);
        assert(capture_profile_excluded_counts[component_openat ? 1 : 0].load() == 1);
    }
    if (selected == 10) assert(env.IsSameObject(pending_object, throwable));
    else assert(!pending_object);
    assert(mazewall::live::jni::guard.state == mazewall::live::jni::GuardState::Inactive);
    if (selected == 0) {
        const auto old_attempt = mazewall::live::jni::guard.attempt;
        stimulus = Stimulus::Missing;
        errno = EBUSY;
        assert(component_invoke(&env) == 73);
        assert(original_calls == 2 && acquisition_calls == 1 && original_status != MW_LIVE_AVAILABLE);
        assert(old_attempt == 0); // The preceding activation left no reusable witness.
    }
}
std::vector<int> calls;
int failure = 0;
int missing = 0;
jvmtiEnv* provided_env = nullptr;
jint JNICALL get_env(JavaVM*, void** out, jint version) {
    assert(version == JVMTI_VERSION_1_2);
    *out = provided_env;
    return JNI_OK;
}
jvmtiEventCallbacks installed{};
jvmtiError JNICALL add(jvmtiEnv*, const jvmtiCapabilities* caps) {
    calls.push_back(1);
    assert(caps->can_generate_native_method_bind_events && caps->can_tag_objects &&
           caps->can_support_virtual_threads && caps->can_generate_all_class_hook_events);
    const bool live = live_startup_mode != LiveStartupMode::DiagnosticOnly;
    assert(bool(caps->can_retransform_classes) == live && bool(caps->can_generate_method_entry_events) == live);
    return failure == 1 ? JVMTI_ERROR_INTERNAL : JVMTI_ERROR_NONE;
}
jvmtiError JNICALL get(jvmtiEnv*, jvmtiCapabilities* caps) {
    calls.push_back(2);
    *caps = {};
    caps->can_generate_native_method_bind_events = missing != 1;
    caps->can_tag_objects = missing != 2;
    caps->can_support_virtual_threads = missing != 3;
    caps->can_generate_all_class_hook_events = missing != 4;
    caps->can_retransform_classes = missing != 5;
    caps->can_generate_method_entry_events = missing != 6;
    return failure == 2 ? JVMTI_ERROR_INTERNAL : JVMTI_ERROR_NONE;
}
jvmtiError JNICALL callbacks(jvmtiEnv*, const jvmtiEventCallbacks* value, jint) {
    calls.push_back(3);
    installed = *value;
    assert(value->VMInit);
    if (live_startup_mode != LiveStartupMode::DiagnosticOnly) assert(value->MethodEntry && value->ThreadEnd && value->VMDeath);
    else assert(!value->MethodEntry && !value->ThreadEnd && !value->VMDeath);
    return failure == 3 ? JVMTI_ERROR_INTERNAL : JVMTI_ERROR_NONE;
}
jvmtiError JNICALL enable(jvmtiEnv*, jvmtiEventMode mode, jvmtiEvent event, jthread thread, ...) {
    assert(mode == JVMTI_ENABLE && thread == nullptr && event != JVMTI_EVENT_METHOD_ENTRY);
    calls.push_back(static_cast<int>(event));
    return failure == static_cast<int>(event) ? JVMTI_ERROR_INTERNAL : JVMTI_ERROR_NONE;
}
void unready() {
    mw_live_info_v1 info{};
    assert(mazewall_live_info_v1(&info, sizeof(info)) == MW_LIVE_UNSUPPORTED);
    assert(info.process_cookie == 0 && info.tgid == 0 && info.binding_mask == 0);
}
void isolated(const auto& test) {
    const auto pid = fork();
    assert(pid >= 0);
    if (pid == 0) { test(); _exit(0); }
    int status = 0;
    assert(waitpid(pid, &status, 0) == pid);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}
void options() {
    isolated([] { assert(parse_options(nullptr)); unready(); });
    for (const auto* selection : {"live-stack=only", "live-stack=shared"}) {
        isolated([&] { assert(parse_options(selection)); unready(); });
        for (const auto* suffix : {"live-stack=only", "live-stack=shared", "live-stack=bad",
                                  "test-open-boundary=/tmp/not-created",
                                  "session=abc,session-bound-definitions,definitions=/tmp/not-created.mzse,capture-yield=/tmp/not-created.mzcy,test-open-boundary=/tmp/not-created.mzob"}) {
            for (bool reverse : {false, true}) isolated([&] {
                const auto text = reverse ? std::string(suffix) + "," + selection : std::string(selection) + "," + suffix;
                assert(!parse_options(text.c_str()));
                assert(!open_boundary_recorder && !capture_yield_recorder && !invocation_fd_recorder && !lifecycle_barrier);
            });
        }
    }
    isolated([] { assert(!parse_options("live-stack=unknown")); });
    for (const auto* option : {"definitions=/tmp/not-created", "capture-yield=/tmp/not-created",
            "invocation-fd=/tmp/not-created", "lifecycle-barrier=/tmp/not-created", "benchmark-phases",
             "mode=full-stream", "live-definitions", "marker-version=v1", "force-capture-failure-once",
            "capture-profile=jdk25-unix-openat0", "session=abc", "canonical-cache=disabled",
            "mode=unique-stack-syscall", "marker-version=v2", "marker-version-proof=/tmp/not-created",
            "stack-transport=dictionary", "stack-transport=inline-per-invocation", "retained-budget-bytes=1",
            "capture-timing-diagnostic", "session-bound-definitions", "canonical-cache=enabled",
            "lifecycle-gate=/tmp/not-created", "capture-failure-gate=/tmp/not-created", "force-exec-once",
            "callback-suppression-probe", "callback-suppression-gate=/tmp/not-created", "capture-bindings=unix-open0"}) {
        for (bool reverse : {false, true}) isolated([&] {
            const auto text = reverse ? std::string(option) + ",live-stack=only" : std::string("live-stack=only,") + option;
            assert(!parse_options(text.c_str()));
            assert(!open_boundary_recorder && !capture_yield_recorder && !invocation_fd_recorder && !lifecycle_barrier);
            assert(definitions_path.empty() && capture_yield_path.empty());
        });
    }
    isolated([] {
        assert(parse_options("live-stack=only"));
        const auto next = next_invocation_id.load();
        JNINativeInterface_ table{};
        table.ExceptionCheck = exception_check;
        JNIEnv env{&table};
        { InvocationScope scope(&env, mazewall::profiler::BindingKind::unix_open0); }
        assert(next_invocation_id.load() == next && stack_capture_count.load() == 0);
        assert(invocation_definitions.empty() && logical_stacks.empty() && !live_publisher.joinable());
    });
    isolated([] { assert(parse_options("live-stack=shared,mode=full-stream,live-definitions")); });
    isolated([] { assert(!parse_options("live-stack=shared,capture-timing-diagnostic")); });
}
void startup() {
    jvmtiInterface_1_ table{};
    table.AddCapabilities = add;
    table.GetCapabilities = get;
    table.SetEventCallbacks = callbacks;
    table.SetEventNotificationMode = enable;
    jvmtiEnv env{&table};
    JNIInvokeInterface_ vm_table{};
    vm_table.GetEnv = get_env;
    JavaVM vm{&vm_table};
    provided_env = &env;
    const std::vector<int> expected{1, 2, 3, JVMTI_EVENT_VM_INIT, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK,
        JVMTI_EVENT_NATIVE_METHOD_BIND, JVMTI_EVENT_CLASS_PREPARE, JVMTI_EVENT_VIRTUAL_THREAD_END,
        JVMTI_EVENT_THREAD_END, JVMTI_EVENT_VM_DEATH};
    for (int absent = 0; absent <= 6; ++absent) {
        for (int fail : {0, 1, 2, 3, static_cast<int>(JVMTI_EVENT_VM_INIT),
                static_cast<int>(JVMTI_EVENT_CLASS_FILE_LOAD_HOOK), static_cast<int>(JVMTI_EVENT_NATIVE_METHOD_BIND),
                static_cast<int>(JVMTI_EVENT_CLASS_PREPARE), static_cast<int>(JVMTI_EVENT_VIRTUAL_THREAD_END),
                static_cast<int>(JVMTI_EVENT_THREAD_END), static_cast<int>(JVMTI_EVENT_VM_DEATH)}) isolated([&] {
            failure = fail; missing = absent;
            const bool ok = Agent_OnLoad(&vm, const_cast<char*>("live-stack=only"), nullptr) == JNI_OK;
            assert(ok == (absent == 0 && fail == 0));
            assert(live_hooks_ready.load() == ok);
            assert(std::equal(calls.begin(), calls.end(), expected.begin()));
            if (ok) assert(calls == expected);
            if (absent != 0 && fail != 1) assert(calls.size() == 2);
            unready();
        });
    }
    isolated([&] {
        assert(configure_native_bind_events(&env));
        auto diagnostic = expected;
        diagnostic.erase(diagnostic.begin() + 1);
        diagnostic.resize(7);
        assert(calls == diagnostic && !live_hooks_ready.load());
        unready();
    });
}
}
int main(int argc, char** argv) {
    const rlimit no_core{0, 0};
    assert(setrlimit(RLIMIT_CORE, &no_core) == 0);
    assert(argc == 2);
    if (std::strcmp(argv[1], "options") == 0) options();
    else if (std::strcmp(argv[1], "guard") == 0) guard_outcomes();
    else if (std::strcmp(argv[1], "capture") == 0) capture_acquisition();
    else if (std::strncmp(argv[1], "guard-component", 15) == 0) {
        component_openat = std::strcmp(argv[1], "guard-component-openat0") == 0;
        for (auto stimulus : {Stimulus::Good, Stimulus::Missing, Stimulus::Wrong, Stimulus::Duplicate,
                Stimulus::Stale, Stimulus::False, Stimulus::DirectC, Stimulus::Callback, Stimulus::EntryException,
                Stimulus::ProbeEntryException, Stimulus::BodyException, Stimulus::ReturnException, Stimulus::PreBodyException, Stimulus::Nested, Stimulus::NestedException,
                Stimulus::Cancel, Stimulus::Replacement, Stimulus::Shutdown,
                Stimulus::CancelException, Stimulus::ReplacementException, Stimulus::ShutdownException})
            isolated([=] { guard_component_case(stimulus); });
    }
    else if (std::strncmp(argv[1], "capture-component", 17) == 0) {
        component_openat = std::strcmp(argv[1], "capture-component-openat0") == 0;
        for (int selected = 0; selected <= 14; ++selected) isolated([=] { capture_component_case(selected); });
    }
    else startup();
    std::cout << "live_stack_agent_" << argv[1] << ": passed\n";
}
#endif
#endif
