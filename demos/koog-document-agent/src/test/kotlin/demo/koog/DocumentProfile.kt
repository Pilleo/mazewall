package demo.koog

import demo.koog.DocumentReader.readBoundedDocument
import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.OperationAdapter
import io.mazewall.enforcer.api.OperationBinding
import io.mazewall.enforcer.api.OperationPolicyDocument
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.PreparedOperationResources
import io.mazewall.profiler.api.MazewallProfiler
import io.mazewall.profiler.api.ProfileOptions
import io.mazewall.profiler.api.ProfileStrategy
import io.mazewall.profiler.api.Profiler
import java.nio.file.Path

/** Development-only entry, deliberately absent from the ordinary application distribution. */
object DocumentProfile {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1 && !Thread.currentThread().isVirtual)
        val path = Path.of(args.single()).toRealPath()
        validateReadArguments(ReadDocumentArguments(path.toString()))
        val runtime = RuntimeProfile.HOTSPOT_JIT
        try {
            MazewallProfiler.open(ProfileOptions(strategy = ProfileStrategy.USER_NOTIF, captureStacks = true)).use { profiler ->
                val result = profiler.profile { readBoundedDocument(path) }
                check(result.value == readBoundedDocument(path))
                println("PROFILE_DOCUMENT ${result.toProfileDocumentJson()}")
                // The existing measured-coverage gate throws before any candidate when collection is incomplete.
                val policy = result.toPolicy(WorkerPolicyBuilder.denyList(runtime).allowJvmClasspath().build(), path.parent)
                PreparedOperationResources.resolve(runtime, policy, DocumentReader::class.java, setOf(path)).use { resources ->
                    val binding = OperationBinding(
                        "read_document",
                        OperationAdapter.CALLABLE,
                        resources.implementationSha256,
                        resources.resourceGeneration,
                        mapOf("entryPoint" to "readBoundedDocument(Ljava/nio/file/Path;)Ljava/lang/String;"),
                    )
                    println("CANDIDATE_DOCUMENT " + OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(runtime, policy), emptySet(), emptyMap()).toJson())
                    println("REVIEW_REQUIRED measured named reader only; no review authority or installed enforcement")
                }
            }
        } finally {
            Profiler.shutdown()
        }
    }
}
