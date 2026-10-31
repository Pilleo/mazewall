package demo.koog

import demo.koog.DocumentReader.readBoundedDocument
import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.OperationAdapter
import io.mazewall.enforcer.api.OperationBinding
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.ReviewedOperationArtifact
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.TrustedPolicyReview
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.enforcer.api.execution.PreparedOperationResources
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/** Development-only enforcement launch: independent explicit review is mandatory, even after a complete trace. */
object ReviewedDocumentRead {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3 && !Thread.currentThread().isVirtual) { "Usage: ReviewedDocumentRead <document> <candidate.json> <trusted-review.properties>" }
        val path = Path.of(args[0]).toRealPath()
        val external = Properties().apply { load(bounded(Path.of(args[2]), 16_384).reader()) }
        val binding = OperationBinding(
            "read_document",
            OperationAdapter.CALLABLE,
            external.getProperty("jarSha256"),
            external.getProperty("resourceGeneration"),
            mapOf("entryPoint" to "readBoundedDocument(Ljava/nio/file/Path;)Ljava/lang/String;"),
        )
        val artifact = ReviewedOperationArtifact.load(
            bounded(Path.of(args[1]), 2_097_152),
            TrustedPolicyReview(external.getProperty("reviewer"), external.getProperty("documentSha256"), emptySet()),
            binding,
        )
        require(artifact.document.policy.runtime == RuntimeProfile.HOTSPOT_JIT)
        val policy = (artifact.document.policy as CanonicalPolicyArtifact.Worker).policy
        val expected = readBoundedDocument(path)
        PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, DocumentReader::class.java, setOf(path)).use { resources ->
            val budget = Duration.ofSeconds(10)
            lateinit var worker: Thread
            ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, budget, budget).use { runtime ->
                val domain = runtime.prepare(artifact, DomainLimits(1, 4), resources)
                val result = runtime
                    .submit(
                    domain,
                    Callable {
                    worker = Thread.currentThread()
                    readBoundedDocument(path)
                },
                ).result()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)
                check(result == expected)
            }
            worker.join(5000)
            check(!worker.isAlive && !worker.isVirtual)
            println("REVIEWED_READ actual fixed reviewed policy, matching content, physically retired worker; no automatic approval")
        }
    }

    private fun bounded(
        path: Path,
        limit: Int,
    ): String {
        val bytes = Files.newInputStream(path).use { it.readNBytes(limit + 1) }
        require(bytes.size <= limit)
        return Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
}
