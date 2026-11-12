#include "live_startup_binding.hpp"

#include <cstdlib>
#include <iostream>
#include <fstream>
#include <iterator>
#include <string>
#include <utility>

using mazewall::profiler::integrity::parse_binding;
using mazewall::profiler::integrity::Binding;
using mazewall::profiler::integrity::Manifest;
using mazewall::profiler::integrity::parse_manifest;

static void require(bool value) {
    if (!value) { std::cerr << "startup binding parser failure\n"; std::exit(1); }
}

int main(int argc, char** argv) {
    require(argc == 2);
    require(!mazewall::profiler::integrity::artifact_size_allowed(-1));
    require(mazewall::profiler::integrity::artifact_size_allowed(0));
    require(mazewall::profiler::integrity::artifact_size_allowed(std::int64_t{1} << 30));
    require(!mazewall::profiler::integrity::artifact_size_allowed((std::int64_t{1} << 30) + 1));
    mazewall::profiler::integrity::StartupSnapshot empty_snapshot;
    const auto capture = mazewall::profiler::integrity::capture_startup(empty_snapshot);
    require(capture.disposition == mazewall::profiler::integrity::CaptureDisposition::Absent);
    const std::string hash(64, 'a');
    const std::string valid = "MAZEWALL-BINDING\t1\nMANIFEST\tL2V0Yy9tYXpld2FsbC9saXZlL2Rpc3RyaWJ1dGlvbi52MQ\t" + hash + "\nEND\n";
    Binding binding;
    require(parse_binding(valid, binding));
    require(binding.manifest_sha256 == hash);
    for (const auto& invalid : {std::string{}, valid + "x", valid.substr(0, valid.size() - 1),
            std::string("MAZEWALL-BINDING\t2\n") + valid.substr(valid.find('\n') + 1),
            std::string("MAZEWALL-BINDING\t1\nMANIFEST\tL2V0Yw\t") + hash + "\nEND\n",
            std::string("MAZEWALL-BINDING\t1\nMANIFEST\tL2V0Yy9tYXpld2FsbC9saXZlL2Rpc3RyaWJ1dGlvbi52MQ\t") + std::string(64, 'A') + "\nEND\n"}) {
        require(!parse_binding(invalid, binding));
    }
    require(!parse_binding(std::string(16385, 'a'), binding));
    std::ifstream file(argv[1]);
    require(file.good());
    const std::string valid_manifest{std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
    Manifest manifest;
    require(parse_manifest(valid_manifest, manifest));
    require(manifest.artifacts.size() == 30 && manifest.witnesses.size() == 12);
    require(manifest.conditional_images[0] && !manifest.conditional_images[1]);
    using mazewall::profiler::integrity::supports_protected_launch;
    require(supports_protected_launch(manifest));
    auto unsafe = manifest;
    unsafe.jvm_args.insert(unsafe.jvm_args.begin(), "-javaagent:/tmp/extra.jar");
    unsafe.process_args.insert(unsafe.process_args.begin() + 1, "-javaagent:/tmp/extra.jar");
    require(!supports_protected_launch(unsafe));
    unsafe = manifest; unsafe.jvm_args[0] = "-Xlog:class+load=info:file=/tmp/log:extra";
    unsafe.process_args[1] = unsafe.jvm_args[0]; require(!supports_protected_launch(unsafe));
    unsafe = manifest; unsafe.process_args.back() = "@argument-file"; require(!supports_protected_launch(unsafe));
    unsafe = manifest; unsafe.process_args.resize(128, "argument"); require(supports_protected_launch(unsafe));
    unsafe.process_args.push_back("argument"); require(!supports_protected_launch(unsafe));
    unsafe = manifest; unsafe.artifacts[23].path += ":/other"; require(!supports_protected_launch(unsafe));
    unsafe = manifest; unsafe.artifacts[23].path += "/*"; require(!supports_protected_launch(unsafe));
    unsafe = manifest; unsafe.process_args[10] = "-classpath"; require(!supports_protected_launch(unsafe));
    unsafe = manifest; unsafe.process_args[12] = "bad..Main"; require(!supports_protected_launch(unsafe));
    unsafe = manifest; unsafe.artifacts[0].path += "=suffix";
    unsafe.jvm_args[8] = "-agentpath:" + unsafe.artifacts[0].path + "=live-stack=only";
    unsafe.process_args[9] = unsafe.jvm_args[8]; require(!supports_protected_launch(unsafe));
    require(!parse_manifest(valid_manifest + "x", manifest));
    require(manifest.artifacts.size() == 30);
    for (const auto& edit : {std::pair{"PROFILE\tCLOSED_V1", "PROFILE\tOPEN"},
            std::pair{"ROUTE\t1\t257", "ROUTE\t1\t2"},
            std::pair{"CONDITIONAL\t0\tlibzip\tpresent", "CONDITIONAL\t0\tlibzip\tunknown"},
            std::pair{"BINDING\t0\t1\tlibnio\t0xe950", "BINDING\t0\t2\tlibnio\t0xe950"},
            std::pair{"DEPENDENCY\t0\tlibnio\t0x15ee0", "DEPENDENCY\t0\tlibnio\t0x15ee1"},
            std::pair{"CLASSPATH\t0\thelper", "CLASSPATH\t1\thelper"},
            std::pair{"SOURCE\tdGVzdC1zb3VyY2U", "SOURCE\tdGVzdC1zb3VyY2U="}}) {
        auto altered = valid_manifest;
        const auto offset = altered.find(edit.first);
        require(offset != std::string::npos);
        altered.replace(offset, std::string_view(edit.first).size(), edit.second);
        require(!parse_manifest(altered, manifest));
        require(manifest.artifacts.size() == 30);
    }
    require(!parse_manifest("", manifest));
    require(!parse_manifest("MAZEWALL-MANIFEST\t1\nPROFILE\tCLOSED_V1\nVARIANT\tONLY\nEND\n", manifest));
    require(!parse_manifest(std::string(262145, 'x'), manifest));
}
