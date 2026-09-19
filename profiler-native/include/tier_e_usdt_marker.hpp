#pragma once

#if defined(MAZEWALL_ENABLE_TIER_E_USDT)
#include <sys/sdt.h>

#define MAZEWALL_TIER_E_USDT_MARKER(invocation_id, flags) \
    DTRACE_PROBE2(mazewall, stack_marker_v1, invocation_id, flags)
#else
#define MAZEWALL_TIER_E_USDT_MARKER(invocation_id, flags) \
    do { \
        (void)(invocation_id); \
        (void)(flags); \
    } while (false)
#endif
