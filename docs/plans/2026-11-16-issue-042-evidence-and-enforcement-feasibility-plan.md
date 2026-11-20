# Issue 042 evidence and enforcement feasibility plan

> **Execution gate:** Plan only. Do not start acquisition, implementation, builds, probes or VMs merely because this file exists. Before any approved execution beyond preparation, merge the current remote `master` into this checkout and reconcile related conflicts as Task 0 requires. No worktrees or publishing. Preserve user work and original acceptance criteria.

**Goal:** Decide whether authenticated exact-build evidence, an independently enforceable Q ownership contract, and complete upstream decision observations can support issue 042 on stock Linux without weakening its acceptance criteria.

**Architecture:** Three feasibility gates produce evidence and contracts, not an accepting production observer. Exact-build analysis establishes candidate compiled coverage; a separate enforcement contract supplies runtime invariants; an observability matrix must account independently for every admission and routing decision. Failure at any gate produces a specific blocked verdict, not another timing experiment.

**Technology:** Existing repository tools and installed offline ELF/BTF/DWARF/package-verification tools. Matching Debian kernel debug/build material is research input, not a new production dependency. No kernel modifications or new package installation without separate approval.

**Binding references:**

- [Original production acceptance](2026-11-08-hotspot-production-accounting-proof-plan.md).
- [Stock-observer feasibility scope](2026-11-14-issue-042-stock-observer-feasibility-plan.md).
- [Production completion sequence](2026-11-14-issue-042-production-completion-plan.md).
- [Proof-gap verdict](../../.superpowers/sdd/2026-11-14-issue-042-recovery-plan/observer-feasibility/production-proof-gap-verdict.md).
- [Admission/routing audit](../../.superpowers/sdd/2026-11-14-issue-042-recovery-plan/observer-feasibility/admission-routing-observability-audit.md).

## Non-negotiable limits

- Original denominators, independent identities/admissions/terminals, absolute deadlines, immutable first receipts and strict finality remain binding. UNKNOWN is never zero.
- Stock Linux 6.12+ remains the platform requirement. Evidence for Debian 6.12.117-1 qualifies only that exact build, not every supported kernel.
- No patches/custom kernel/modules, instruction-offset probes, BPF waits, extra-listener experiments, timing tuning, retry campaigns, new production dependencies or breaking API changes.
- No production activation, terminal probe or native experiment is authorized by this feasibility plan. Later runtime work needs its own reviewed scope and explicit approval.
- Preserve all prior successful and failed diagnostic artifacts, consumed approvals, original overlap NotExercised results and orphan `27c8915ef1256859f2d24821212d40d14a0dde58a4860382a7b69335e20bb8cf`.
- Do not modify or manage `GITHUB_TOKEN`. Use existing operator-managed repository access.
- Roles when separately authorized: parent owns merge, provenance, acceptance and runtime; planner designs; Hydrogen implements while available, configured ChatGPT implementer is the operator-authorized quota fallback; Sol independently reviews. No concurrent writers in the same checkout.

## Task 0 — merge remote master before execution

**Owner:** Parent only. **Deliverables:** recovery manifest, integration/conflict report and verified post-merge baseline. No feasibility implementation before this gate passes.

Planning-time observations, not current execution authority: branch `feat/kubescape-agentless-hotspot`, HEAD `b2c9b335720fd0491a8d86917a3b0593b818a4ec`, cached `origin/master` `b41db116e8c4314cfac91dbbb5c03b7c7da8af46`. `origin` is `git@github.com:Pilleo/mazewall.git`. Many staged/unstaged/untracked changes exist; no unmerged entries were observed. Nothing was fetched or merged while writing this plan. Re-read all of this at execution time.

- [ ] Confirm all agents/workers touching this checkout are idle. Re-read current root/module instructions; inspect branch, HEAD, index, worktree, untracked/ignored evidence, remote configuration and any pre-existing merge/rebase. Stop if another operation is active or the intended remote is ambiguous.
- [ ] Create a uniquely named recovery snapshot under `/tmp/opencode`, with recorded HEAD, binary staged and unstaged diffs, NUL-safe path inventory, executable/symlink metadata, and hashes. Back up relevant untracked and ignored evidence separately, including `.superpowers/sdd/`, preserved artifacts and referenced external `/tmp/opencode` fixtures. Assess size/capacity before archiving; do not indiscriminately archive build caches or secrets. Verify the archive contents and a representative restoration into a temporary ordinary directory, not a Git worktree. If any important material cannot be protected, stop before mutation.
- [ ] Preserve the original index/worktree with a named recoverable stash including untracked files (`git stash push --include-untracked -m <unique-label>`). Record its exact object ID and keep it. Ignored evidence is not included by that command and must already be backed up. Do not use `stash pop`, `stash --all`, resets, force checkout or recursive deletes.
- [ ] Fetch explicitly: `git fetch origin master`. Record the fetched `origin/master` SHA and inspect the related remote changes before integrating. Do not treat the planning-time cached SHA as current remote state.
- [ ] Merge, not rebase: `git merge --no-ff --no-commit origin/master`. If already up to date, record that fact. If local uncommitted files unexpectedly block the merge, stop and inspect; do not discard them. The user's requested merge permits its necessary merge commit, not arbitrary WIP commits or publication.
- [ ] For conflicts, inspect base/local/remote intent and connected callers/tests/docs. Maintain a path-by-path resolution ledger. Preserve both sides' substantive work; no blanket ours/theirs or assumption that related remote edits are obsolete. Remote fixes may supersede local designs, but never discard local receipts or call UNKNOWN evidence a PASS. Ask only if competing behaviors require a substantive operator choice.
- [ ] Check the merge result and create the merge commit if needed. Then restore the recorded stash with `git stash apply --index <recorded-object-id>`. Keep the stash even after success. Restoration conflicts are a second reconciliation step, not permission to discard the original index or stage every WIP file. Verify original staged/unstaged distinctions where conflict resolution allows them; document any unavoidable differences.
- [ ] Verify saved artifacts against their hashes and reconcile every changed source path against the recovery snapshot and remote delta. Record unmerged entries, if any; unresolved entries block further work. Stage only genuine merge resolutions during the merge, and never use `git add .` to absorb unrelated WIP.
- [ ] Re-read issue 042, relevant plans, ownership/enforcement architecture and current module paths after integration. Refresh the admission/routing gap matrix for remote changes. Historical evidence remains historical and cannot qualify newly merged code automatically. Amend this plan if the merge changes scope or proof assumptions.
- [ ] Run conflict-focused tests for executable changes and required repository gates for the integrated revision. Run `git diff --check` and backlog validation; the full repository quality checkpoint is `./gradlew build` when executable integration changes require it. An environmental failure must be disclosed and must not be reported as green. Do not start feasibility implementation while unresolved conflicts or material unvalidated integration concerns remain.

## Task 1 — authenticate and bind exact-build evidence

**Output directory:** `.superpowers/sdd/2026-11-14-issue-042-recovery-plan/observer-feasibility/evidence-enforcement/` (create only during approved execution).

**Deliverables:** `acquisition-manifest.json`, `exact-build-coverage.md`, small offline extracts. Preserve downloaded bytes under a unique `/tmp/opencode` directory, with hashes and durable location references. Do not overwrite existing evidence.

- [ ] Inventory already retained files and installed tools. Reuse verified bytes; identify exactly which kernel/module debug files and build records are missing before network access. Do not install packages or change global APT sources/keyrings/configuration.
- [ ] Locate matching Debian debug/build artifacts for source package `linux` version `6.12.117-1` and binary release `6.12.117+deb13-amd64`. Determine actual package names/versions from authenticated metadata; do not guess a debug package from the release string.
- [ ] Verify a trust chain from an independently trusted Debian archive key to signed repository metadata, then package-index hashes, then downloaded package hashes. Preserve signatures, verification output, key identity, origin and retrieval time. Treat key retrieval from the same untrusted endpoint as insufficient bootstrapping. An expired snapshot or unavailable historical artifact is a blocker requiring an explicit decision, not a reason to disable authentication or freshness checks silently.
- [ ] Extract packages offline without installation or maintainer scripts. Bind the unstripped kernel/debug data by build ID, package identity and BTF to the retained kernel. Record any reproducibility/signature limits separately; a build ID or BTF match alone is not authentication.

Retained reference values to recheck, not accept on spelling alone:

| Material | Identity |
| --- | --- |
| VM image | `sha256:27696533ea3268f57bbcbc6179187fe5d19b7098f383175fa62789ba03a7c153` |
| Boot kernel SHA-256 | `14159c607ad9762133db41e479dafecde1d6a5fe715a8d65d292a3262e4b6bed` |
| Retained decompressed ELF SHA-256 | `631417d33453047e1beeac2a5952ac57b90b482f8f49b995fd91ad13503f60c1` |
| ELF build ID | `b1d7a9114053a488ef493f85f85e9fc75ba9f632` |
| Guest BTF SHA-256 | `4507cd5912a97650ac1abc7a609a3d535d1cf2dc851f23f9fe9ace42ee51f34b` |

- [ ] Map relevant source decisions to compiled bodies, inline instances, clones, direct/tail calls and indirect callback targets. Include applicable modules/configuration and generated/transitive source provenance. Enumerate missing material explicitly; do not declare an exhaustive caller graph from BTF names or unlabelled disassembly.
- [ ] Distinguish three outcomes per boundary: exact offline coverage supported; additional evidence needed; unavailable under current constraints. Missing original function boundaries do not become attachable merely because DWARF locates their inline instructions. Instruction-offset probing remains prohibited.

**Gate:** Evidence must be authenticated and matched before it can support a coverage argument. If it is not obtainable, stop with the exact missing artifact/trust link. Even a passed evidence gate does not establish runtime admissions.

## Task 2 — design independently enforceable Q authority

**Deliverable:** `q-ownership-contract.md`; design only, no new supervisor/broker implementation.

- [ ] Define the trusted owner/process boundary and the untrusted actors, including genuine arbitrary-code execution where applicable. Record relevant external privileged actors, namespaces, file credentials and kernel authority. A trusted thread in an untrusted address space, nonroot status or a cgroup label alone is not enforcement.
- [ ] Define the epoch state sequence: Q created and independently bound → configuration fixed → baseline sealed → observation epoch → controlled Cut → actual borrower completion/final sampling → qualified retirement. Every state transition needs an authority and observable receipt; uncertain transitions invalidate certification.
- [ ] Fill the enforcement matrix below with the actual existing mechanism and verification method. A row without a defensible mechanism is a blocker, not a future assumption.

| State/authority | Paths that must be prevented or completely observed |
| --- | --- |
| Descriptor and socket ownership | fork/exec inheritance, dup, SCM_RIGHTS, pidfd/proc access, registered-file references, close/reuse, process-memory/control access |
| Membership/list participation | initial bind/autobind/connect, successful/failed insert, MC/subscription changes, release, kernel multicast clearing/group resizing and deferred destruction |
| Filters/options | classic/eBPF attach/replace/detach/lock, relevant SOL_SOCKET and SOL_NETLINK options, LISTEN_ALL_NSID |
| Domain and credentials | socket net/protocol/port invariants, namespace lifetime and mappings, capability/file-credential decisions and applicable privileged interference |
| Cut/finality | unique authorized mutation, all outstanding actual returns, unresolved concurrent state, all-CPU observer lifetime and retirement |

- [ ] Separate what can be frozen from what must remain dynamically observed. Derive Q eligibility anew from each broadcast's exclusion socket/port/group/filter and sender domain; immutable Q state does not make sender state constant.
- [ ] Check compatibility with the original acceptance/threat model and current public interfaces. New process architecture, exclusions of privileged actors, dependencies or API changes require explicit approval before implementation. Scope restrictions must be explicit eligibility rules, not retroactive claims about the historical runs.
- [ ] Specify meaningful negative tests for each proposed enforcement guarantee, including aliases and pending ownership. These are a future validation design, not permission to run them now.

**Gate:** An enforceable Q contract must determine all runtime state needed for E, or identify the exact unresolved state transition. Controlling Q alone does not control unrelated kernel producers.

## Task 3 — complete admission and upstream decision matrix

**Deliverables:** `observability-matrix.md`, `feasibility-decision.md`.

- [ ] Define independent root and traversal identities, including no-broadcast cases and deferred descendants. Never use SEQNUM, delivered count or terminal counts alone as original admissions. Define how unresolved executions invalidate the epoch without disappearing from B.
- [ ] For each row below, record the source decision, compiled-coverage evidence, actual observable arguments/returns or enforced invariant, correlation identity/lifetime, independent terminal classification and a rejecting-control design. Separate confirmed native evidence, offline evidence and unverified proposals.

| Required fact | Mandatory branches/conditions |
| --- | --- |
| Every actual traversal B | all covered bodies/callers, original root/relay/namespace iteration, incomplete or deferred executions through Cut |
| Independent E(b,Q) | actual list participation, group range/bit, excluded socket/port, protocol/net, cross-net mappings/capabilities where permitted; admission before inherited failure/filter outcomes |
| Env suppression/construction | suppression/no-kset/no-subsystem, kset filter/name/uevent callbacks, construction/size failures, identity before SEQNUM |
| Namespace routing | tagged/untagged choice, namespace-list baseline/lifecycle, each iteration, no-listener actual decision |
| Allocation | every attempt, actual NULL/success, retries and reused skb, original producer/iteration correlation |
| Relay | protocol/message admission, capability/size/format/copy rejection, per-message identity and masked broadcast errors |
| Independent Q terminals | enqueue, overrun, filters and inherited clone failure correlated to the same independently admitted E; residual subtraction is not classification |

- [ ] Explicitly test proposed arguments against counterexamples: an eligible Q fails before delivery; another recipient makes aggregate return successful; a root returns zero without broadcast; bind/release bypasses the MC ledger; an unobserved alias mutates Q; a helper is inlined or a callback target escapes coverage. If the architecture cannot distinguish a counterexample, mark that row blocked.
- [ ] State remaining observer-lifetime, all-CPU/hotplug, miss/recursion/nonwrap and strict finality obligations. Do not silently expand a narrow source argument into a production qualification claim.
- [ ] Conclude with exactly one of: **feasible accepting contract specified**, **specific additional evidence required**, or **blocked under current constraints**. A feasible contract must cover both independent admission and upstream routing; debug evidence or terminals alone cannot pass it.

## Task 4 — independent review and execution decision

- [ ] Sol reviews the evidence chain, enforcement contract and each matrix row against the original acceptance criteria. No duplicate probe campaign or automatically repeated reviews.
- [ ] If any row remains blocked, report the smallest explicit operator choice: obtain named missing material, approve a justified architecture/platform/threat-model change, or leave promotion blocked. Do not weaken acceptance or silently treat an unobservable branch as excluded.
- [ ] Only after a defensible contract passes review, write a separately scoped implementation/test plan using the merged checkout's actual files and interfaces. Any later VM diagnostic requires explicit approval, exact delivered bytes/current-image checks, original absolute budgets, immutable receipts and actual-return ownership.

## Verification and completion criteria

This plan is complete only when the file exists and its local references and whitespace are checked. Execution of this plan is complete only when Task 0 has passed and the evidence/contract/matrix yield a reviewed decision—not when a debug package downloads or a hook attaches.

For documentation-only changes use scoped whitespace/link checks and `./scripts/adkw check-backlog`. After the required merge, discover current verification commands from the integrated instructions; do not assume historical paths or removed tooling still apply. Executable changes need focused regression checks and the repository-required final build. Pure model checks cannot qualify native behavior.

Keep issue 042 OPEN unless the original production conjunction and full required campaign actually pass. This feasibility project authorizes no production certificate, rollout, commit beyond the requested merge, or publication.
