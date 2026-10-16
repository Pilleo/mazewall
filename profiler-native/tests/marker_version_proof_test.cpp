#include "marker_version_proof.hpp"

#include <cstdlib>
#include <fstream>
#include <string>
#include <unistd.h>

int main() {
    std::string stat = "42 (java worker with spaces) S";
    for (int index = 1; index < 19; ++index) stat += " " + std::to_string(index);
    stat += " 9876 21";
    const auto start = mazewall::profiler::marker_proof::process_starttime(stat);
    if (!start || *start != 9876) return 1;
    if (mazewall::profiler::marker_proof::process_starttime("42 malformed")) return 2;
    const auto proof = mazewall::profiler::marker_proof::format(0x4d5a, *start, true);
    if (proof != "version=1\nsession_tag=4d5a\nprocess_starttime=9876\nmarker_version=v2\n") return 3;
    if (mazewall::profiler::marker_proof::format(0x4d5a, *start, false).find("marker_version=v1\n") == std::string::npos) return 4;
    char directory[] = "/tmp/mazewall-marker-proof-XXXXXX";
    if (::mkdtemp(directory) == nullptr) return 5;
    const auto file = std::string(directory) + "/marker.proof";
    if (!mazewall::profiler::marker_proof::write_atomic(file, proof)) return 6;
    std::ifstream input(file);
    const auto bytes = std::string(std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>());
    if (bytes != proof || mazewall::profiler::marker_proof::write_atomic("relative.proof", proof)) return 7;
    (void)::unlink(file.c_str());
    (void)::rmdir(directory);
}
