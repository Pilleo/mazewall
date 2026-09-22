typedef unsigned long long u64;
typedef unsigned int u32;

struct mazewall_invocation_state {
    u64 invocation_id;
    u64 sequence;
    u32 flags;
};

struct mazewall_invocation_state_v2 {
    u64 invocation_id;
    u64 sequence;
    u32 flags;
    u32 reserved;
    u64 stack_trace_id;
};

/* Retain the type in BTF without emitting a zero-sized DATASEC. */
__attribute__((used)) static int mazewall_invocation_state_anchor(
    int key,
    const struct mazewall_invocation_state* state) {
    return key + (int)state->flags;
}

__attribute__((used)) static int mazewall_invocation_state_v2_anchor(
    int key,
    const struct mazewall_invocation_state_v2* state) {
    return key + (int)state->flags + (int)state->stack_trace_id;
}
