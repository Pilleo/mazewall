package io.mazewall

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SupervisorReplyArchitectureTest {
    @Test
    fun `handler stays within its coordinator size budget`() {
        val file = Path.of("src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt")
        val lines = Files.readAllLines(file).size
        assertTrue(lines <= 300, "SupervisorSessionHandler has $lines physical lines; maximum is 300")
    }

    @Test
    fun `only the reply interpreter can send supervisor kernel replies`() {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.mazewall.enforcer.supervisor")
        noClasses()
            .that()
            .haveNameNotMatching(".*SupervisorReplyInterpreter(\\$.*)?")
            .should()
            .callMethodWhere(object : DescribedPredicate<JavaMethodCall>("kernel reply sending") {
                override fun test(input: JavaMethodCall): Boolean =
                    (input.target.owner.name == "io.mazewall.platform.seccomp.UserNotifReply" && input.target.name == "send") ||
                        input.target.name in setOf("sendSeccompError", "sendSeccompContinue")
            })
            .check(classes)
    }
}
