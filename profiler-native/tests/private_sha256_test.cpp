#include "private_sha256.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <limits>
#include <string>
#include <string_view>
#include <vector>

using mazewall::profiler::integrity::Sha256;

static void require(bool value) {
    if (!value) { std::cerr << "SHA-256 vector or state failure\n"; std::exit(1); }
}

static std::string hex(const std::array<std::uint8_t, 32>& digest) {
    constexpr char alphabet[] = "0123456789abcdef";
    std::string result;
    for (const auto byte : digest) {
        result.push_back(alphabet[byte >> 4]);
        result.push_back(alphabet[byte & 15]);
    }
    return result;
}

static void check(std::string_view input, std::string_view expected) {
    Sha256 sha;
    require(sha.update(reinterpret_cast<const std::uint8_t*>(input.data()), input.size()));
    std::array<std::uint8_t, 32> digest{};
    require(sha.finish(digest));
    require(hex(digest) == expected);
    auto unchanged = digest;
    require(!sha.finish(digest));
    require(digest == unchanged);
    require(!sha.update(reinterpret_cast<const std::uint8_t*>(input.data()), input.size()));
}

int main(int argc, char** argv) {
    require(argc == 2);
    check("", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    check("abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    check("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
          "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
    check(std::string(1000000, 'a'), "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
    Sha256 first, second;
    std::uint8_t byte = 0;
    require(first.update(nullptr, 0));
    require(!first.update(nullptr, 1));
    require(!first.update(&byte, std::numeric_limits<std::size_t>::max()));
    std::ifstream vectors(argv[1]);
    require(vectors.good());
    std::size_t length = 0, row = 0;
    std::string expected;
    while (vectors >> length >> expected) {
        require(length == row && length <= 1024);
        std::vector<std::uint8_t> input(length + 1);
        for (std::size_t i = 0; i < length; ++i) input[i + 1] = static_cast<std::uint8_t>(i);
        Sha256 streamed;
        for (std::size_t offset = 0; offset < length;) {
            const auto chunk = std::min<std::size_t>(55, length - offset);
            require(streamed.update(input.data() + 1 + offset, chunk));
            offset += chunk;
        }
        std::array<std::uint8_t, 32> digest{};
        require(streamed.finish(digest));
        require(hex(digest) == expected);
        ++row;
    }
    require(vectors.eof() && row == 1025);
    std::array<std::uint8_t, 32> a{}, b{};
    require(first.finish(a) && second.finish(b) && a == b);
}
