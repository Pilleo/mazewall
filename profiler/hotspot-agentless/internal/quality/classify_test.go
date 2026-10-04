package quality

import (
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"testing"
)

func TestClassifyScopeAndWalkConservatively(t *testing.T) {
	java := func(scope string) model.Frame {
		return model.Frame{ProviderType: uint8(libpf.HotSpotFrame), ScopeLookup: scope}
	}
	for _, tc := range []struct {
		name        string
		frames      []model.Frame
		scope, walk string
	}{
		{"exact", []model.Frame{java("exact_pc"), java("exact_pc")}, "exact_pc", "returned"},
		{"mixed", []model.Frame{java("exact_pc"), java("preceding_pc")}, "mixed", "returned"},
		{"unknown dominates", []model.Frame{java("exact_pc"), java("unknown")}, "unknown", "returned"},
		{"unknown after mixed", []model.Frame{java("exact_pc"), java("preceding_pc"), java("unknown")}, "unknown", "returned"},
		{"method", []model.Frame{java("method_only")}, "method_only", "returned"},
		{"native ignored", []model.Frame{{FrameType: "native"}, java("exact_pc")}, "exact_pc", "returned"},
		{"no Java", []model.Frame{{FrameType: "native"}}, "unknown", "returned"},
		{"scope partial", []model.Frame{{ProviderType: uint8(libpf.HotSpotFrame), ScopeLookup: "exact_pc", ScopeWalk: "partial", QualityDiagnostic: "bad scope link"}}, "exact_pc", "partial"},
		{"error", []model.Frame{{ErrorFrame: true}}, "unknown", "partial"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			c := Classify(tc.frames)
			if c.ScopeLookup != tc.scope || c.Walk != tc.walk {
				t.Fatalf("incorrect quality: %+v", c)
			}
			if tc.name == "scope partial" && (len(c.RawErrors) != 1 || c.RawErrors[0] != "bad scope link") {
				t.Fatal("diagnostic lost")
			}
		})
	}
}
