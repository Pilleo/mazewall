#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
builder=$(python3 -c 'import json;print(json.load(open("dependencies.lock.json"))["images"]["gadget_builder"])')
source_dir=$(python3 - <<'PY'
import hashlib,pathlib,shutil,subprocess,json
root=pathlib.Path.cwd();patch=root/"patches/faults/drop-marked-event.patch"
h=hashlib.sha256(patch.read_bytes())
for p in sorted((root/"gadgets/openat-stack").iterdir()):
 if p.is_file():h.update(p.name.encode()+b"\0"+p.read_bytes())
sha=h.hexdigest();dest=root/"build"/("loss-gadget-"+sha)
if not dest.exists():
 shutil.copytree(root/"gadgets/openat-stack",dest)
 subprocess.run(["patch","--batch","--forward","--fuzz=0","-p1","-i",str(patch)],cwd=dest,check=True,stdout=subprocess.DEVNULL)
 (dest/"fault-manifest.json").write_text(json.dumps({"fault":"drop_marked_event_sequence_42","source_sha256":sha},indent=2)+"\n")
print(dest)
PY
)
mkdir -p build/loss-gadget
go run ./cmd/mw-gadget-build --builder-image "$builder" --builder-image-pull always \
    --output "$root/build/loss-gadget" --tag mw-hotspot-openat-loss-test:v1 "$source_dir"
python3 - <<'PY'
import json,pathlib
index=json.loads((pathlib.Path.home()/".ig/oci-store/index.json").read_text())
m=next(m for m in index["manifests"] if m.get("annotations",{}).get("org.opencontainers.image.ref.name","").endswith("/mw-hotspot-openat-loss-test:v1"))
pathlib.Path("build/loss-gadget-image.ref").write_text("ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat-loss-test@"+m["digest"]+"\n")
PY
