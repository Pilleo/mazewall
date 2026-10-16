package demo

import java.util.concurrent.Executors

object UnsafeRunner {
    fun run(payload: String) = runAction { VulnerableLogger.log(payload) }

    internal fun runAction(action: () -> String) {
        val executor = Executors.newSingleThreadExecutor()
        OwnedActionRunner.run(executor) {
            executor.submit<String> {
                action()
            }
        }
    }
}
