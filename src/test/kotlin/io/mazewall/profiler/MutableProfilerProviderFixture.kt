package io.mazewall.profiler.api

internal object MutableProfilerProviderFixture {
    var provider: (() -> Any)? = null
}
