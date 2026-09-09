package io.mazewall.portal

internal object MutablePortalProviderFixture {
    interface DependencyProvider

    var provider: DependencyProvider? = null
}
