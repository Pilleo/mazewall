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
        try {
            try {
                Class.forName("demo.documents.MarkdownOutline", false, OutlineFileApplication::class.java.classLoader)
                error("Worker implementation leaked onto the ordinary host classpath")
            } catch (_: ClassNotFoundException) {
                // Opaque generated worker bundle only.
            }
            lateinit var child: ProcessHandle
            lateinit var tool: io.mazewall.koog.PortalToolAdapter<OutlineArguments, String>
            val outline = PortalRuntime.open(outlineConfiguration(root.resolve("worker-cache"))).use { runtime ->
                tool = outlineTool(runtime)
                val pid = runtime
                    .health()
                    .single { it.isolationGroup == "document-outline" }
                    .workerPids
                    .single()
                check(pid != ProcessHandle.current().pid())
                child = ProcessHandle.of(pid).orElseThrow()
                check(child.isAlive)
                println("OUTLINE_FILE_BOUNDARY host=${ProcessHandle.current().pid()} worker=$pid; input acquisition=host parsing=worker")
                runBlocking { tool.execute(OutlineArguments(markdown)) }
            }
            val closed = tool.diagnostics()
            check(!child.isAlive && closed.state == ExecutionDiagnosticState.CLOSED && closed.retirementComplete && closed.liveWorkers == 0)
            retired = true
            require(outline.none { it.isISOControl() && it != '\n' && it != '\t' }) { "Terminal control characters are not outline display content" }
            println("# Document outline\n$outline")
            println("OUTLINE_FILE_RETIRED actual captured worker process ended; host implementation remained opaque")
        } finally {
            if (retired) {
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            } else {
                System.err.println("Outline cache retained because physical retirement was not established: $root")
            }
        }
    }
}
