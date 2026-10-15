# Regression inputs

`phase-replay/` contains the six original `phase/{full,unique}-{1,2,3}.log`
members of `tier-e-separated-phase-gate-20260927/raw-receipts.tar.gz`, renamed
to `.txt`. `node-noise/{control,noisy}/` contains only the terminal, manifest
and result rows read by the existing qualification test, from the
`unaudited_no_noise_control/target` and `default_noisy_incomplete/target` members
of `tier-e-clean-node-noise-20260927/raw-diagnostics.tar.gz`.

These 12 files retain their exact original bytes (95,069 bytes total). They
preserve the existing replay/refusal regressions without depending on the large
local benchmark bundles or a tar subprocess. They are historical test inputs,
not qualification of the current source revision.
