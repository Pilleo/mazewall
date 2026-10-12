package io.mazewall.enforcer.api

import org.junit.jupiter.api.Tag
import java.lang.annotation.Inherited

/**
 * This test installs seccomp/USER_NOTIF on the JUnit worker JVM (process-wide,
 * current-thread, or a shared daemon). Gradle runs these with [Test.forkEvery] = 1.
 *
 * Tests that only install on acquired dedicated platform workers using
 * [ContainedExecutors.installOnCurrentThread], launch [IsolatedProcessTester], or spawn
 * their own ProcessBuilder should stay untagged. Owned-runtime tests that install a
 * process baseline on the JUnit worker need this tag unless they use a disposable child.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Inherited
@Tag(NeedsFreshJvm.TAG)
public annotation class NeedsFreshJvm {
    public companion object {
        public const val TAG: String = "needs-fresh-jvm"
    }
}
