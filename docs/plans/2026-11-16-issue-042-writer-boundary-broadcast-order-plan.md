# Writer-boundary broadcast order — diagnostic plan

> **For agentic workers:** This plan does not authorize implementation, a build, a probe, or a VM. After a later explicit operator approval, execute one task at a time. Do not commit unless the operator asks. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Determine whether one natural broadcast and one real original-Q Drop can be ordered by the already observed writer-held mutation interval, without forcing those calls to overlap.

**Architecture:** Record `netlink_broadcast_filtered` and `netlink_update_socket_mc` on the same kernel clock. Classify only two strict containment witnesses. Every other timing, including another disjoint pair, stays UNKNOWN. Do not stall either probe, add listeners, or tune a delay.

**Tech Stack:** Existing Go 1.26.10, cilium/ebpf 0.22.0, golang.org/x/sys 0.48.0, stock Debian Linux 6.12.117, the reviewed bounded BPF builder.

**Spec:** `docs/plans/2026-11-14-issue-042-stock-observer-feasibility-plan.md` lines 47–50 and `docs/plans/2026-11-08-hotspot-production-accounting-proof-plan.md`. This plan does not replace either acceptance rule. Source and evidence: `.superpowers/sdd/2026-11-14-issue-042-recovery-plan/observer-feasibility/gate4/proof-boundary-reassessment.md`, `writer-boundary-availability.md`, and `gate4-passive-mutation-vm/parent-interpretation.json`.

## Global constraints

- Stock Linux 6.12.117+ only. No kernel patch, module, instruction offset, new dependency, or production edit.
- No BPF wait, spin, sleep, yield, calibrated delay, extra listener, CPU-affinity fishing, or retry.
- Original budgets: 180-second parent and at most 30-second guest. No renewal.
- Fail closed. A missing, inlined, ambiguous, or unordered result is UNKNOWN, never zero E/D and never production PASS.
- Preserve r1/r2 overlap evidence, the passive-mutation receipts, and orphan `27c8915ef1256859f2d24821212d40d14a0dde58a4860382a7b69335e20bb8cf`.
- One fresh 540-second BPF build only after implementation approval. A failed build is consumed.
- Feature-specific runtime happens only in the isolated stock VM, and only after a separate runtime approval.
- Do not commit or publish unless the operator explicitly asks.

## What this can prove

Extracted `af_netlink.c` SHA-256 `adb62758a981ef16a9409f8df93c58fff1529687bde3a2bca2ccabaa218cba51`:

- `netlink_setsockopt` calls `netlink_table_grab`, then `netlink_update_socket_mc`, then `netlink_table_ungrab` (`1681–1684`). The passive VM showed `update_subscriptions` nested inside `update_socket_mc`. The mc interval is therefore inside the writer-held section for that observed route.
- `netlink_broadcast_filtered` enters, later calls inline `netlink_lock_table` (`1534`), traverses (`1536–1537`), unlocks (`1541`), and returns afterward. Reader admission is not a BTF function. `do_one_broadcast` is absent from the exact guest BTF.

Let `M = [mc_entry, mc_exit]` and `B = [broadcast_entry, broadcast_exit]`, same kernel clock, one correlated pair each.

| Observed relation | Classification | Why |
| --- | --- | --- |
| `mc_entry < broadcast_entry < mc_exit < broadcast_exit` | `MutationBeforeTraversal` | Broadcast entered while the writer still held the lock, so its later reader admission follows writer release. |
| `broadcast_entry < mc_entry < broadcast_exit < mc_exit` | `TraversalBeforeMutation` | Broadcast returned while the writer held the lock, so its earlier protected traversal finished before writer acquisition. |
| Both broadcast entry and exit inside `M` | `Unknown` / fault | The two source inferences contradict. Do not pick one. |
| Any other order, missing timestamp, equal boundary, extra pair, or identity mismatch | `Unknown` | Absence is not the opposite order and not permission to retry. |

This is source-assisted order, not a direct observation of inline reader admission, Q eligibility, delivery, E, D, continuity, or all-source coverage. Gate 4's overlap control remains NotExercised. This plan does not reopen it.

## Task 1: Oracle and fixtures

**Files:**
- Create: `.superpowers/sdd/2026-11-14-issue-042-recovery-plan/observer-feasibility/gate4/writer-order/order.go`
- Test: `.superpowers/sdd/2026-11-14-issue-042-recovery-plan/observer-feasibility/gate4/writer-order/order_test.go`

**Interfaces:**
- Produces: `func classifyOrder(mc, broadcast interval) OrderResult`
- `OrderResult` is a closed set: `MutationBeforeTraversal`, `TraversalBeforeMutation`, `Unknown`. It carries a reason string and does not mutate timestamps.

- [ ] **Step 1: Write the failing classifier tests**

Cover, with nonzero ordered inputs:

```go
func TestClassifyOrder(t *testing.T) {
    cases := []struct {
        name string
        mc, b interval
        want Order
    }{
        {"mutation first", interval{10, 30}, interval{20, 40}, MutationBeforeTraversal},
        {"traversal first", interval{20, 40}, interval{10, 30}, TraversalBeforeMutation},
        {"both inside is fault", interval{10, 50}, interval{20, 30}, Unknown},
        {"disjoint", interval{10, 20}, interval{30, 40}, Unknown},
        {"boundary equal", interval{10, 20}, interval{20, 30}, Unknown},
        {"broadcast surrounds mutation", interval{20, 30}, interval{10, 40}, Unknown},
    }
    for _, tc := range cases {
        t.Run(tc.name, func(t *testing.T) {
            if got := classifyOrder(tc.mc, tc.b).Order; got != tc.want {
                t.Fatalf("got %s want %s", got, tc.want)
            }
        })
    }
}
```

Classify broadcast `[10,40]`, mc `[20,30]` as `Unknown`: neither broadcast endpoint is inside the mutation interval. Broadcast `[10,40]`, mc `[20,50]` is `TraversalBeforeMutation`: broadcast exit is inside the mutation interval. These rules follow the table above, not overlap alone.

- [ ] **Step 2: Run the test and confirm it fails**

Run: `GOTOOLCHAIN=go1.26.10 go test -mod=readonly -count=1 -run TestClassifyOrder .`

Expected: FAIL because `classifyOrder` does not exist.

- [ ] **Step 3: Implement only the classifier**

Use strict `<` for every containment edge. Equal timestamps are `Unknown`. Do not clamp, widen, or invent a timestamp. Do not import socket, BPF, or time-sleep APIs into this file.

- [ ] **Step 4: Re-run the test**

Expected: PASS.

## Task 2: One combined passive probe

**Files:**
- Create: `observer-feasibility/gate4/writer-order/writer_order.bpf.c`
- Create: `observer-feasibility/gate4/writer-order/probe.go`
- Create: `observer-feasibility/gate4/writer-order/probe_test.go`
- Do not edit `gate4/cut_overlap.bpf.c`, `gate4/passive-mutation/mutation.bpf.c`, or their built objects.

**Interfaces:**
- Consumes: `classifyOrder`
- Produces: one receipt with broadcast and mc intervals, Q identity, membership before/after, and `OrderResult`

Hooks, and no others: fentry/fexit of `netlink_broadcast_filtered` and `netlink_update_socket_mc`. Reuse the existing enrollment getname object. Do not hook grab, ungrab, lock, unlock, or `do_one_broadcast`.

- [ ] **Step 1: Write pure receipt tests before BPF**

Require one correlated mc record with `group=1`, `is_new=0`, and derived `sk` equal to enrolled Q. Require one broadcast record with the seven existing arguments and a signed return separate from `sendmsg`. Reject a second mc or broadcast pair or a different socket. This probe does not hook subscriptions, so do not require a subscriptions observation. Membership readback remains userspace evidence, not a native hook.

- [ ] **Step 2: Confirm those tests fail, then add the reducers**

The reducer calls `classifyOrder` only after identity, generation, TID, and count checks pass. Any earlier failure stays `Unknown` and does not call the classifier to manufacture an order.

- [ ] **Step 3: Add the BPF program**

Reuse the checked helper-5, helper-183, and helper-185 patterns from the passive-mutation object. Capture mc arguments 0–2 only. Do not publish a signal, store a release/xchg word, or loop. Broadcast keeps its existing seven-argument capture and signed return. No new map flag and no mmap.

- [ ] **Step 4: Compile once after the pure tests pass**

Use the unchanged builder `ca59011d529281b987481b736cb21c73a005335f5c0570e154c8fbdb0507f02a`, one 540-second invocation, a new output path, and no retry. Then build one static `linux/amd64` binary. Preserve the passive-mutation r3 binary `f5e693923da4c055936cf82afffefa66cf5d29886b468408d73d5794ea4b8e29` and object `9b7341207566d21df260712b5f216aaf2d82b96b7c02dc59d6f5f6c68f4b4a45`.

## Task 3: Runtime, only after another approval

**Files:**
- Create: `observer-feasibility/gate4-writer-order-vm/`
- Do not modify `gate4-vm-r1/`, `gate4-vm-r2/`, or `gate4-passive-mutation-vm/`.

- [ ] **Step 1: Parent checks the exact binary, object, current image, and BTF**

Image `27696533ea3268f57bbcbc6179187fe5d19b7098f383175fa62789ba03a7c153`, kernel `14159c607ad9762133db41e479dafecde1d6a5fe715a8d65d292a3262e4b6bed`, BTF `4507cd5912a97650ac1abc7a609a3d535d1cf2dc851f23f9fe9ace42ee51f34b`.

- [ ] **Step 2: Run one natural attempt**

Enroll Q, join group 1, and read the full bitmap. Release one broadcast and one original-Q Drop without a rendezvous, signal, affinity requirement, or delay. Do not repeat the attempt if the order is `Unknown`.

- [ ] **Step 3: Parent classifies from the immutable receipts**

Accept `MutationBeforeTraversal` or `TraversalBeforeMutation` only when the table above matches and the marker is ready and consistent. Otherwise record `Unknown`. Either accepted order still leaves E, D, Q eligibility, continuity, and production qualification UNKNOWN.

## STOP

Stop before implementation if the operator has not approved this plan. Stop after one VM attempt regardless of result. Stop if either new hook does not attach. Do not add grab/ungrab to rescue a miss. Do not treat Gate 4 call-overlap failure as answered by this order result, or this order result as answered by a future overlap.

## Self-review

- Spec coverage: the feasibility plan's overlap gate stays closed; this is a separate source-assisted order question. Original denominators are explicitly not discharged.
- No placeholder tasks: the classifier table, hooks, hashes, and stop rules are concrete. BPF body text is not pasted because it must be derived from the already reviewed helper patterns at implementation time, not invented here.
- The two containment directions are mutually exclusive. Both-inside is a fault, not a third success.
