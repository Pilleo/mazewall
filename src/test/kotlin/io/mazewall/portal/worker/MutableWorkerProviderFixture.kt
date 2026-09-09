package io.mazewall.portal.worker

internal class MutableWorkerProviderFixture {
    interface DependencyProvider

    companion object {
        var provider: DependencyProvider? = null
    }
}
