package io.contained

data class Arch(
    val name: String,
    val audit: Int,
    val limit: Int,
    val fork: Int,
    val vfork: Int,
    val clone: Int,
    val clone3: Int,
    val execve: Int,
    val execveat: Int,
    val connect: Int,
    val bind: Int,
    val listen: Int,
    val accept: Int,
    val accept4: Int,
    val sendto: Int,
    val sendmsg: Int,
    val openat: Int,
    val open: Int,
    val mmap: Int,
    val ptrace: Int,
    val socket: Int,
    val initModule: Int,
    val finitModule: Int,
    val memfdCreate: Int,
    val seccompSyscallNumber: Int
) {
    companion object {
        const val AUDIT_ARCH_X86_64 = 0xC000003E.toInt()
        const val AUDIT_ARCH_AARCH64 = 0xC00000B7.toInt()

        val AMD64 = Arch(
            name = "amd64",
            audit = AUDIT_ARCH_X86_64,
            limit = 547,
            fork = 57,
            vfork = 58,
            clone = 56,
            clone3 = 435,
            execve = 59,
            execveat = 322,
            connect = 42,
            bind = 49,
            listen = 50,
            accept = 43,
            accept4 = 288,
            sendto = 44,
            sendmsg = 46,
            openat = 257,
            open = 2,
            mmap = 9,
            ptrace = 101,
            socket = 41,
            initModule = 175,
            finitModule = 313,
            memfdCreate = 319,
            seccompSyscallNumber = 317
        )

        val AARCH64 = Arch(
            name = "aarch64",
            audit = AUDIT_ARCH_AARCH64,
            limit = 436,
            fork = -1,
            vfork = -1,
            clone = 220,
            clone3 = 435,
            execve = 221,
            execveat = 281,
            connect = 203,
            bind = 200,
            listen = 201,
            accept = 202,
            accept4 = 242,
            sendto = 206,
            sendmsg = 211,
            openat = 56,
            open = -1,
            mmap = 222,
            ptrace = 117,
            socket = 198,
            initModule = 105,
            finitModule = 273,
            memfdCreate = 279,
            seccompSyscallNumber = 277
        )

        fun current(): Arch {
            return when (val osArch = System.getProperty("os.arch")) {
                "amd64", "x86_64" -> AMD64
                "aarch64", "arm64" -> AARCH64
                else -> throw UnsupportedOperationException("Architecture not supported: $osArch")
            }
        }
    }
}
