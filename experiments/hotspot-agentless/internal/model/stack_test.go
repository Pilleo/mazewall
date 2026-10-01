package model

import (
	"encoding/json"
	"strings"
	"testing"

	"go.opentelemetry.io/ebpf-profiler/libpf"
)

func TestNormalizePreservesTypedSequenceWithoutInventingBCI(t *testing.T) {
	var raw libpf.Frames
	raw.Append(&libpf.Frame{Type: libpf.NativeFrame, FunctionName: libpf.Intern("nativeLeaf"), AddressOrLineno: 4096})
	raw.Append(&libpf.Frame{Type: libpf.HotSpotFrame, FunctionName: libpf.Intern("javaLeaf"), AddressOrLineno: 17, SourceLine: 42})
	raw.Append(&libpf.Frame{Type: libpf.HotSpotFrame, FunctionName: libpf.Intern("javaParent"), AddressOrLineno: 0})
	frames := Normalize(raw)
	if len(frames) != 3 || frames[0].FunctionName != "nativeLeaf" || frames[1].FunctionName != "javaLeaf" || frames[2].FunctionName != "javaParent" {
		t.Fatal("sequence changed")
	}
	if frames[0].NativeAddress == nil || *frames[0].NativeAddress != 4096 || frames[0].BCI != nil {
		t.Fatal("native address domain lost")
	}
	if frames[1].BCI != nil || frames[1].RawPosition != 17 || frames[1].SourceLine == nil || *frames[1].SourceLine != 42 {
		t.Fatal("BCI availability or source line invented")
	}
	if frames[2].SourceLine != nil || frames[2].BCI != nil {
		t.Fatal("missing values converted to zero")
	}
	encoded, err := json.Marshal(frames)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(encoded), `"bci":0`) {
		t.Fatal("fallback zero became available BCI")
	}
}

func TestNormalizeMappingWithoutBackingFileKeepsUnknownIdentity(t *testing.T) {
	var raw libpf.Frames
	raw.Append(&libpf.Frame{Type: libpf.NativeFrame, Mapping: libpf.NewFrameMapping(libpf.FrameMappingData{Start: 4096, End: 8192})})
	frames := Normalize(raw)
	if len(frames) != 1 || frames[0].Mapping == nil || frames[0].Mapping.FileID != "" || frames[0].Mapping.Start != 4096 {
		t.Fatal("unknown mapping identity fabricated or lost")
	}
}

func TestNormalizeExplicitBCIAvailability(t *testing.T) {
	for _, tc := range []struct {
		name      string
		q         libpf.HotSpotQuality
		available bool
		raw       bool
	}{
		{"observed zero", libpf.HotSpotQuality{ScopeLookup: libpf.HotSpotScopeExactPC, BCIState: libpf.HotSpotBCIObservedInRange}, true, true},
		{"method fallback", libpf.HotSpotQuality{ScopeLookup: libpf.HotSpotScopeMethodOnly, BCIState: libpf.HotSpotBCIUnavailable}, false, false},
		{"invalid input", libpf.HotSpotQuality{ScopeLookup: libpf.HotSpotScopeInterpreter, BCIState: libpf.HotSpotBCIOutOfRange, RawBCI: 0xffff}, false, true},
		{"unknown", libpf.HotSpotQuality{}, false, false},
		{"unknown enum", libpf.HotSpotQuality{ScopeLookup: 255, BCIState: 255, ScopeWalk: 255}, false, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var raw libpf.Frames
			raw.Append(&libpf.Frame{Type: libpf.HotSpotFrame, HotSpotQuality: tc.q})
			f := Normalize(raw)[0]
			if (f.BCI != nil) != tc.available || (f.RawBCI != nil) != tc.raw {
				t.Fatalf("invalid availability: %+v", f)
			}
			b, _ := json.Marshal(f)
			if strings.Contains(string(b), `"bci":0`) != tc.available {
				t.Fatal("JSON zero availability wrong")
			}
		})
	}
	var raw libpf.Frames
	raw.Append(&libpf.Frame{Type: libpf.NativeFrame, AddressOrLineno: 4096, HotSpotQuality: libpf.HotSpotQuality{ScopeLookup: libpf.HotSpotScopeExactPC, BCIState: libpf.HotSpotBCIObservedInRange}})
	f := Normalize(raw)[0]
	if f.BCI != nil || f.ScopeLookup != "" || f.RawBCI != nil || *f.NativeAddress != 4096 {
		t.Fatal("native frame got Java metadata")
	}
}
