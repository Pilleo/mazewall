package io.mazewall.seccomp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.nio.file.Path
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider
import kotlin.test.assertEquals

/** Compile real consumers against the JVM API: no reflection or source-text approximations. */
class BpfCompileContractTest {
    @TempDir
    lateinit var output: Path

    @Test
    fun `only verified immutable programs and valid builder transitions compile`() {
        val setup = """
            var initial = BpfProgram.Companion.builder();
            var active = initial.checkArch(Arch.Companion.getAMD64()).loadSyscallNr();
            BpfProgram<BpfStatus.Unverified> raw = active.allow().build();
        """.trimIndent()
        compiles(setup + "BpfProgram<BpfStatus.Verified> checked = BpfStaticVerifier.INSTANCE.verify(raw);", expected = true)
        val invalid = listOf(
            "new BpfProgram<BpfStatus.Verified>(java.util.List.of());",
            "new BpfBuilder();",
            "initial.allow();",
            "initial.loadSyscallNr();",
            "active.build();",
            "active.allow().loadAbsolute(0);",
            "BpfProgram<BpfStatus.Verified> checked = raw;",
            "new BpfInstruction.Ld((short) 0x06, 0);",
            "new BpfInstruction.Jmp((short) 0x05, (short) 0, (short) 0, 1);",
            "new BpfInstruction.Ret(0x7fff0000);",
            "active.expect(1, (java.util.function.Function<BpfBuilder.Active, BpfBuilder.Active>) b -> b);",
            "new SyscallInspection(1, 6, new ArgCheck.MaskEquals(1L, 1L), SeccompAction.ACT_ALLOW.INSTANCE, SeccompAction.ACT_ALLOW.INSTANCE);",
        )
        invalid.forEach { compiles(setup + it, expected = false) }
    }

    private fun compiles(
        body: String,
        expected: Boolean,
    ) {
        val source = object : SimpleJavaFileObject(URI.create("string:///BpfContract.java"), JavaFileObject.Kind.SOURCE) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence =
                """
                import io.mazewall.seccomp.*;
                import io.mazewall.platform.syscall.*;
                class BpfContract { void exercise() { $body } }
            """.trimIndent()
        }
        val classpath = listOf(BpfProgram::class.java, BpfInstruction::class.java, Unit::class.java)
            .map {
                val location = it.protectionDomain.codeSource.location
                File(location.toURI()).path
            }.distinct()
            .joinToString(File.pathSeparator)
        val compiler = ToolProvider.getSystemJavaCompiler()
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, null).use { files ->
            val success = compiler
                .getTask(
                null,
                files,
                diagnostics,
                listOf("-proc:none", "-classpath", classpath, "-d", output.toString()),
                null,
                listOf(source),
            ).call()
            assertEquals(expected, success, "$body\n${diagnostics.diagnostics.joinToString("\n")}")
        }
    }
}
