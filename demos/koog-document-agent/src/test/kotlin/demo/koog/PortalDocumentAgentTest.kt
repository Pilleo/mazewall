package demo.koog

import demo.documents.MarkdownOutline
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PortalDocumentAgentTest {
    private val service = MarkdownOutline()

    @Test fun `fenced code is not an outline heading or a heading limit violation`() {
        assertEquals("Real\nAfter", service.outline("# Real\n````kotlin\n# hidden\n```\n## still code\n````\n~~~\n# hidden too\n~~~\n## After"))
        assertEquals("Real", service.outline("# Real\n```\n" + List(129) { "# code comment" }.joinToString("\n")))
    }

    @Test fun `outline is useful data and preserves Unicode without rendering`() {
        assertEquals("Launch é🙂\nNext\n(untitled)", service.outline("# Launch é🙂\nbody\n  ## Next ###\n#\n####### no\n    # code"))
        assertEquals("", service.outline("ordinary text\n#not-a-heading"))
    }

    @Test fun `outline enforces input byte Unicode output and heading bounds`() {
        assertEquals("", service.outline("x".repeat(65_536)))
        assertFailsWith<IllegalArgumentException> { service.outline("x".repeat(65_537)) }
        assertFailsWith<IllegalArgumentException> { service.outline("é".repeat(32_769)) }
        assertFailsWith<IllegalArgumentException> { service.outline("\uD800") }
        assertEquals("x".repeat(16_384), service.outline("# " + "x".repeat(16_384)))
        assertFailsWith<IllegalArgumentException> { service.outline("# " + "x".repeat(16_385)) }
        assertEquals(List(128) { "h" }.joinToString("\n"), service.outline(List(128) { "# h" }.joinToString("\n")))
        assertFailsWith<IllegalArgumentException> { service.outline(List(129) { "# h" }.joinToString("\n")) }
    }
}
