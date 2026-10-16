# Tier E source-state and process-incarnation follow-up, 2026-09-23

The four split-topology benchmark runners now hash every tracked or untracked
runtime-input file under their declared source roots before measured forks,
rehash after completion, and fail a terminal result if the revision or
content digest changed. Image and native/JAR identities remain separate.
`rootful-controls.tar.zst` retains a dictionary-hybrid 72-path coverage smoke
and a matched workload smoke with `source_state_unchanged=true`. The source
fingerprint tool has a synthetic-repository test proving that tracked and
untracked source edits change its digest while documentation-only edits do not.
`clean-source-postcommit.tar.zst` repeats the 72-path hybrid smoke after
commit `4eff2777`: both arms passed with `input_dirty_count=0`, 921 hashed
runtime-input files, and `source_state_unchanged=true`.

The same archive also retains a **simulated** active-session process-incarnation
change under registered marker v2. The target was held alive through the
collector watch interval; the terminal report says
`PROCESS_IDENTITY_REUSED`, `INCOMPLETE`, and `lifecycle_validity=false`.
This is a fault-injection fixture, not host PID reuse.

The bounded, read-only host probe in
`../tier-e-real-pid-reuse-probe-20260923.json` observed **no** replacement
of its original host PID in 256 successive process lifetimes. The host
`pid_max` was 4,194,304. No host PID allocation controls were modified.
`host_pid_reuse_verdict=UNVERIFIED`: this cannot qualify the real host-PID-reuse
claim. Running more unrelated namespace-PID reuse is not a substitute.

A later [disposable-VM run](../tier-e-real-host-pid-reuse-20260924/README.md)
observed a genuinely recycled guest-host PID and the collector's rejection;
the original normal-host probe remains unverified as recorded here.

`SHA256SUMS` binds the archives, probe JSON, and starting source revision.
Every artifact remains `policy_eligibility=false`.
