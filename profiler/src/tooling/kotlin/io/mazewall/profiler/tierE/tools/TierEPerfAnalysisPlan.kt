package io.mazewall.profiler.tierE.tools

/** Immutable external-tool command schedule for post-recording analysis. */
public object TierEPerfAnalysisPlan {
    public fun commands(mode: String): List<List<String>> =
        when (mode) {
        "perf" -> listOf(
            listOf("perf", "buildid-cache", "-a", "/bundle/libmazewall_stack_agent.so"),
            listOf("perf", "report", "--stdio", "--no-children", "-i", "/artifacts/perf.data"),
            listOf("perf", "script", "-F", "comm,pid,tid,event,ip,sym,dso", "-i", "/artifacts/perf.data"),
            listOf("perf", "script", "--ns", "-F", "comm,pid,tid,time,event,ip,sym,dso", "-i", "/artifacts/perf.data"),
        )
        "allocation" -> listOf(listOf("perf", "script", "-f", "-i", "/artifacts/allocation.data"))
        else -> throw IllegalArgumentException("Unknown Tier E analysis mode: $mode")
    }
}
