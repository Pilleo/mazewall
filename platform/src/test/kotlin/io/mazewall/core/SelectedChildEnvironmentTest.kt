package io.mazewall.platform.process

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SelectedChildEnvironmentTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `operator prepares only known synthetic markers while inherited deployment marker survives`() {
        val source = directory.resolve("EnvironmentProbe.java")
        Files.writeString(
            source,
            """
            import io.mazewall.platform.process.RealProcessLauncher;
            public class EnvironmentProbe {
                public static void main(String[] args) throws Exception {
                    if (args.length == 0) {
                        if (!"host-only".equals(System.getenv("MAZEWALL_HOST_ONLY_MARKER"))) throw new AssertionError("host marker not acquired");
                        if (!"legacy-region".equals(System.getenv("MAZEWALL_DEPLOYMENT_REGION"))) throw new AssertionError("deployment marker not acquired");
                        var command = java.util.List.of(System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("java.class.path"), "EnvironmentProbe", "child");
                        var child = RealProcessLauncher.INSTANCE.startProcess(command, true, builder -> {
                            builder.environment().put("MAZEWALL_APPROVED_MARKER", "approved");
                            builder.environment().remove("MAZEWALL_HOST_ONLY_MARKER");
                        });
                        try {
                            if (!child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("child timeout");
                            if (child.exitValue() != 0) throw new AssertionError(new String(child.getInputStream().readAllBytes()));
                        } finally { child.destroyForcibly(); child.waitFor(); }
                    } else {
                        if (System.getenv("MAZEWALL_HOST_ONLY_MARKER") != null) throw new AssertionError("host-only marker leaked");
                        if (!"approved".equals(System.getenv("MAZEWALL_APPROVED_MARKER"))) throw new AssertionError("approved marker missing");
                        if (!"legacy-region".equals(System.getenv("MAZEWALL_DEPLOYMENT_REGION"))) throw new AssertionError("inherited deployment marker lost");
                    }
                }
            }
        """.trimIndent(),
        )
        val cp = System.getProperty("java.class.path")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", cp, "-d", directory.toString(), source.toString()))
        val process = JvmChildProcess.start(
            RealProcessLauncher,
            JvmChildSpec(
                "EnvironmentProbe",
                classpath = "$directory${java.io.File.pathSeparator}$cp",
                javaAgents = JavaAgentSelection.None,
            ),
            preparation = JvmLaunchPreparation { builder ->
                builder.environment()["MAZEWALL_HOST_ONLY_MARKER"] = "host-only"
                builder.environment()["MAZEWALL_DEPLOYMENT_REGION"] = "legacy-region"
            },
        )
        try {
            assertEquals(true, process.waitFor(15, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), process.inputStream.readAllBytes().toString(Charsets.UTF_8))
            assertFalse(process.isAlive)
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `unsupported operator preparation rejects before legacy launch`() {
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
                preparation: JvmLaunchPreparation,
            ): Process = throw UnsupportedOperationException("unsupported required selection")

            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process = error("legacy launch must never run")
        }
        assertFailsWith<UnsupportedOperationException> { JvmChildProcess.start(launcher, JvmChildSpec("unused"), preparation = JvmLaunchPreparation {}) }
    }

    @Test
    fun `operator hook cannot substitute command or borrowed transport before acquisition`() {
        val spec = JvmChildSpec("unused", javaAgents = JavaAgentSelection.None)
        for (prepare in listOf<JvmLaunchPreparation>(JvmLaunchPreparation { it.command(listOf("DO_NOT_RUN")) }, JvmLaunchPreparation { it.inheritIO() })) {
            assertFailsWith<IllegalArgumentException> { JvmChildProcess.start(RealProcessLauncher, spec, preparation = prepare) }
        }
    }
}
