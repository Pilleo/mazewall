#pragma once

#include <jni.h>
#include <jvmti.h>
#include <cstdint>

namespace mazewall::live::jni {
__attribute__((visibility("hidden"))) bool prepare_worker(JNIEnv*) noexcept;
__attribute__((visibility("hidden"))) bool prepare_diagnostics(JNIEnv*, jthread) noexcept;
__attribute__((visibility("hidden"))) void method_entry(jmethodID) noexcept;
__attribute__((visibility("hidden"))) bool probe(JNIEnv*) noexcept;
__attribute__((visibility("hidden"))) bool begin_activation() noexcept;
__attribute__((visibility("hidden"))) void end_activation(bool owner) noexcept;
__attribute__((visibility("hidden"))) void poison_guard() noexcept;
__attribute__((visibility("hidden"))) void thread_end() noexcept;
__attribute__((visibility("hidden"))) void publish_capture(uint32_t, const jvmtiFrameInfo*, jint, bool) noexcept;
__attribute__((visibility("hidden"))) bool vm_init(JNIEnv*, jvmtiEnv*) noexcept;
__attribute__((visibility("hidden"))) int startup_variant() noexcept;
__attribute__((visibility("hidden"))) void* original(uint32_t binding) noexcept;
class __attribute__((visibility("hidden"))) BindingUpdate final {
public:
    BindingUpdate(JNIEnv*, jvmtiEnv*, jmethodID, jclass, uint32_t) noexcept;
    ~BindingUpdate();
    BindingUpdate(const BindingUpdate&) = delete;
    BindingUpdate& operator=(const BindingUpdate&) = delete;
};
__attribute__((visibility("hidden"))) void observed_replacement(JNIEnv*, jclass) noexcept;
__attribute__((visibility("hidden"))) void observe_helper_binding(JNIEnv*, jclass, jmethodID) noexcept;
#ifdef MAZEWALL_LIVE_JNI_TEST
__attribute__((visibility("hidden"))) void* substituted_original(uint32_t) noexcept;
__attribute__((visibility("hidden"))) void test_observe_original() noexcept;
__attribute__((visibility("hidden"))) int test_invoke_open(JNIEnv*, jclass, const char*) noexcept;
__attribute__((visibility("hidden"))) int test_invoke_openat(JNIEnv*, jclass, const char*) noexcept;
__attribute__((visibility("hidden"))) uint64_t test_acquisition_calls() noexcept;
#endif
}
