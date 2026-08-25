package io.mazewall.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parity fixtures mirror the live board shapes verified 2026-08-24 and the exact
 * semantics of the bash implementation this Kotlin supervisor replaces:
 * marker-gated, unassigned, terminal blockers only; priority desc, number asc.
 */
class DispatchSelectorTest {

    private fun issue(
        id: String,
        identifier: String,
        status: String = "backlog",
        priority: String = "low",
        number: Int,
        assigned: String? = null,
        description: String? = "desc <!-- mazewall:backlog-file=docs/internals/backlog/x.md --> desc",
        blockers: List<PaperclipBlocker> = emptyList(),
    ) = PaperclipIssue(
        id = id,
        identifier = identifier,
        title = identifier,
        status = status,
        priority = priority,
        issueNumber = number,
        assigneeAgentId = assigned,
        description = description,
        blockedBy = blockers,
    )

    @Test
    fun `selects highest priority then lowest number`() {
        val chosen = DispatchSelector.select(
            listOf(
                issue("a", "MAZ-40", number = 40),
                issue("b", "MAZ-39", priority = "medium", number = 39),
                issue("c", "MAZ-38", priority = "high", number = 38),
            ),
        )
        assertEquals("MAZ-38", chosen?.identifier)
    }

    @Test
    fun `same priority breaks tie by lowest issueNumber`() {
        val chosen = DispatchSelector.select(
            listOf(
                issue("a", "MAZ-42", priority = "medium", number = 42),
                issue("b", "MAZ-41", priority = "medium", number = 41),
            ),
        )
        assertEquals("MAZ-41", chosen?.identifier)
    }

    @Test
    fun `ignores issues without the markdown provenance marker`() {
        val manual = issue(
            "m", "MAZ-26", number = 26,
            description = "manual board entry, no marker",
        )
        assertTrue(DispatchSelector.ordered(listOf(manual)).isEmpty())
    }

    @Test
    fun `ignores non-backlog statuses and already-assigned issues`() {
        val inProgress = issue("i", "MAZ-50", status = "in_progress", number = 50)
        val review = issue("r", "MAZ-51", status = "in_review", number = 51)
        val assigned = issue("s", "MAZ-52", number = 52, assigned = "agent-1")
        assertTrue(DispatchSelector.ordered(listOf(inProgress, review, assigned)).isEmpty())
    }

    @Test
    fun `open blockers gate dispatch`() {
        val gated = issue(
            "g", "MAZ-60", number = 60,
            blockers = listOf(PaperclipBlocker("b1", "in_review", "MAZ-59")),
        )
        assertFalse(DispatchSelector.isDispatchable(gated))
        val released = issue(
            "g2", "MAZ-61", number = 61,
            blockers = listOf(
                PaperclipBlocker("b1", "done", "MAZ-59"),
                PaperclipBlocker("b2", "cancelled", "MAZ-58"),
            ),
        )
        assertTrue(DispatchSelector.isDispatchable(released))
    }

    @Test
    fun `forcing restricts to identifier but keeps every safety gate`() {
        val issues = listOf(
            issue("a", "MAZ-80", priority = "high", number = 80),
            issue("b", "MAZ-81", number = 81), // low, marker present
        )
        assertEquals(
            "MAZ-81",
            DispatchSelector.select(issues, "MAZ-81")?.identifier,
        )
        assertEquals(null, DispatchSelector.select(issues, "MAZ-NONE")?.identifier)
        val gated = issue(
            "c", "MAZ-82", number = 82,
            blockers = listOf(PaperclipBlocker("b", "in_review", "MAZ-79")),
        )
        assertEquals(null, DispatchSelector.select(listOf(gated), "MAZ-82")?.identifier)
    }

    @Test
    fun `unknown priority sorts as lowest`() {
        val chosen = DispatchSelector.select(
            listOf(
                issue("x", "MAZ-70", priority = "urgent?", number = 70),
                issue("y", "MAZ-71", priority = "medium", number = 71),
            ),
        )
        assertEquals("MAZ-71", chosen?.identifier)
    }
}

class ComponentRouterTest {

    private val router = ComponentRouter()

    @Test
    fun `routes plan-md component table to roster urlKeys`() {
        assertEquals("antigravity-acp-developer", router.urlKeyFor("enforcer"))
        assertEquals("antigravity-acp-developer", router.urlKeyFor("portal"))
        assertEquals("implementation-software-developer-grok", router.urlKeyFor("profiler"))
        assertEquals("implementation-software-developer-grok", router.urlKeyFor("platform"))
        assertEquals("founding-systems-security-engineer", router.urlKeyFor("docs"))
        assertEquals("founding-systems-security-engineer", router.urlKeyFor("architecture"))
    }

    @Test
    fun `unknown or absent components return null for default-adapter fallback`() {
        assertNull(router.urlKeyFor("nonexistent"))
        assertNull(router.urlKeyFor(null))
        assertNull(router.urlKeyFor(""))
    }

    @Test
    fun `matching is case-insensitive and trimmed`() {
        assertEquals("antigravity-acp-developer", router.urlKeyFor(" ENFORCER "))
    }

    @Test
    fun `custom overrides merge over defaults`() {
        val custom = ComponentRouter(customRoutes = mapOf("tools" to "vibe-acp-developer"))
        assertEquals("vibe-acp-developer", custom.urlKeyFor("tools"))
        assertEquals("antigravity-acp-developer", custom.urlKeyFor("enforcer"))
    }

    @Test
    fun `override env parsing tolerates junk`() {
        val parsed = ComponentRouter.parseOverrides(" tools=vibe , bad-entry , enforcer=antigravity-acp-developer ")
        assertEquals(mapOf("tools" to "vibe", "enforcer" to "antigravity-acp-developer"), parsed)
        assertTrue(ComponentRouter.parseOverrides(null).isEmpty())
    }

    @Test
    fun `component extraction from ingested description`() {
        assertEquals("enforcer", router.componentOf("lead\n**Component:** enforcer\ntail"))
        assertNull(router.componentOf("no component here"))
        assertNull(router.componentOf(null))
    }
}
