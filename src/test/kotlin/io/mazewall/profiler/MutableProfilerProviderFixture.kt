package io.mazewall.profiler

internal object MutableProfilerProviderFixture {
    var provider: (() -> Any)? = null
}
