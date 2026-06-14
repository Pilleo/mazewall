import io.mazewall.*
import io.mazewall.core.*

fun main() {
    val p1: Policy<PolicyScope.ProcessWideSafe, Uncompiled> = Policy.NO_EXEC
    val p2: Policy<PolicyScope.ProcessWideSafe, Uncompiled> = Policy.NO_NETWORK
    val p3: Policy<PolicyScope.ThreadLocalOnly, Uncompiled> = Policy.PURE_COMPUTE

    val c1: Policy<PolicyScope.ProcessWideSafe, Uncompiled> = p1 + p2
    val c2: Policy<PolicyScope.ThreadLocalOnly, Uncompiled> = p1 + p3
    val c3: Policy<PolicyScope.ThreadLocalOnly, Uncompiled> = p3 + p1
}
