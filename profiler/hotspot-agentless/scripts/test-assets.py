#!/usr/bin/env python3
"""Exercise packaging against a complete local OCI descriptor graph."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("assets", Path(__file__).with_name("assets.py"))
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)


class AssetsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = self.root / "source"
        self.store.joinpath("blobs/sha256").mkdir(parents=True)
        self.helper = self.root / "helper"
        self.helper.write_bytes(b"ELF fixture")
        self.helper.chmod(0o755)
        self.layer = self.push(b"native BPF fixture", "application/vnd.gadget.ebpf.program.v1+binary")
        config = self.push(b"{}", "application/vnd.gadget.config.v1+json")
        manifest = self.push(json.dumps({"schemaVersion": 2, "config": config, "layers": [self.layer]}).encode(), "application/vnd.oci.image.manifest.v1+json")
        manifest["platform"] = {"os": "linux", "architecture": "amd64"}
        self.top = self.push(json.dumps({"schemaVersion": 2, "manifests": [manifest]}).encode(), "application/vnd.oci.image.index.v1+json")
        self.store.joinpath("index.json").write_text(json.dumps({"schemaVersion": 2, "manifests": [self.top]}))
        self.store.joinpath("oci-layout").write_text('{"imageLayoutVersion":"1.0.0"}')
        self.bundle = self.root / "bundle"

    def push(self, data, media_type):
        digest = hashlib.sha256(data).hexdigest()
        self.store.joinpath("blobs/sha256", digest).write_bytes(data)
        return {"mediaType": media_type, "digest": "sha256:" + digest, "size": len(data)}

    def pack(self):
        assets.pack(self.helper, self.store, "ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@" + self.top["digest"], self.bundle)

    def test_complete_graph_installs_into_new_directory(self):
        self.pack()
        manifest = assets.verify(self.bundle)
        self.assertEqual(manifest["gadget_digest"], self.top["digest"])
        self.assertTrue(self.bundle.joinpath("oci/blobs/sha256", self.layer["digest"].split(":")[1]).is_file())
        destination = self.root / "installed"
        assets.install(self.bundle, destination)
        self.assertEqual(assets.verify(destination), manifest)
        with self.assertRaises(FileExistsError):
            assets.install(self.bundle, destination)

    def test_missing_or_corrupt_transitive_bpf_layer_refuses_pack(self):
        layer = self.store.joinpath("blobs/sha256", self.layer["digest"].split(":")[1])
        layer.write_bytes(b"corrupt")
        with self.assertRaises(ValueError):
            self.pack()
        self.assertFalse(self.bundle.exists())
        layer.unlink()
        with self.assertRaises(FileNotFoundError):
            self.pack()

    def test_tampering_refuses_install_and_does_not_publish_destination(self):
        self.pack()
        self.bundle.joinpath("mw-hotspot-collector").write_bytes(b"tampered")
        with self.assertRaises(ValueError):
            assets.install(self.bundle, self.root / "installed")
        self.assertFalse(self.root.joinpath("installed").exists())

    def test_unsupported_version_and_unlisted_required_blob_refused(self):
        self.pack()
        path = self.bundle / "manifest.json"
        manifest = json.loads(path.read_text())
        manifest["capture_abi"] = 1
        path.write_text(json.dumps(manifest))
        with self.assertRaises(ValueError):
            assets.verify(self.bundle)
        manifest["capture_abi"] = 2
        del manifest["files"]["oci/blobs/sha256/" + self.layer["digest"].split(":")[1]]
        path.write_text(json.dumps(manifest))
        with self.assertRaises(ValueError):
            assets.verify(self.bundle)

    def test_symbolic_helper_refused(self):
        helper = self.root / "linked"
        helper.symlink_to(self.helper)
        with self.assertRaises(ValueError):
            assets.pack(helper, self.store, "ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@" + self.top["digest"], self.bundle)


if __name__ == "__main__":
    unittest.main()
