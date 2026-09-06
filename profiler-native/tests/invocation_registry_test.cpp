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
}
