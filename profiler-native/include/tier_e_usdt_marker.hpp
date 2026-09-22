#pragma once

#if defined(MAZEWALL_ENABLE_TIER_E_USDT)
#include <sys/sdt.h>

#define MAZEWALL_TIER_E_USDT_MARKER(invocation_id, flags) \
    DTRACE_PROBE2(mazewall, stack_marker_v1, invocation_id, flags)

#define MAZEWALL_TIER_E_USDT_MARKER_V2(invocation_id, stack_trace_id, flags) \
    DTRACE_PROBE3(mazewall, stack_marker_v2, invocation_id, stack_trace_id, flags)
#else
#define MAZEWALL_TIER_E_USDT_MARKER(invocation_id, flags) \
    do { \
        (void)(invocation_id); \
        (void)(flags); \
    } while (false)

#define MAZEWALL_TIER_E_USDT_MARKER_V2(invocation_id, stack_trace_id, flags) \
    do { \
        (void)(invocation_id); \
        (void)(stack_trace_id); \
        (void)(flags); \
    } while (false)
#endif
