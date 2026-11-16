package io.mazewall.platform.native

internal class LegacyNativeEngine : NativeEngine {
    override val fileSystem: NativeFileSystem get() = throw AssertionError("fileSystem")
    override val networking: NativeNetworking get() = throw AssertionError("networking")
    override val process: NativeProcess get() = throw AssertionError("process")
    override val memory: NativeMemory get() = throw AssertionError("memory")
    override val raw: RawSyscallOperations get() = throw AssertionError("raw")
}
