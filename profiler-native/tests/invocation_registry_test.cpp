#include "invocation_registry.hpp"

int main() {
    mazewall::profiler::InvocationRegistry registry;

    auto outer = registry.enter(101);
    (void)outer;
    if (outer.parent_id != 0 || registry.current_id() != 101) return 1;

    const auto inner = registry.enter(202);
    (void)inner;
    if (inner.parent_id != 101 || registry.current_id() != 202) return 2;

    if (!registry.leave(inner) || registry.current_id() != 101) return 3;

    if (!registry.replace_current(outer, 303) || outer.id != 303 || registry.current_id() != 303) return 4;

    if (!registry.leave(outer) || registry.current_id() != 0) return 5;

    if (registry.leave(outer)) return 6;

    auto virtual_scope = registry.enter(404, 77);
    if (!virtual_scope.valid() || !registry.matches_current_execution(77)) return 7;

    const auto leaked_carrier_scope = registry.enter(505, 88);
    if (leaked_carrier_scope.valid() || registry.current_id() != 0) return 8;

    auto callback_outer = registry.enter(606);
    if (!callback_outer.valid() || registry.current_id() != 606) return 9;

    registry.suppress();
    if (registry.current_id() != 0) return 10;

    const auto callback_inner = registry.enter(707);
    if (!callback_inner.valid() || registry.current_id() != 707) return 11;
    if (!registry.leave(callback_inner) || registry.current_id() != 0) return 12;

    if (registry.leave(callback_outer) || registry.current_id() != 0) return 13;

    auto v2_outer = registry.enter(801, 1, 11);
    if (!v2_outer.valid() || registry.current_stack_id() != 11) return 14;

    const auto v2_inner = registry.enter(802, 1, 22);
    if (!v2_inner.valid() || registry.current_stack_id() != 22) return 15;

    if (!registry.leave(v2_inner) || registry.current_stack_id() != 11) return 16;

    if (!registry.leave(v2_outer) || registry.current_stack_id() != 0) return 17;

    auto capture_scope = registry.enter(901);
    if (!capture_scope.valid()) return 18;
    registry.set_current_stack(capture_scope, 33);
    if (registry.current_stack_id() != 33 || capture_scope.stack_trace_id != 33) return 19;
    if (!registry.leave(capture_scope) || registry.current_stack_id() != 0) return 20;
}
