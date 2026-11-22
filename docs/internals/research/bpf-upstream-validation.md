# BPF compiler validation: upstream patterns and follow-ups

Date: 2026-11-03. Status: source review and proposed follow-ups; the recommendations
below are not implemented by this document. Upstream suites were inspected, not
executed. Local code references describe the reviewed checkout on this date.

The highest-value reuse is gVisor's fragment contracts and generated-instruction
coverage, libseccomp's semantic fixtures, and Linux's conformance cases. Preserve
mazewall's closed instruction variants, immutable program snapshots and private
verified construction. The [hardening review](../reviews/2026-11-03-bpf-generation-hardening.md)
records implementation and build evidence separately; the
[containment design](../designs/enforcer/containment-design.md#2-bpf-construction-and-dispatch-guarantees)
defines the current guarantees.

## Sources and revisions

| Implementation | Reviewed revision | Reusable material |
| --- | --- | --- |
| gVisor (Go) | `471d6dff5229b99208bf6fec985601067f1f9d69` | [Differential fuzzing and instruction coverage](https://github.com/google/gvisor/blob/471d6dff5229b99208bf6fec985601067f1f9d69/test/secfuzz/secfuzz.go); [predicate-derived seeds](https://github.com/google/gvisor/blob/471d6dff5229b99208bf6fec985601067f1f9d69/pkg/seccomp/seccomp_fuzz_helpers.go); [fragment outcomes](https://github.com/google/gvisor/blob/471d6dff5229b99208bf6fec985601067f1f9d69/pkg/bpf/program_builder.go#L220) |
| libseccomp (C) | `2814474a2bc105f5ffabf4bbc7ae9fb100997028` | [Data-driven regression runner](https://github.com/seccomp/libseccomp/blob/2814474a2bc105f5ffabf4bbc7ae9fb100997028/tests/regression); simulation, fuzz and live modes; [independent BPF simulator](https://github.com/seccomp/libseccomp/blob/2814474a2bc105f5ffabf4bbc7ae9fb100997028/tools/scmp_bpf_sim.c) |
| rust-vmm/seccompiler (Rust) | `c05f20798917d8df814d1154aea3135a53f7f944` | [Checked jump construction and long-rule cases](https://github.com/rust-vmm/seccompiler/blob/c05f20798917d8df814d1154aea3135a53f7f944/src/backend/rule.rs); [explicit Dword/Qword comparisons](https://github.com/rust-vmm/seccompiler/blob/c05f20798917d8df814d1154aea3135a53f7f944/src/backend/condition.rs); [kernel probes](https://github.com/rust-vmm/seccompiler/blob/c05f20798917d8df814d1154aea3135a53f7f944/tests/integration_tests.rs) |
| Minijail (C/C++) | `8787d1a4fdd7c64791607a1122d2408b3b35ade4` | [Label, argument, duplicate-rule and emitted-shape tests](https://github.com/google/minijail/blob/8787d1a4fdd7c64791607a1122d2408b3b35ade4/syscall_filter_unittest.cc) |
| Firecracker (Rust) | `9ae1f43245dff69ab7c2277fc932f356da2039a2` | [Current compiler wraps libseccomp](https://github.com/firecracker-microvm/firecracker/blob/9ae1f43245dff69ab7c2277fc932f356da2039a2/src/seccompiler/src/lib.rs#L74); it is not another independent backend alongside libseccomp |
| Linux (C) | `v6.18` | [Classic verifier](https://github.com/torvalds/linux/blob/v6.18/net/core/filter.c#L1067), [seccomp verifier](https://github.com/torvalds/linux/blob/v6.18/kernel/seccomp.c#L278), [UAPI layout/actions](https://github.com/torvalds/linux/blob/v6.18/include/uapi/linux/seccomp.h), [seccomp selftests](https://github.com/torvalds/linux/blob/v6.18/tools/testing/selftests/seccomp/seccomp_bpf.c) |

The supplementary [seccomp(2) reference](https://man7.org/linux/man-pages/man2/seccomp.2.html)
was retrieved as Linux man-pages 6.19. Context7 returned unversioned libseccomp
`main` documentation; source-specific findings above use the pinned revision.

## Source-confirmed gaps

### 1. A termination token does not prove fragment-local termination

[`BpfBuilder.Active.expect`](../../../enforcer/src/main/kotlin/io/mazewall/seccomp/BpfBuilder.kt#L95)
requires a current, same-owner `Terminated` token. Labels are owned by the builder,
not by an individual inspection fragment. Consequently, its checks permit this
shape inside an active builder:

```kotlin
val escape = createLabel("escape")
expect(123) {
    jmp(escape)
    deny(1)
}
mark(escape)
allow()
```

The unreachable `deny` supplies the current token; the matched path jumps outside
the inspection to `allow`. Whole-program structural verification can accept the
forward, in-bounds control flow. This example is source-derived, not a newly run
regression. The current built-in argument inspectors use local exits, and the
clone3 handler returns directly.

gVisor's
[`MustHaveJumpedToOrReturned`](https://github.com/google/gvisor/blob/471d6dff5229b99208bf6fec985601067f1f9d69/pkg/seccomp/seccomp.go#L255)
checks fragment fallthrough, external destinations and permitted return values.
Adapt that contract: an inspection's matched paths must return internally or reach
explicitly permitted exits. Fragment-scoped labels can constrain construction;
dynamic control-flow validation must still check the emitted fragment.

Acceptance: the escaping-label example fails, valid multi-branch inspections pass,
and existing foreign/stale-token, label-ownership and immutable-prefix regressions
remain green. Track in the
[fragment-exit issue](../backlog/resolved/issue-20261103-123347-795-validate-bpf-inspection-fragment-exits-before-acce.md).

### 2. Differential verification silently drops a missing verdict

[`assertKernelVerdictMatchesSimulator`](../../../enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/SeccompDifferentialVerdictTest.kt#L72)
maps simulator `null` to neither its blocked nor its allowed probe list. Both
assertion loops then omit that probe. A missing verdict must fail the test with
the input and program attached; it cannot count as agreement.

Acceptance: a no-RET input produces an assertion failure naming the probe; valid
allow/deny cases still reach their kernel checks. Preserve the explicit benign
probe restrictions. Track in the
[missing-verdict issue](../backlog/resolved/issue-20261103-123359-606-fail-differential-seccomp-checks-when-simulator-ha.md).

## Validation improvements

### Independent semantic oracles

The existing
[`BpfFilterPropertyTest`](../../../enforcer/src/test/kotlin/io/mazewall/seccomp/BpfFilterPropertyTest.kt)
checks dispatch boundary verdicts on both architectures. Some argument tests in
[`BpfFilterTest`](../../../enforcer/src/test/kotlin/io/mazewall/seccomp/BpfFilterTest.kt#L278)
only assert that expected constants or instructions exist. Such checks do not
establish that branches connect them correctly. Its property-test companion's
"random combinations" size test enumerates fixed chunks of syscall entries.

Add a small independent test evaluator expressing membership and mask predicates:

```text
direct evaluation of effective policy(input)
    == interpretation of compiled BPF(input)
```

The evaluator must account explicitly for the architecture/x32 guard, JVM floor,
special inspections, defaults and profiling substitutions without calling the
compiler's decision helpers. Compare complete action/payload words in simulation.
Use a separately implemented interpreter over serialized instructions to exercise
packing as well as the Kotlin instruction model.

gVisor's
[optimized/unoptimized equivalence test](https://github.com/google/gvisor/blob/471d6dff5229b99208bf6fec985601067f1f9d69/runsc/boot/filter/filter_fuzz_test.go)
is a useful pattern for our existing dispatch paths. A direct evaluator remains
valuable because two compilation paths can share the same mistake. An external
compiler/interpreter would be test tooling, requiring its own scoped integration;
this review does not select a new production dependency or dispatch algorithm.

### Generated-bytecode coverage and reproducible seeds

gVisor records executed instruction positions and runs a deterministic
`StaticCorpus` through the same comparison harness used for fuzzing. Adapt these
patterns with the existing test framework:

- Generate matching and near-matching inputs from each rule.
- Represent each 64-bit argument as separate high/low 32-bit fuzz inputs so each
  half's comparison can be explored independently.
- Record visited BPF instruction positions; separately consider true/false edge
  coverage. The inspected gVisor enforcement records instructions, which is not
  by itself proof that every conditional edge was exercised.
- Retain failing inputs as replayable fixtures with architecture, rule/policy,
  seed, arguments, expected/actual action and disassembly.
- Account explicitly for impossible edges such as the false outcome of
  `(A & 0) == 0`, and unreachable returns emitted for an empty equality set.

Kotlin source coverage and BPF execution coverage measure different things.
Coverage and finite fuzz samples also do not prove semantic equivalence.

### Shared boundary corpus

Use libseccomp's fixture concept: architecture, syscall, six arguments and exact
expected verdict. Include instruction pointer only if the harness can model it
faithfully. Reuse existing cases before adding new ones.

| Area | Cases to retain or add |
| --- | --- |
| Width and argument layout | All six slots; correct-low/wrong-high and correct-high/wrong-low combinations; `0`, `1`, `0x7fffffff`, `0x80000000`, `0xffffffff`, `0x100000000`, all-ones 64-bit values |
| Masks | Zero/full masks, high-only/low-only masks, one required bit missing, irrelevant bits changed, invalid expected bits rejected |
| Equality sets | Empty/singleton, duplicate/reordered values, sets near conditional-jump limits; membership semantics preserved under permutation/duplication |
| Control flow | Conditional offsets 254/255/256, long JA, unreachable invalid instructions, fragment escapes, unknown/duplicate/foreign/backward labels |
| Dispatch | Existing 31/32/33 and 99/100/101 boundaries, sparse numbers, input-map permutations, matched/default actions, JVM-floor overrides and profiling substitutions |
| ABI and actions | Wrong architecture, x32 bit and 511/512/547/548 boundaries, exact errno/TRACE payloads, AMD64 and AARCH64 |
| Size | Empty program, exact 4096 acceptance, 4097 rejection, separate stacked-filter budget checks |

Useful pinned libseccomp cases:
[05 long jumps](https://github.com/seccomp/libseccomp/blob/2814474a2bc105f5ffabf4bbc7ae9fb100997028/tests/05-sim-long_jumps.c),
[12 masked operations](https://github.com/seccomp/libseccomp/blob/2814474a2bc105f5ffabf4bbc7ae9fb100997028/tests/12-sim-basic_masked_ops.tests),
[35 negative one](https://github.com/seccomp/libseccomp/blob/2814474a2bc105f5ffabf4bbc7ae9fb100997028/tests/35-sim-negative_one.tests),
[48 argument widths](https://github.com/seccomp/libseccomp/blob/2814474a2bc105f5ffabf4bbc7ae9fb100997028/tests/48-sim-32b_args.tests),
[49 64-bit comparisons](https://github.com/seccomp/libseccomp/blob/2814474a2bc105f5ffabf4bbc7ae9fb100997028/tests/49-sim-64b_comparisons.tests),
and [53 binary-tree dispatch](https://github.com/seccomp/libseccomp/blob/2814474a2bc105f5ffabf4bbc7ae9fb100997028/tests/53-sim-binary_tree.tests).
Translate case intent to our supported predicates rather than adding comparison
operators just to run an upstream fixture.

### Kernel conformance and observable outcomes

Linux's selftests provide cases for `filter_size_limits`, `filter_chain_limits`,
`arg_out_of_range`, `ERRNO_valid`, `ERRNO_capped`, `ERRNO_order` and action
precedence. For the supported subset, local acceptance should imply kernel
structural acceptance when installation prerequisites and resource budgets hold.
Our typed subset intentionally rejects some programs the broader kernel accepts.

Use isolated children for installation and fatal probes. Retain the existing x32
test's unfiltered baseline and post-install readiness marker. Use a distinctive
errno, as rust-vmm/seccompiler does with
[`FAILURE_CODE = 1000`](https://github.com/rust-vmm/seccompiler/blob/c05f20798917d8df814d1154aea3135a53f7f944/tests/integration_tests.rs#L31),
where the probe's natural result cannot be confused with that value. Ordinary
`EPERM` alone does not identify a filter as the source of a failure.

Keep encoded action words separate from observable effects: Linux caps ERRNO data
above 4095 to errno 4095; equal-precedence stacked actions select the newest
matching filter's payload. Test exact errno, termination and installation outcomes
instead of treating every action as a Boolean allow/deny result. Invalid raw
encoding cases belong in test-only fixtures, without exposing a production path
that forges a verified program.

Native AMD64 results do not qualify AARCH64. Run the relevant kernel corpus on
both supported architectures and record unsupported/skipped cases explicitly.

## Translation constraints

- libseccomp `SCMP_A0_32(SCMP_CMP_EQ, value)` narrows the constant but does not
  implement our high-word-ignoring `EqualsAny32` semantics. Its test 48 distinguishes
  `0xffffffff` from `0xffffffffffffffff`. Use masked equality with mask
  `0xffffffff` when constructing an equivalent oracle.
- rust-vmm/seccompiler masks the expected value in `MaskedEq`; mazewall rejects
  expected bits outside the mask. Compare valid shared inputs and test each API's
  rejection/normalization contract separately.
- Linux permits ERRNO(0), whereas our action API rejects zero. Distinguish our
  construction contract from kernel encoding and syscall-result semantics.
- gVisor's general classic-BPF interpreter explicitly differs from Linux on
  scratch-register initialization checks. It is not a drop-in seccomp verifier.
- Minijail's emitted-shape and negative-label tests supplement semantic checks;
  their structure assertions alone do not establish verdict correctness.
- Preserve per-file licensing and attribution for verbatim reuse. Inspected
  libseccomp C tests carry LGPL-2.1 terms; adapting the test pattern does not
  require copying their implementation.

## Suggested follow-up order

1. Close the missing-verdict loophole and the fragment-exit contract gap in their
   scoped backlog issues.
2. Add independent predicate evaluation and a deterministic shared verdict corpus.
3. Measure generated-instruction coverage and expand targeted high/low-word seeds.
4. Qualify serialized-bytecode differential checks and Linux conformance cases on
   both architectures.

These are future changes. No new semantic regression, external-suite result or
native AARCH64 qualification is claimed by this research record.
