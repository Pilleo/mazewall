#include "binding_manifest.hpp"

int main() {
    using mazewall::profiler::BindingKind;
    using mazewall::profiler::BindingManifest;

    if (BindingManifest::classify("Lsun/nio/fs/UnixNativeDispatcher;", "open0", "(JII)I") != BindingKind::unix_open0) return 1;
    if (BindingManifest::classify("Lsun/nio/fs/UnixNativeDispatcher;", "open0", "(JII)V") != BindingKind::unsupported) return 2;
    if (BindingManifest::classify("Lsun/nio/ch/UnixFileDispatcherImpl;", "read0", "(Ljava/io/FileDescriptor;JI)I") != BindingKind::file_read0) return 3;
    if (BindingManifest::classify("Lsun/nio/ch/SocketDispatcher;", "write0", "(Ljava/io/FileDescriptor;JI)I") != BindingKind::socket_write0) return 4;
    if (BindingManifest::classify("Lsun/nio/fs/UnixNativeDispatcher;", "unlink0", "(J)V") != BindingKind::unix_unlink0) return 5;
}
