// SPDX-License-Identifier: GPL-2.0
/*
 * Tier E WP-03: the semantic-context marker.
 *
 * The function body is intentionally empty. Its only job is to be an
 * attachment point: a uprobe (G0a) or USDT probe (G0b) fires synchronously on
 * the calling task before this call returns, recording `context_id` into BPF
 * task-local storage. See docs/internals/designs/profiler/tier-e-design.md §4.
 *
 * The exported symbol is a bring-up ABI ONLY; the long-term interface is the
 * Mazewall USDT probe (design doc §4.1.1).
 */

#include <stdint.h>

#ifdef TIER_E_USDT
#include <sys/sdt.h>
#endif

__attribute__((visibility("default")))
void mazewall_context_marker(uint32_t context_id)
{
#ifdef TIER_E_USDT
	DTRACE_PROBE1(mazewall, context_switch, context_id);
#else
	(void)context_id;
#endif
}
