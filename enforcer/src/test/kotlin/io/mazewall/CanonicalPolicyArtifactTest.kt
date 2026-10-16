package io.mazewall.enforcer.api

import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CanonicalPolicyArtifactTest {
    private val denyAll = """
        {"formatVersion":1,"scope":"WORKER","runtime":"HOTSPOT_JIT",
        "defaultAction":{"kind":"ALLOW"},
        "syscalls":[{"name":"CONNECT","action":{"kind":"ERRNO","data":13}}],
        "arguments":{"executableMappings":true,"nonThreadClone":false,"unsafePrctl":false,"cetLock":false},
        "filesystem":{"kind":"DENY_ALL","read":[],"write":[]},
        "diagnostics":{"phrases":[],"regexes":[]}}
    """.trimIndent()

    @Test
    fun `explicit deny all survives canonical policy round trip`() {
        val artifact = assertIs<CanonicalPolicyArtifact.Worker>(CanonicalPolicyArtifact.fromJson(denyAll))
        val roundTrip = assertIs<CanonicalPolicyArtifact.Worker>(CanonicalPolicyArtifact.fromJson(artifact.toJson()))
        assertTrue(roundTrip.policy.definition.enforceLandlock)
        assertTrue(roundTrip.policy.allowedFsReadPaths.isEmpty())
        assertEquals(SeccompAction.ACT_ERRNO(13), roundTrip.policy.syscallActions[Syscall.CONNECT])
        assertEquals(RuntimeProfile.HOTSPOT_JIT, roundTrip.runtime)
        assertTrue(roundTrip.policy.allowMmapExec)
        assertFalse(roundTrip.policy.allowNonThreadClone)
    }

    @Test
    fun `process artifact retains fixed scope and parameterized actions`() {
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .addAction(SeccompAction.ACT_TRACE(17), Syscall.CONNECT)
            .build()
        val artifact = CanonicalPolicyArtifact.process(RuntimeProfile.HOTSPOT_JIT, policy)
        val decoded = assertIs<CanonicalPolicyArtifact.Process>(CanonicalPolicyArtifact.fromJson(artifact.toJson()))
        assertEquals(SeccompAction.ACT_TRACE(17), decoded.policy.syscallActions[Syscall.CONNECT])
        assertFalse(decoded.policy.definition.enforceLandlock)
    }

    @Test
    fun `decoded permission collections cannot be mutated`() {
        val decoded = assertIs<CanonicalPolicyArtifact.Worker>(CanonicalPolicyArtifact.fromJson(denyAll))
        assertFailsWith<UnsupportedOperationException> {
            (decoded.policy.syscallActions as MutableMap<Syscall, SeccompAction>)[Syscall.CONNECT] = SeccompAction.ACT_ALLOW
        }
    }

    @Test
    fun `unrestricted and active empty policies remain different artifacts`() {
        val unrestricted = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()
        val restricted = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
        val first = CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, unrestricted)
        val second = CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, restricted)
        assertFalse(assertIs<CanonicalPolicyArtifact.Worker>(CanonicalPolicyArtifact.fromJson(first.toJson())).policy.definition.enforceLandlock)
        assertTrue(assertIs<CanonicalPolicyArtifact.Worker>(CanonicalPolicyArtifact.fromJson(second.toJson())).policy.definition.enforceLandlock)
    }

    @Test
    fun `unknown versions fields actions and contradictory filesystem intent reject`() {
        val invalid = listOf(
            denyAll.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            denyAll.replace("\"scope\":\"WORKER\"", "\"scope\":\"UNRESTRICTED\""),
            denyAll.replace("\"cetLock\":false", "\"cetLock\":false,\"allowEverything\":true"),
            denyAll.replace("\"kind\":\"ALLOW\"", "\"kind\":\"UNKNOWN\""),
            denyAll.replace("\"data\":13", "\"data\":-1"),
            denyAll.replace("\"read\":[]", "\"read\":[\"/private\"]"),
            denyAll.replace("\"scope\":\"WORKER\"", "\"scope\":\"PROCESS\""),
            denyAll.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
        )
        invalid.forEach { text -> assertFailsWith<IllegalArgumentException> { CanonicalPolicyArtifact.fromJson(text) } }
    }

    @Test
    fun `exact resource grants and diagnostic options round trip without permission widening`() {
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .allowFsRead("/tmp/parser.jar")
            .allowFsWrite("/tmp/output")
            .customViolationPhrase("custom marker")
            .customViolationRegex(Regex("secret.*", RegexOption.IGNORE_CASE))
            .build()
        val artifact = CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy)
        val decodedArtifact = assertIs<CanonicalPolicyArtifact.Worker>(CanonicalPolicyArtifact.fromJson(artifact.toJson()))
        val decoded = decodedArtifact.policy
        assertEquals(setOf("/tmp/parser.jar"), decoded.allowedFsReadPaths.map { it.value }.toSet())
        assertEquals(setOf("/tmp/output"), decoded.allowedFsWritePaths.map { it.value }.toSet())
        assertEquals(listOf("custom marker"), decoded.customViolationPhrases)
        assertEquals(setOf(RegexOption.IGNORE_CASE), decoded.customViolationRegexes.single().options)
    }
}
