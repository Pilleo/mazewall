# Clean Tier E low32 checkpoint diagnostic — 2026-09-27

**`INCOMPLETE`, zero of three predeclared instrumented control/noisy pairs.**
This separate, opt-in diagnostic used clean `bffaccdf`, one pinned rootful
Docker image/native agent, CPUs `0,1` for the target and `2` for the unrelated
noise loop. Full and unique modes retained the original 10,000 operations at
1,000/s and exact-oracle gates. The schedule was
`control,noisy | noisy,control | control,noisy`; the first incomplete arm
stopped it. It never replaces or upgrades the failed five-pair **unaudited**
qualification. `policy_eligibility=false` and `REJECT_DEFAULT_PROMOTION` remain.

The first no-noise control matched 10,000/10,000 in both modes; each had 10,000
raw target entries, 10,000 exact descriptor submissions and 30,000 kernel
exact/ring-ready records. Its source fingerprint, image ID, native agent and
target CPUs matched the subsequent noisy arm. The first noisy target failed
on the **full-stream fork**, before unique mode ran: the raw audit counted
10,000/10,000 target `sys_enter` events on CPU 1, but descriptors `4742`–`4749`
each had **zero** low32 checkpoint counts and zero exact admission counts.
Only 9,992 target operations were session-matched and submitted, with 29,984
kernel exact/ring-ready records and zero reported ring loss. The low32 and
full-width handler aggregates were both 10,048 and contain unrelated opens;
the raw denominator and per-descriptor samples, not aggregate subtraction,
establish this boundary. Argument-read failures were zero. A three-second
unrelated noise sample ran while the first full-stream PID was alive, but
there is no timestamped proof that the eight missing IDs overlapped the loop.

The checkpoint increments **after** the main BPF program's argument decode
and descriptor-map lookup. A per-descriptor zero therefore localizes the
absence to **at or before this checkpoint**; it does not prove that the main
program was never invoked, nor identify an early argument or lookup branch.
No core BPF fix is justified from the remaining aggregate counters alone.
The runner `tier-e-trustworthy-dd5263e3-f611-4e89-81f2-6e289ff4f723-runner`
was verified stopped with exit 1 and its matching `-artifacts` volume retained
for exact recovery. Preserve both and the raw `/tmp/tier-e-m3-low32-clean-1790536502*`
artifacts; no retry was made after the failed target.

The committed Gradle 9.6.1 wrapper failed a nested TestKit worker-classpath
preflight **before starting any target**. The already installed Gradle 9.8.0
passed the same clean-source preflight. The decision-only five-pair controller
then correctly rejected a three-pair **audited** profile before launch. Both
attempts and their logs are retained. The same pinned source/package/profile
identities were used for an explicit serial control/noisy schedule under 9.8.0;
`manifest.json` and `raw-audit.tar.gz` retain the plan, profile checksums,
per-arm receipts, child logs, recovery record and preflight provenance.
`SHA256SUMS` seals the archive and small source receipts. The pre-admission
[backlog issue](../../backlog/issue-20260920-232238-326-diagnose-intermittent-tier-e-pre-admission-observa.md)
remains open.
