package consumer.koogspring

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.KotlinxSerializer
import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.OperationAdapter
import io.mazewall.enforcer.api.OperationBinding
import io.mazewall.enforcer.api.OperationPolicyDocument
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.ReviewedOperationArtifact
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.TrustedPolicyReview
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.enforcer.api.execution.PreparedOperationResources
import io.mazewall.spring.DomainLifecycle
import io.mazewall.spring.InvocationContextBridge
import io.mazewall.spring.SpringOperationBinder
import io.mazewall.spring.SpringSecurityContextBridge
import io.mazewall.spring.SpringSecurityMethodAdvice
import io.mazewall.spring.SpringTransactionEffects
import io.mazewall.spring.SpringTransactionGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.h2.jdbcx.JdbcDataSource
import org.springframework.aop.framework.Advised
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.AccessDeniedException as FileAccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val profile = RuntimeProfile.HOTSPOT_JIT
private val budget = Duration.ofSeconds(10)

/** Runs only in fresh JAR-only JVMs; bootstrap/approval never runs on a virtual caller. */
fun main(args: Array<String>) {
    require(!Thread.currentThread().isVirtual && args.size == 3)
    val action = args[0]
    val root = Path.of(args[1]).toRealPath()
    val callerKind = args[2]
    require(callerKind == "platform" || callerKind == "virtual")
    val type = ApprovedJdbcService::class.java
    val jar = Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()
    check(Files.isRegularFile(jar) && jar.toString().endsWith(".jar") && type.classLoader === ClassLoader.getSystemClassLoader())
    val policy = WorkerPolicyBuilder.denyList(profile).allowFsRead(jar, root.resolve("template.txt")).build()
    PreparedOperationResources.resolve(profile, policy, type, setOf(jar, root.resolve("template.txt"))).use { resources ->
        if (action == "measure") {
            val document = OperationPolicyDocument(binding(resources.implementationSha256, resources.resourceGeneration), CanonicalPolicyArtifact.worker(profile, policy), emptySet(), emptyMap())
            Files.writeString(root.resolve("candidate.json"), document.toJson())
            Files.writeString(root.resolve("candidate-generation.txt"), resources.resourceGeneration)
            println("KOOG_SPRING_CANDIDATE_ONLY caller=$callerKind jar=${resources.implementationSha256}")
            return
        }
        require(action == "verify")
        val authority = Properties().apply { Files.newInputStream(root.resolve("trusted-review.properties")).use { load(it) } }
        val expected = binding(authority.getProperty("jarSha256"), authority.getProperty("resourceGeneration"))
        val review = TrustedPolicyReview(authority.getProperty("reviewer"), authority.getProperty("documentSha256"), emptySet())
        val artifact = ReviewedOperationArtifact.load(Files.readString(root.resolve("candidate.json")), review, expected)
        check(resources.implementationSha256 == expected.implementationSha256)
        check(resources.implementationSha256 != SpringOperationBinder.classResourceSha256(type))
        verify(root, callerKind, resources, artifact)
    }
    println("KOOG_SPRING_V1_OK caller=$callerKind registry=upstream binding=approved worker=platform identity=restored jdbc=commit-and-rollback pre=denied post=rolled-back business=preserved callerTransaction=rejected fs=denied cancellation=cooperative retirement=complete fallback=false replay=false")
}

private fun binding(sha: String, generation: String) = OperationBinding(
    "koog-spring-jdbc", OperationAdapter.SPRING_METHOD, sha, generation,
    mapOf("beanName" to "jdbcService", "className" to ApprovedJdbcService::class.java.name, "methodDescriptor" to "run(Ljava/lang/String;)Ljava/lang/String;"),
)

private fun verify(root: Path, callerKind: String, resources: PreparedOperationResources, artifact: ReviewedOperationArtifact) {
    val data = JdbcDataSource().apply { setURL("jdbc:h2:mem:koogSpring;DB_CLOSE_DELAY=-1") }
    val jdbc = JdbcTemplate(data)
    jdbc.execute("create table effects(name varchar(40))")
    jdbc.update("insert into effects(name) values ('trusted-warmup')")
    jdbc.queryForList("select * from effects")
    jdbc.update("delete from effects")
    val beans = DefaultListableBeanFactory()
    val transactions = DataSourceTransactionManager(data)
    beans.registerSingleton("jdbc", transactions)
    val effects = SpringTransactionEffects()
    val guard = SpringTransactionGuard(beans, "jdbc", effects)
    val target = ApprovedJdbcService(jdbc, root.resolve("template.txt"), root.resolve("sibling.txt"))
    val capturedWorker = AtomicReference<Thread>()
    val restored = AtomicInteger()
    val security = SpringSecurityContextBridge()
    val contexts = InvocationContextBridge { invocation ->
        val delegated = security.capture(invocation)
        Callable {
            val worker = Thread.currentThread()
            capturedWorker.compareAndSet(null, worker)
            check(worker === capturedWorker.get() && !worker.isVirtual)
            check(SecurityContextHolder.getContext().authentication == null)
            try {
                delegated.call()
            } finally {
                check(SecurityContextHolder.getContext().authentication == null) { "Worker identity leaked after delegation" }
                SecurityContextHolder.clearContext()
                restored.incrementAndGet()
            }
        }
    }
    try {
        // Explicit application-owned Tier 1 bootstrap before any platform/virtual caller runs.
        ManagedRuntime.open(profile, Policy.NO_EXEC_HOTSPOT, budget, budget).use { runtime ->
            val lifecycle = DomainLifecycle(runtime, artifact, DomainLimits(1, 4), resources, Duration.ofMillis(50), contexts)
            lifecycle.start()
            check(lifecycle.diagnostics().state == ExecutionDiagnosticState.READY && lifecycle.diagnostics().invocations.admitted == 0L)
            resources.close() // Transferred owner remains live until the actual worker retires.
            val binder = SpringOperationBinder(beans, lifecycle, guard)
            val factory = ProxyFactory(target).apply {
                addAdvisor(binder.dispatchAdvisor())
                addAdvisor(guard.transactionAdvisor())
                addAdvisor(guard.diagnosisAdvisor())
                addAdvisor(SpringSecurityMethodAdvice.authorizationAdvisor())
                isFrozen = true
            }
            val proxy = factory.proxy as JdbcService
            beans.registerSingleton("jdbcService", proxy)
            val advisors = (proxy as Advised).advisors.toList()
            binder.bind(SpringOperationBinder.Approval(artifact, proxy, ApprovedJdbcService::class.java.classLoader, profile, resources.resourceGeneration,
                setOf(SpringOperationBinder.Phase.BODY, SpringOperationBinder.Phase.TRANSACTION_COMPLETION)))
            binder.validateRequiredBindings(listOf("jdbcService"))
            check(beans.getBean("jdbcService") === proxy && proxy.advisors.toList() == advisors)
            val tool = SpringServiceTool(proxy)
            val registry = ToolRegistry { tool(tool) }
            check(registry.tools.map { it.name } == listOf("reviewed_jdbc") && registry.getTool("reviewed_jdbc") === tool)
            val selected = registry.getTool<SpringServiceTool>()
            val cancellation = AtomicReference<Job>()
            val callerFailure = AtomicReference<Throwable>()
            val callerFactory = if (callerKind == "virtual") Thread.ofVirtual().factory() else Thread.ofPlatform().factory()
            val caller = callerFactory.newThread {
                try {
                    runBlocking {
                        verifyCalls(root, selected, target, lifecycle, jdbc, transactions, effects, callerKind)
                        val originalCaller = Thread.currentThread()
                        val originalContext = SecurityContextHolder.getContext()
                        cancellation.set(currentCoroutineContext().job)
                        try {
                            selected.execute(SpringServiceArguments("cancel"))
                            error("Cancelled invocation returned success")
                        } finally {
                            check(Thread.currentThread() === originalCaller && SecurityContextHolder.getContext() === originalContext)
                        }
                    }
                    error("Cancelled caller returned success")
                } catch (failure: Throwable) {
                    callerFailure.set(failure)
                } finally {
                    SecurityContextHolder.clearContext()
                }
            }
            caller.start()
            try {
                check(target.entered.await(5, TimeUnit.SECONDS)) { "Caller failed before cancellation body: ${callerFailure.get()}" }
                cancellation.get().cancel()
                caller.join(5000)
                check(!caller.isAlive && callerFailure.get() is CancellationException) { "Cancellation delivery failed: ${callerFailure.get()}" }
                check(target.cancellationSeen.await(5, TimeUnit.SECONDS))
                lifecycle.stop()
                check(lifecycle.lastDrain() is DrainReport.Incomplete)
                val pending = lifecycle.diagnostics()
                check(pending.state == ExecutionDiagnosticState.DRAINING && !pending.retirementComplete && pending.unfinishedInvocations > 0 && pending.invocations.cancellationRequests > 0)
                check(target.bodyEnded.count == 1L && capturedWorker.get().isAlive)
                resources.validate()
                println("KOOG_SPRING_CANCELLATION caller=$callerKind delivery=cancelled drain=incomplete resources=retained physical=live")
            } finally {
                target.cleanupRelease.countDown()
                cancellation.get()?.cancel()
                if (caller.isAlive) caller.interrupt()
                caller.join(5000)
            }
            check(target.bodyEnded.await(5, TimeUnit.SECONDS))
            capturedWorker.get().join(5000)
            check(!capturedWorker.get().isAlive && !caller.isAlive)
            lifecycle.stop()
            check(lifecycle.lastDrain() == DrainReport.Complete)
            val retired = lifecycle.diagnostics()
            check(retired.state == ExecutionDiagnosticState.CLOSED && retired.retirementComplete && retired.liveWorkers == 0 && retired.unfinishedInvocations == 0)
            failure<IllegalStateException> { resources.validate() }
            check(restored.get() == 7 && target.entries.get() == 6 && target.worker.get() === capturedWorker.get())
            check(retired.invocations.admitted == 7L && retired.invocations.ended == 7L) { "No replay or caller-transaction admission" }
            rolledBack(jdbc, effects, "cancel")
            check(jdbc.queryForList("select name from effects order by name", String::class.java) == listOf("afterDenied", "approved"))
            check(Files.readString(root.resolve("sibling.txt")) == "private sibling")
        }
    } finally {
        jdbc.execute("shutdown")
    }
}
private suspend fun verifyCalls(root: Path, tool: SpringServiceTool, target: ApprovedJdbcService, lifecycle: DomainLifecycle,
    jdbc: JdbcTemplate, transactions: DataSourceTransactionManager, effects: SpringTransactionEffects, callerKind: String) {
    val caller = Thread.currentThread()
    check(caller.isVirtual == (callerKind == "virtual"))
    val serializer = KotlinxSerializer(Json { ignoreUnknownKeys = false })
    val arguments = tool.decodeArgs(serializer.decodeJSONElementFromString("{\"mode\":\"approved\"}") as JSONObject, serializer)
    failure<SerializationException> {
        tool.decodeArgs(serializer.decodeJSONElementFromString("{\"mode\":\"approved\",\"policy\":\"unrestricted\"}") as JSONObject, serializer)
    }
    for (invalid in listOf("", " ", "a\u0000b", "é".repeat(2049))) {
        failure<IllegalArgumentException> { tool.execute(SpringServiceArguments(invalid)) }
    }
    check(lifecycle.diagnostics().invocations.admitted == 0L && target.entries.get() == 0)
    principal("admin")
    val original = SecurityContextHolder.getContext()
    check(tool.execute(arguments) == "approved:approved template")
    check(target.entries.get() == 1 && target.worker.get() !== caller && SecurityContextHolder.getContext() === original)
    check(effects.lastCompletion().transactions().single().outcome() == SpringTransactionEffects.Outcome.COMMITTED)
    check(jdbc.queryForObject("select count(*) from effects where name = 'approved'", Int::class.java) == 1)
    SecurityContextHolder.clearContext()
    val unauthenticated = failure<Exception> { tool.execute(SpringServiceArguments("unauthenticated")) }
    check(causes(unauthenticated).any { it is AccessDeniedException || it is AuthenticationCredentialsNotFoundException })
    check(target.entries.get() == 1 && jdbc.queryForObject("select count(*) from effects", Int::class.java) == 1)
    check(effects.lastCompletion().diagnostic() == null)
    principal("bob")
    val bob = SecurityContextHolder.getContext()
    val postDenied = failure<Exception> { tool.execute(SpringServiceArguments("postDenied")) }
    check(causes(postDenied).any { it is AccessDeniedException } && SecurityContextHolder.getContext() === bob)
    rolledBack(jdbc, effects, "postDenied")
    check(effects.lastCompletion().diagnostic() == null)
    principal("admin")
    val admin = SecurityContextHolder.getContext()
    check(tool.execute(SpringServiceArguments("afterDenied")) == "afterDenied:approved template")
    check(effects.lastCompletion().transactions().single().outcome() == SpringTransactionEffects.Outcome.COMMITTED)
    val business = failure<InterruptedException> { tool.execute(SpringServiceArguments("business")) }
    check(business === target.businessInterruption && currentCoroutineContext().isActive && !caller.isInterrupted)
    rolledBack(jdbc, effects, "business")
    val beforeEntries = target.entries.get()
    val beforeAdmissions = lifecycle.diagnostics().invocations.admitted
    val transaction = transactions.getTransaction(DefaultTransactionDefinition())
    try {
        val connection = TransactionSynchronizationManager.getResource(jdbc.dataSource!!)
        check(connection != null)
        val rejected = failure<IllegalStateException> { tool.execute(SpringServiceArguments("callerTx")) }
        check(rejected.message.orEmpty().contains("Cannot dispatch an external thread-bound transaction/resource"))
        check(TransactionSynchronizationManager.getResource(jdbc.dataSource!!) === connection)
        check(target.entries.get() == beforeEntries && lifecycle.diagnostics().invocations.admitted == beforeAdmissions)
    } finally {
        transactions.rollback(transaction)
    }
    check(TransactionSynchronizationManager.getResourceMap().isEmpty())
    check(Files.readString(root.resolve("sibling.txt")) == "private sibling")
    val denied = failure<Exception> { tool.execute(SpringServiceArguments("sibling")) }
    check(causes(denied).any { it is FileAccessDeniedException }) { "Actual sibling open denial required: $denied" }
    rolledBack(jdbc, effects, "sibling")
    check(SecurityContextHolder.getContext() === admin && Thread.currentThread() === caller)
    check(Files.readString(root.resolve("sibling.txt")) == "private sibling")
    println("KOOG_SPRING_CALLS caller=$callerKind admin=committed unauthenticated=zero-body post=rolled-back business=original callerTx=zero-admissions sibling=denied")
}

private fun principal(name: String) {
    SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext().apply {
        authentication = UsernamePasswordAuthenticationToken.authenticated(name, null, listOf(SimpleGrantedAuthority(if (name == "admin") "ROLE_ADMIN" else "ROLE_USER")))
    })
}

private fun rolledBack(jdbc: JdbcTemplate, effects: SpringTransactionEffects, mode: String) {
    check(jdbc.queryForObject("select count(*) from effects where name = ?", Int::class.java, mode) == 0)
    check(effects.lastCompletion().transactions().single().outcome() == SpringTransactionEffects.Outcome.ROLLED_BACK)
}

private fun causes(failure: Throwable) = generateSequence(failure) { it.cause }.toList()

private inline fun <reified T : Throwable> failure(action: () -> Unit): T {
    val result = runCatching(action).exceptionOrNull()
    check(result is T) { "Expected ${T::class.java.name}, got $result" }
    return result
}
