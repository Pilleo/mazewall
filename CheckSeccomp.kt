import io.contained.*

fun main() {
    val getSeccomp = LinuxNative.prctl(LinuxNative.PR_GET_SECCOMP, 0, 0, 0, 0)
    println("PR_GET_SECCOMP ret=${getSeccomp.returnValue} errno=${getSeccomp.errno}")
}
