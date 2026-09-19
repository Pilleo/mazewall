#pragma once

#include <array>
#include <cstddef>
#include <string_view>

namespace mazewall::profiler {

enum class BindingKind {
    unsupported,
    unix_open0,
    unix_openat0,
    unix_close0,
    unix_unlink0,
    file_read0,
    file_write0,
    file_close_int_fd,
    socket_read0,
    socket_write0,
    net_socket0,
    net_connect0,
    net_accept,
};

/**
 * Exact HotSpot JDK binding manifest. A mismatch is intentionally unsupported: native method
 * proxies have ABI-specific signatures, and redirecting a lookalike method is unsafe.
 */
class BindingManifest final {
public:
    static constexpr std::array<BindingKind, 12> supported_bindings{
        BindingKind::unix_open0,
        BindingKind::unix_openat0,
        BindingKind::unix_close0,
        BindingKind::unix_unlink0,
        BindingKind::file_read0,
        BindingKind::file_write0,
        BindingKind::file_close_int_fd,
        BindingKind::socket_read0,
        BindingKind::socket_write0,
        BindingKind::net_socket0,
        BindingKind::net_connect0,
        BindingKind::net_accept,
    };

    [[nodiscard]] static constexpr std::string_view name(BindingKind kind) noexcept {
        switch (kind) {
        case BindingKind::unix_open0: return "unix-open0";
        case BindingKind::unix_openat0: return "unix-openat0";
        case BindingKind::unix_close0: return "unix-close0";
        case BindingKind::unix_unlink0: return "unix-unlink0";
        case BindingKind::file_read0: return "file-read0";
        case BindingKind::file_write0: return "file-write0";
        case BindingKind::file_close_int_fd: return "file-close-int-fd";
        case BindingKind::socket_read0: return "socket-read0";
        case BindingKind::socket_write0: return "socket-write0";
        case BindingKind::net_socket0: return "net-socket0";
        case BindingKind::net_connect0: return "net-connect0";
        case BindingKind::net_accept: return "net-accept";
        case BindingKind::unsupported: return "";
        }
        return "";
    }

    [[nodiscard]] static constexpr BindingKind from_name(std::string_view value) noexcept {
        for (const auto kind : supported_bindings) {
            if (name(kind) == value) return kind;
        }
        return BindingKind::unsupported;
    }

    [[nodiscard]] static constexpr std::size_t index(BindingKind kind) noexcept {
        return kind == BindingKind::unsupported ? supported_bindings.size() : static_cast<std::size_t>(kind) - 1;
    }

    [[nodiscard]] static BindingKind classify(
        std::string_view owner,
        std::string_view name,
        std::string_view descriptor) noexcept {
        if (owner == "Lsun/nio/fs/UnixNativeDispatcher;") {
            if (name == "open0" && descriptor == "(JII)I") return BindingKind::unix_open0;
            if (name == "openat0" && descriptor == "(IJII)I") return BindingKind::unix_openat0;
            if (name == "close0" && descriptor == "(I)V") return BindingKind::unix_close0;
            if (name == "unlink0" && descriptor == "(J)V") return BindingKind::unix_unlink0;
        }
        if (owner == "Lsun/nio/ch/UnixFileDispatcherImpl;") {
            if (name == "read0" && descriptor == "(Ljava/io/FileDescriptor;JI)I") return BindingKind::file_read0;
            if (name == "write0" && descriptor == "(Ljava/io/FileDescriptor;JI)I") return BindingKind::file_write0;
            if (name == "closeIntFD" && descriptor == "(I)V") return BindingKind::file_close_int_fd;
        }
        if (owner == "Lsun/nio/ch/SocketDispatcher;") {
            if (name == "read0" && descriptor == "(Ljava/io/FileDescriptor;JI)I") return BindingKind::socket_read0;
            if (name == "write0" && descriptor == "(Ljava/io/FileDescriptor;JI)I") return BindingKind::socket_write0;
        }
        if (owner == "Lsun/nio/ch/Net;") {
            if (name == "socket0" && descriptor == "(ZZZZ)I") return BindingKind::net_socket0;
            if (name == "connect0" && descriptor == "(ZLjava/io/FileDescriptor;Ljava/net/InetAddress;I)I") return BindingKind::net_connect0;
            if (name == "accept" && descriptor == "(Ljava/io/FileDescriptor;Ljava/io/FileDescriptor;[Ljava/net/InetSocketAddress;)I") return BindingKind::net_accept;
        }
        return BindingKind::unsupported;
    }
};

} // namespace mazewall::profiler
