#include "symbolization_generation.hpp"

#include <cassert>

int main() {
    mazewall::profiler::SymbolizationGeneration generation;

    const auto first = generation.begin_capture();
    assert(first.valid());
    assert(generation.validate(first));

    generation.begin_replacement(false);
    assert(!generation.begin_capture().valid());
    assert(!generation.validate(first));
    generation.complete_replacement();

    const auto second = generation.begin_capture();
    assert(second.valid());
    assert(second.value != first.value);
    assert(generation.validate(second));

    generation.begin_replacement(true);
    assert(!generation.begin_capture().valid());
    generation.resynchronize();
    const auto third = generation.begin_capture();
    assert(third.valid());
    assert(third.value != second.value);
    return 0;
}
