package wire

import (
	"encoding/json"
	"testing"
)

func TestProviderPreservesLegacyNativeJSON(t *testing.T) {
	// Legacy model.Provider field spelling; revisions here are synthetic fixtures.
	const legacy = `{"name":"otel-ebpf-profiler-via-gadget","gadget_revision":"fixture-gadget","profiler_revision":"fixture-profiler","patch_sha256":"fixture-patch"}`
	var provider Provider
	if err := json.Unmarshal([]byte(legacy), &provider); err != nil {
		t.Fatal(err)
	}
	if provider != (Provider{Name: "otel-ebpf-profiler-via-gadget", GadgetRevision: "fixture-gadget", ProfilerRevision: "fixture-profiler", PatchSHA256: "fixture-patch"}) {
		t.Fatal("legacy native provider identity changed")
	}
	encoded, err := json.Marshal(provider)
	if err != nil {
		t.Fatal(err)
	}
	if string(encoded) != legacy {
		t.Fatal("legacy provider JSON changed", string(encoded))
	}
}
