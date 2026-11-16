#include "live_java_observation.hpp"

#include <cstdint>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace mazewall::profiler::integrity {
namespace {
bool decode(JNIEnv* env, jstring value, std::string& out) {
    if (!value || env->ExceptionCheck()) return false;
    const auto length = env->GetStringLength(value);
    if (env->ExceptionCheck() || length < 0 || length > 4096) return false;
    const auto* chars = env->GetStringChars(value, nullptr);
    if (!chars) return false;
    bool valid = true;
    std::string result;
    try {
        for (jsize i = 0; i < length && valid; ++i) {
            std::uint32_t cp = chars[i];
            if (cp >= 0xd800 && cp <= 0xdbff) {
                if (++i >= length || chars[i] < 0xdc00 || chars[i] > 0xdfff) { valid = false; break; }
                cp = 0x10000 + ((cp - 0xd800) << 10) + (chars[i] - 0xdc00);
            } else if (cp >= 0xdc00 && cp <= 0xdfff) { valid = false; break; }
            if (cp == 0) { valid = false; break; }
            if (cp < 0x80) result.push_back(static_cast<char>(cp));
            else if (cp < 0x800) {
                result.push_back(static_cast<char>(0xc0 | (cp >> 6)));
                result.push_back(static_cast<char>(0x80 | (cp & 63)));
            } else if (cp < 0x10000) {
                result.push_back(static_cast<char>(0xe0 | (cp >> 12)));
                result.push_back(static_cast<char>(0x80 | ((cp >> 6) & 63)));
                result.push_back(static_cast<char>(0x80 | (cp & 63)));
            } else {
                result.push_back(static_cast<char>(0xf0 | (cp >> 18)));
                result.push_back(static_cast<char>(0x80 | ((cp >> 12) & 63)));
                result.push_back(static_cast<char>(0x80 | ((cp >> 6) & 63)));
                result.push_back(static_cast<char>(0x80 | (cp & 63)));
            }
            if (result.size() > 4096) valid = false;
        }
    } catch (...) {
        env->ReleaseStringChars(value, chars);
        throw;
    }
    env->ReleaseStringChars(value, chars);
    if (!valid || env->ExceptionCheck()) return false;
    out = std::move(result);
    return true;
}
}
bool compare_java_observation(JNIEnv* env, jobjectArray input, const Manifest& manifest) {
    if (!env || !input || env->ExceptionCheck()) return false;
    const auto length = env->GetArrayLength(input);
    if (env->ExceptionCheck() || length < 0 || length > 2048 || manifest.artifacts.size() != 30
            || manifest.witnesses.size() != 12 || manifest.jvm_args.size() > 128) return false;
    std::vector<std::string> expected{"mazewall-observed-java/1", "CLOSED_V1", "ONLY"};
    expected.insert(expected.end(), manifest.runtime.begin(), manifest.runtime.end());
    const auto& java_path = manifest.artifacts[static_cast<std::size_t>(ArtifactRole::Java)].path;
    constexpr std::string_view suffix = "/bin/java";
    if (!java_path.ends_with(suffix) || java_path.size() <= suffix.size()) return false;
    const auto java_home = java_path.substr(0, java_path.size() - suffix.size());
    for (const auto& [role, relative] : {std::pair{ArtifactRole::Release, "/release"},
            std::pair{ArtifactRole::Modules, "/lib/modules"}, std::pair{ArtifactRole::Libjvm, "/lib/server/libjvm.so"},
            std::pair{ArtifactRole::Libjava, "/lib/libjava.so"}, std::pair{ArtifactRole::Libnio, "/lib/libnio.so"}}) {
        if (manifest.artifacts[static_cast<std::size_t>(role)].path != java_home + relative) return false;
    }
    expected.push_back(java_home);
    expected.push_back(std::to_string(manifest.jvm_args.size()));
    for (std::size_t i = 0; i < manifest.jvm_args.size(); ++i) {
        expected.push_back(std::to_string(i)); expected.push_back(manifest.jvm_args[i]);
    }
    expected.push_back("7");
    for (std::size_t i = 0; i < 7; ++i) {
        const auto& artifact = manifest.artifacts[i + 23];
        expected.push_back(std::to_string(i)); expected.push_back(artifact.path); expected.push_back(artifact.sha256);
    }
    expected.push_back(std::to_string(manifest.witnesses.size()));
    for (std::size_t i = 0; i < manifest.witnesses.size(); ++i) {
        const auto& witness = manifest.witnesses[i];
        const auto role = static_cast<std::size_t>(witness.role);
        if (role >= manifest.artifacts.size() || manifest.artifacts[role].role != witness.role) return false;
        expected.push_back(std::to_string(i)); expected.push_back(witness.name); expected.push_back("system");
        expected.push_back("unnamed"); expected.push_back(manifest.artifacts[role].path);
    }
    if (expected.size() != static_cast<std::size_t>(length)) return false;
    std::size_t total = 0;
    for (jsize i = 0; i < length; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(input, i));
        std::string decoded;
        bool ok = false;
        try { ok = decode(env, value, decoded); }
        catch (...) { if (value) env->DeleteLocalRef(value); throw; }
        if (value) env->DeleteLocalRef(value);
        if (!ok || decoded != expected[static_cast<std::size_t>(i)] || decoded.size() > 262144 - total) return false;
        total += decoded.size();
    }
    return true;
}
}
