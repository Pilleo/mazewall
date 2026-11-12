#include "live_java_observation.hpp"

#include <cstdlib>
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <vector>

using namespace mazewall::profiler::integrity;
static void require(bool value) { if (!value) { std::cerr << "Java observation test failed\n"; std::exit(1); } }
static std::vector<std::string> observation(const Manifest& m) {
    std::vector<std::string> v{"mazewall-observed-java/1", "CLOSED_V1", "ONLY"};
    v.insert(v.end(), m.runtime.begin(), m.runtime.end());
    v.push_back("/fixture/jdk");
    v.push_back(std::to_string(m.jvm_args.size()));
    for (std::size_t i = 0; i < m.jvm_args.size(); ++i) { v.push_back(std::to_string(i)); v.push_back(m.jvm_args[i]); }
    v.push_back("7");
    for (std::size_t i = 0; i < 7; ++i) {
        const auto& a = m.artifacts[i+23]; v.push_back(std::to_string(i)); v.push_back(a.path); v.push_back(a.sha256);
    }
    v.push_back(std::to_string(m.witnesses.size()));
    for (std::size_t i = 0; i < m.witnesses.size(); ++i) {
        const auto& w = m.witnesses[i]; v.push_back(std::to_string(i)); v.push_back(w.name);
        v.push_back("system"); v.push_back("unnamed"); v.push_back(m.artifacts[static_cast<std::size_t>(w.role)].path);
    }
    return v;
}
int main(int argc, char** argv) {
    require(argc == 2);
    std::ifstream file(argv[1]); require(file.good());
    const std::string bytes{std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
    Manifest manifest; require(parse_manifest(bytes, manifest));
    JavaVMInitArgs options{}; options.version = JNI_VERSION_1_8; options.ignoreUnrecognized = JNI_FALSE;
    JavaVM* vm = nullptr; JNIEnv* env = nullptr;
    require(JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &options) == JNI_OK);
    const auto values = observation(manifest);
    auto string_class = env->FindClass("java/lang/String"); require(string_class != nullptr);
    auto array = env->NewObjectArray(static_cast<jsize>(values.size()), string_class, nullptr); require(array != nullptr);
    for (std::size_t i = 0; i < values.size(); ++i) {
        auto item = env->NewStringUTF(values[i].c_str()); require(item != nullptr);
        env->SetObjectArrayElement(array, static_cast<jsize>(i), item); env->DeleteLocalRef(item);
    }
    require(!env->ExceptionCheck() && compare_java_observation(env, array, manifest));
    auto mismatch = env->NewStringUTF("wrong"); env->SetObjectArrayElement(array, 3, mismatch); env->DeleteLocalRef(mismatch);
    require(!compare_java_observation(env, array, manifest));
    const jchar high_surrogate = 0xd800;
    auto malformed = env->NewString(&high_surrogate, 1); env->SetObjectArrayElement(array, 3, malformed); env->DeleteLocalRef(malformed);
    require(!compare_java_observation(env, array, manifest));
    env->SetObjectArrayElement(array, 3, nullptr);
    require(!compare_java_observation(env, array, manifest));
    env->DeleteLocalRef(array); env->DeleteLocalRef(string_class);
    require(vm->DestroyJavaVM() == JNI_OK);
}
