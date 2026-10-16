# Reviewed Detekt warning inventories

Each `.txt` contains the complete normalized warning multiset from the previously reviewed
reports. The original reports were verified against all six SHA-256 fingerprints formerly in
`build.gradle.kts` before converting this inventory. No newly reported finding was adopted.

`detektWarningRatchet` ignores source line/column movement and XML whitespace, retains complete
multiline messages and duplicate findings, and prints every added or removed finding. Additions
fail verification. Reductions remain visible against the reviewed inventory; a lower total does
not authorize a different warning. The two existing platform reductions are deliberately retained
as reductions rather than absorbed into a new budget.

Update these files only from independently reviewed reports. A compatibility exception belongs
on the exact declaration with a rationale and a reviewed suppression entry, never in a larger
per-module warning allowance.

The review-hardening native/helper exceptions are individually documented in
[native-helper-exceptions.md](../native-helper-exceptions.md); their exact signatures are
manually selected in module baselines. These do not refresh or enlarge the warning inventories.
