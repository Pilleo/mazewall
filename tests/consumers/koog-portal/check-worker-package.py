#!/usr/bin/env python3
"""Check actual generated opaque packaging, including worker dependency isolation."""

import hashlib
import io
import sys
import zipfile

host_path = sys.argv[1]
fixture = len(sys.argv) > 2 and sys.argv[2] == "--native-fixture"
with zipfile.ZipFile(host_path) as host:
    services = host.read("META-INF/mazewall/portal/services.index").decode().splitlines()
    assert services == (["bounded-text", "test-denied"] if fixture else ["bounded-text"]), services
    metadata = dict(
        line.split("=", 1)
        for line in host.read("META-INF/mazewall/portal/worker-artifacts/bounded-text-worker").decode().splitlines()
    )
    assert metadata["format"] == "2", metadata
    bundle = host.read(metadata["resource"])
    assert hashlib.sha256(bundle).hexdigest() == metadata["sha256"]
    assert not any("/worker/" in name and name.endswith(".class") for name in host.namelist())
    with zipfile.ZipFile(io.BytesIO(bundle)) as worker:
        classes = set()
        for name in worker.namelist():
            assert name.startswith("lib/") and name.endswith(".jar"), name
            with zipfile.ZipFile(io.BytesIO(worker.read(name))) as jar:
                classes.update(jar.namelist())
        assert not any(name.startswith(("ai/koog/", "org/springframework/")) for name in classes)
        assert "io/mazewall/demo/koog/worker/AsciiTextTransformer.class" in classes
        assert "io/mazewall/demo/koog/TextTransformerServicePortalDispatcher.class" in classes
        denied = "io/mazewall/consumer/koog/worker/DeniedResourceProbe.class"
        assert (denied in classes) == fixture
        assert "io/mazewall/demo/koog/TextTools.class" not in classes
        print(f"OPAQUE_WORKER_PACKAGE_OK fixture={fixture} jars={len(worker.namelist())} sha256={metadata['sha256']}")
