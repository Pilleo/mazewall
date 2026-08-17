package io.mazewall

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NeedsFreshJvmTest {
    @Test
    fun `tag matches the Gradle includeTags filter`() {
        assertEquals("needs-fresh-jvm", NeedsFreshJvm.TAG)
    }
}
