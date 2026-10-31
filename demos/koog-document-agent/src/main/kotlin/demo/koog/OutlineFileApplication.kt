package demo.koog

import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState
import io.mazewall.portal.PortalRuntime
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

object OutlineFileApplication {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1 && !Thread.currentThread().isVirtual) { "Usage: outline <markdown-file>" }
        // Input acquisition is trusted host work; only the parser runs in the guest process.
        val markdown = DocumentReader.readBoundedDocument(Path.of(args.single()))
        installPortalHostBaseline()
        val root = Files.createTempDirectory("mazewall-outline-file-")
        var retired = false
        AutoCloseable {
            if (retired) {
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            } else {
                System.err.println("Outline cache retained because physical retirement was not established: $root")
            }
        }.use {
            try {
                Class.forName("demo.documents.MarkdownOutline", false, OutlineFileApplication::class.java.classLoader)
                error("Worker implementation leaked onto the ordinary host classpath")
            } catch (_: ClassNotFoundException) {
                // Opaque generated worker bundle only.
            }
            var child: ProcessHandle? = null
            var tool: io.mazewall.koog.PortalToolAdapter<OutlineArguments, String>? = null
            val outline = AutoCloseable {
                val closed = tool?.diagnostics()
                check(child?.isAlive == false && closed?.state == ExecutionDiagnosticState.CLOSED && closed.retirementComplete && closed.liveWorkers == 0)
                retired = true
                println("OUTLINE_FILE_RETIRED actual captured worker process ended; host implementation remained opaque")
            }.use {
                PortalRuntime.open(outlineConfiguration(root.resolve("worker-cache"))).use { runtime ->
                val prepared = outlineTool(runtime)
                tool = prepared
                val pid = runtime
                    .health()
                    .single { it.isolationGroup == "document-outline" }
                    .workerPids
                    .single()
                check(pid != ProcessHandle.current().pid())
                val acquired = ProcessHandle.of(pid).orElseThrow()
                child = acquired
                check(acquired.isAlive)
                println("OUTLINE_FILE_BOUNDARY host=${ProcessHandle.current().pid()} worker=$pid; input acquisition=host parsing=worker")
                runBlocking { prepared.execute(OutlineArguments(markdown)) }
                }
            }
            require(outline.none { it.isISOControl() && it != '\n' && it != '\t' }) { "Terminal control characters are not outline display content" }
            println("# Document outline\n$outline")
        }
    }
}
