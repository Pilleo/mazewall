package demo

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DemoAppTest {

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `main with no arguments runs both demos and succeeds`() {
        val unsafeMarker = File("/tmp/pwned_unsafe")
        val safeMarker = File("/tmp/pwned_safe")
        unsafeMarker.delete()
        safeMarker.delete()

        // Running main() without arguments should trigger the "both" mode
        main(emptyArray())

        // Wait a bit for the async unsafe exploit to finish (it uses Runtime.exec)
        var attempts = 0
        while (!unsafeMarker.exists() && attempts < 50) {
            Thread.sleep(100)
            attempts++
        }

        assertTrue(unsafeMarker.exists(), "Unsafe demo should have created the marker")
        assertFalse(safeMarker.exists(), "Safe demo should NOT have created the marker")

        // Cleanup
        unsafeMarker.delete()
        safeMarker.delete()
    }
}
