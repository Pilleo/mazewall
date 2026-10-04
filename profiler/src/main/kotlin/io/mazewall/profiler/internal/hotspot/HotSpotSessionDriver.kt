package io.mazewall.profiler.internal.hotspot

import java.util.concurrent.atomic.AtomicReference

internal interface HotSpotCollectorCallbacks {
    fun record(row: HotSpotWireRecord)

    fun event(event: HotSpotSessionEvent)

    fun diagnostics(
        bytes: ByteArray,
        count: Int,
    )

    fun pulse()

    fun timerActive(): Boolean
}

/** Effect renderer. Domain state is committed by the interpreter before any process I/O is scheduled. */
internal class HotSpotSessionDriver(
    private val interpreter: HotSpotSessionInterpreter,
) : HotSpotCollectorCallbacks {
    private val ownedWorkers = AtomicReference<HotSpotCollectorWorkers?>()
    private var argv: List<String> = emptyList()

    fun bind(
        workers: HotSpotCollectorWorkers,
        argv: List<String>,
    ) {
        check(ownedWorkers.compareAndSet(null, workers)) { "Collector workers already bound" }
        this.argv = java.util.List.copyOf(argv)
    }

    override fun record(row: HotSpotWireRecord) = execute(interpreter.record(row))

    override fun event(event: HotSpotSessionEvent) =
        execute(
        interpreter.event(
        if (event is HotSpotSessionEvent.ProcessExited && event.code != 0) HotSpotSessionEvent.Failure(HotSpotCollectorExitFailure(event.code)) else event,
    ),
    )

    override fun diagnostics(
        bytes: ByteArray,
        count: Int,
    ) = interpreter.diagnostics(bytes, count)

    override fun pulse() = event(HotSpotSessionEvent.Tick)

    override fun timerActive(): Boolean =
        when (interpreter.state) {
        HotSpotSessionState.Created, is HotSpotSessionState.Starting, is HotSpotSessionState.Ready, is HotSpotSessionState.Running -> true
        is HotSpotSessionState.Finishing, is HotSpotSessionState.Failed, is HotSpotSessionState.Completed -> false
    }

    private fun execute(effects: List<HotSpotSessionEffect>) {
        val workers = checkNotNull(ownedWorkers.get())
        for (effect in effects) {
            when (effect) {
                HotSpotSessionEffect.Launch -> workers.launch(argv)
                HotSpotSessionEffect.RequestStop -> workers.requestStop()
                HotSpotSessionEffect.Abort -> workers.abort()
                HotSpotSessionEffect.PublishReady, HotSpotSessionEffect.RunWork, is HotSpotSessionEffect.AwaitOwner -> Unit
            }
        }
    }
}
