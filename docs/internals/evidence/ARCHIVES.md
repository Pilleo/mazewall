# Local benchmark archives

Historical raw benchmark bundles are local data, not published artifacts. The
79 `.tar.gz`/`.tar.zst` payloads have been moved out of the Git checkout. Their
original reports, checksum ledgers, manifests and member indexes remain here.

The cleanup's local copy is in `jseccomp-benchmark-archives/`, alongside the main
checkout. That directory mirrors the original repository-relative archive paths
under `docs/internals/evidence/`. Its local inventory records lengths and SHA-256
hashes; all 79 files total 235,535,341 bytes.

If an old local experiment is needed, copy its archive back to its original path
and use its saved instructions. Restored raw bundles are ignored by Git. Normal
builds use only the small checked-in regression inputs they need; they do not
require these bundles, a downloader, or access to an archive service.
