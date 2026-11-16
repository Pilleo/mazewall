#include "private_sha256.hpp"

#include <algorithm>
#include <cstring>
#include <limits>

namespace mazewall::profiler::integrity {
namespace {
constexpr std::array<std::uint32_t, 64> k{
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
constexpr std::uint32_t rotate(std::uint32_t x, unsigned n) noexcept { return (x >> n) | (x << (32 - n)); }
}
void Sha256::block(const std::uint8_t* data) noexcept {
    std::array<std::uint32_t, 64> w{};
    for (std::size_t i = 0; i < 16; ++i) {
        w[i] = (std::uint32_t(data[4*i]) << 24) | (std::uint32_t(data[4*i+1]) << 16)
            | (std::uint32_t(data[4*i+2]) << 8) | std::uint32_t(data[4*i+3]);
    }
    for (std::size_t i = 16; i < 64; ++i) {
        const auto a = rotate(w[i-15], 7) ^ rotate(w[i-15], 18) ^ (w[i-15] >> 3);
        const auto b = rotate(w[i-2], 17) ^ rotate(w[i-2], 19) ^ (w[i-2] >> 10);
        w[i] = w[i-16] + a + w[i-7] + b;
    }
    auto a=state_[0], b=state_[1], c=state_[2], d=state_[3];
    auto e=state_[4], f=state_[5], g=state_[6], h=state_[7];
    for (std::size_t i=0; i<64; ++i) {
        const auto t1=h+(rotate(e,6)^rotate(e,11)^rotate(e,25))+((e&f)^(~e&g))+k[i]+w[i];
        const auto t2=(rotate(a,2)^rotate(a,13)^rotate(a,22))+((a&b)^(a&c)^(b&c));
        h=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
    }
    state_[0]+=a; state_[1]+=b; state_[2]+=c; state_[3]+=d;
    state_[4]+=e; state_[5]+=f; state_[6]+=g; state_[7]+=h;
}
bool Sha256::update(const std::uint8_t* data, std::size_t size) noexcept {
    if (finished_ || (!data && size != 0) || size > (std::numeric_limits<std::uint64_t>::max()/8 - bytes_)) return false;
    bytes_ += size;
    while (size != 0) {
        const auto n = std::min(size, buffer_.size()-used_);
        std::memcpy(buffer_.data()+used_, data, n);
        used_ += n; data += n; size -= n;
        if (used_ == buffer_.size()) { block(buffer_.data()); used_ = 0; }
    }
    return true;
}
bool Sha256::finish(std::array<std::uint8_t, 32>& digest) noexcept {
    if (finished_) return false;
    finished_ = true;
    const auto bits = bytes_ * 8;
    buffer_[used_++] = 0x80;
    if (used_ > 56) { std::fill(buffer_.begin()+used_, buffer_.end(), 0); block(buffer_.data()); used_=0; }
    std::fill(buffer_.begin()+used_, buffer_.begin()+56, 0);
    for (unsigned i=0; i<8; ++i) buffer_[63-i] = static_cast<std::uint8_t>(bits >> (8*i));
    block(buffer_.data());
    for (std::size_t i=0; i<state_.size(); ++i) {
        for (unsigned j=0; j<4; ++j) digest[4*i+j] = static_cast<std::uint8_t>(state_[i] >> (24-8*j));
    }
    return true;
}
}
