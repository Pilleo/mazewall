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
    fun `grok and antigravity adapters are refused for loop work unless unlocked`() {
        assertEquals(
            setOf("antigravity", "grok_local"),
            HybridSupervisor.FORBIDDEN_EXPERIMENT_ADAPTERS,
        )
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
    fun `jules is the primary worker - no component routes by default`() {
        // Operator directive 2026-08-25: every component falls back to the
        // default adapter unless explicitly overridden via env.
        for (component in listOf("enforcer", "portal", "profiler", "platform", "docs", "nonexistent")) {
            assertNull(router.urlKeyFor(component), component)
        }
    }

    @Test
    fun `plan-md table re-enables per component via overrides`() {
        val optIn = ComponentRouter(customRoutes = ComponentRouter.parseOverrides("enforcer=antigravity-acp-developer,docs=founding-systems-security-engineer"))
        assertEquals("antigravity-acp-developer", optIn.urlKeyFor("enforcer"))
        assertEquals("founding-systems-security-engineer", optIn.urlKeyFor("docs"))
        assertEquals(null, optIn.urlKeyFor("profiler"), "unlisted components stay on Jules")
    }

    @Test
    fun `matching is case-insensitive and trimmed`() {
        val optIn = ComponentRouter(customRoutes = mapOf("kernel" to "antigravity-acp-developer"))
        assertEquals("antigravity-acp-developer", optIn.urlKeyFor(" KERNEL "))
    }

    @Test
    fun `custom overrides resolve independently of the now-empty default table`() {
        val custom = ComponentRouter(customRoutes = mapOf("tools" to "vibe-acp-developer"))
        assertEquals("vibe-acp-developer", custom.urlKeyFor("tools"))
        assertNull(custom.urlKeyFor("enforcer"), "no implicit table entries anymore")
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
