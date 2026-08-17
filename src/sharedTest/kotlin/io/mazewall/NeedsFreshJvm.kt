package io.mazewall

import org.junit.jupiter.api.Tag
import java.lang.annotation.Inherited

/**
 * This test installs seccomp/USER_NOTIF on the JUnit worker JVM (process-wide,
 * current-thread, or a shared daemon). Gradle runs these with [Test.forkEvery] = 1.
 *
 * Tests that only [ContainedExecutors.wrap] a dedicated pool, launch
 * [IsolatedProcessTester], or spawn their own ProcessBuilder should stay untagged.
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
