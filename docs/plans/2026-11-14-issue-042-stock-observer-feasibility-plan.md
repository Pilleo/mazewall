# Issue 042: stock-observer feasibility gates

**PLAN ONLY; no accepting architecture approval.** This is the next gated sequence in `.superpowers/sdd/2026-11-14-issue-042-recovery-plan/`, not another implementation queue. The parent presents Gate 1 for approval before coding. No application edits, agents, builds or native execution occur here. Future probes are throwaway diagnostics in the owned stock-kernel VM, not production hook replacements.

The November 8 production-accounting plan remains binding. The fourteen private hooks/layouts are replaceable mechanisms; original source admissions, classifications, independent identities, consumption and finality are not. Jobs 1–4 and the actual production-barrier PASS remain component evidence. Guards, Open/Fence, lifetime and all-source completeness remain unqualified; issue 042 stays OPEN.

## Source basis and measurement contract

Ledger sources: `observer-rework-source/lib/kobject_uevent.c:308–417,473–641,695–767`; `job5-netlink-source/af_netlink.c:1105–1127,1385–1564,2814`; `observer-rework-source/include/linux/netlink.h:224–236`. The installed inventory lacks both old wrapper/recipient functions. Source presence/export does not establish compiled call coverage.

Define **B** as the finite set of actual multicast-list traversals covered by one qualified epoch, including every enabled producer and deferred descendant; not observed entries, roots, allocations or received messages. For b∈B, **E(b,Q)** is actual Q list participation at traversal plus every pre-failure eligibility predicate: exclude socket/port, group bounds/bit, namespace equality or all applicable cross-netns permission/mapping checks. `D=Σ E(b,Q)` is UNKNOWN until B and traversal-time membership are independently established. Broadcast entry precedes table locking; aggregate return is never a denominator or Q outcome.

Require `D=Queued+Overrun+FilteredOrFailed`, with independently observed, uniquely correlated actual Q outcomes. Missing/duplicate outcomes, ambiguous nesting or unexplained residuals reject; subtraction cannot manufacture FilteredOrFailed. Inherited clone failure still admits eligible Q before overrun. Filters cannot shrink D. Prove continuous filter absence or independently observe classification. Preserve rejection of nonzero overrun/filter/failure even when arithmetic balances.

Pinned FD/cookie alone does not prove continuous membership. Alias access, drop/rejoin, filter replacement, namespace/domain changes and Cut ordering require a continuous exclusion/serialization argument: **UNKNOWN**. Same-netns/group-1/LISTEN_ALL_NSID-off restrictions remain; unsupported domains reject. Unresolved Cut overlap is UNKNOWN, not zero admission.

## Old facts → replacement obligations

`validateKobjectCounters` currently requires every stage's `attempted=succeeded+failed+skipped+active`, zero failed/active, roots started=returned, and zero recipient failures. Nothing below relaxes those rejections:

| Old private fact | Required replacement evidence/status |
| --- | --- |
| Env/Relay attempts, root starts/returns, active frames | Independently admitted root entry and actual-return pairs; roots=Env+Relay; no unresolved root/deferred work. Coverage UNKNOWN. |
| Env succeeded/skipped/failed | Preserve suppression/kset-filter/no-subsystem, construction failure and downstream classifications separately. Return zero alone cannot distinguish suppression from masked netlink failure. Exact branch classification UNKNOWN. |
| Broadcast attempted/succeeded/skipped/failed/active | Replace wrapper-stage mechanism with independently classified routing decisions: suppressed/no-listener, allocation failure, tagged/untagged traversals and completion. Untagged retries allocation across namespaces and can broadcast repeatedly. Wrapper count is not D; unobserved routing/no-listener classification is concretely UNKNOWN. |
| Allocation attempts/success/failure/active | Independent `alloc_uevent_skb` calls and pointer returns, including retries; relay copy/size/capability failures separately. Neither traversal nor root counts substitutes. Coverage UNKNOWN. |
| Relay success/skipped/failure | Capability/size/copy/prebroadcast rejection and actual return; masked broadcast returns cannot erase failure. Exact classification UNKNOWN. |
| Recipient admission/queue/overrun/filter-failure | Independent E and correlated terminal evidence above; no residual classification. UNKNOWN. |
| Faults, overflow, unregistered producers, nesting/identity errors | Sticky rejection with original errno/first cause; unknown producer rejects source completeness, never silently excluded. |

## Gate 1 — delivery-independent original-Q enrollment ONLY

**Candidate, not assumption:** `netlink_getname(struct socket *sock, struct sockaddr *addr, int peer)` obtains `sock->sk`. Source returns **sizeof(sockaddr_nl)**, not necessarily zero; `.getname` references it. First establish actual guest BTF prototype/availability and source-match the socket syscall dispatch/return semantics. Missing callback/BTF/prototype/attachability stops this route **without fallback**.

**Minimal files:** future ledger-local `observer-feasibility/gate1/enroll.go`, `enroll_test.go`, minimal `enroll.bpf.c` for callback records, and immutable `receipts/`. Use existing pinned build tooling/dependencies. No production patches/generated assets, broadcast/enqueue/overrun hooks, Cut or uevent stimulus.

Before arming, acquire and retain the original bound AF_NETLINK FD, fresh generation, SO_COOKIE, local port, protocol and independently identified socket netns; record owned process/thread identity and exclusive FD/alias handling. Validate cookie and kernel field layouts from matched evidence, not guessed offsets. Arm only the controlled thread/invocation. Paired callback entry/actual return records `socket*`, `sock*`, cookie/port/protocol/netns and output address under that generation. Compare them with the preacquired FD identity and the userspace getsockname result. Userspace syscall success and internal positive address-length return are distinct observations.

**Matrix/evidence:** one original-FD enrollment with zero required deliveries; one different owned FD must be observed and correctly rejected for Q enrollment. That expected rejection is a successful negative control, not probe failure; accepting/confusing that FD is failure. Wrong independently acquired identity must reject. Logical generation is application/map/receipt metadata, not a kernel socket property: its rejection is a clearly labelled MODEL/expected-receipt comparison against a separate copy, never a fabricated native generation observation or mutation of the original receipt. Each invocation has its own immutable receipt; controls are not retries. Retain BTF/source identities, attachment/map IDs, arguments, returned length/address, independent identities, mismatches and actual operation returns.

**Barrier/STOP:** no publication before actual getsockname/callback returns and identity recheck. Keep original FD/resources until actual borrowers/operations return; unknown callback retirement retains ownership. Stop on other-FD confusion, duplicate/missing return, cookie/domain mismatch, permissions, unavailable fields, deadline or uncertain ownership. A recorded kernel pointer is diagnostic data, **not authority**; coverage/lifetime/nonwrap remain unqualified.

## Later gates — separately approved small batches

1. **Broadcast arguments/returns:** separate diagnostic source/control test and receipts; use independently enrolled Q, inspect actual `netlink_broadcast_filtered` prototype/attachment, then one bounded stimulus. Record sender/protocol/netns, excluded port/group/filter, invocation/nesting and actual return. Barrier: matching returns before snapshot. STOP on missing attachment/arguments/return or ambiguity. No D or terminal inference.
2. **Negative destination domain:** separate control/receipt; independently establish destination exclusion, not absent delivery. Barrier: identity/domain checks bracket the call, without claiming continuity. STOP if exclusion cannot be established; report UNKNOWN. No aggregate-return inference.
3. **Cut overlap:** separate control/receipt; deliberately overlap broadcast and real Cut. Preserve actual operation returns; unresolved traversal ordering must produce UNKNOWN. STOP if control did not overlap or falsely accepts. No inferred zero membership.
4. **Terminal/inherited-failure feasibility:** separate correlation diagnostic/tests/receipts; only then examine enqueue/overrun actual returns, duplicates, filters and inherited clone failure. Source/build coverage, continuous domain and failure classification still need independent qualification. STOP on any unexplained terminal/residual. No accepting guards.

Later gates use separate ledger-local `observer-feasibility/gateN/` diagnostic sources/tests and `receipts/`; each batch requires approval. Preserve original absolute budgets, immutable first receipts, actual-return ownership, no retries and orphan/failure preservation. Any builder retains 540 seconds. No instruction offsets, custom kernel/module, new production dependencies or public API changes.

## Unchanged acceptance/finality

All possible-CPU selected rows retain `A=S+F; R≤S; T=R; Lost=A−R` and terminal classification. Trace retains `A=S+OutputFailed+RejectedSize`, `Received=HandedOff+RejectedRecord+HandoffAborted`, `Received≤S`, and emission loss including undelivered submissions. Report/PID/poll/prune/async/store each require independent admissions/terminals; enrichment requires successful event/terminal writes; wire requires matching prefix records/bytes/hash, unique summary/EOF; retention requires admitted=kept+discarded pairs/recoveries.

Positive exercise and triggered rejecting controls, checked arithmetic, independent bindings, all-CPU/hotplug, compiled callers/clones/inlining, miss/recursion/nonwrap and uninterrupted observer lifetime remain mandatory. Deferred work requires linked admissions/terminals; empty roots, joined workers and membarrier cannot substitute. Actual Cut/Fence precedes covered final sampling on original resources; certification follows strict retirement. None of these gates grants that certification.
