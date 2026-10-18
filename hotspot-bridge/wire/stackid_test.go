package wire

import (
	"encoding/json"
	"reflect"
	"testing"
)

func TestCanonicalStackIDContentNotOccurrence(t *testing.T) {
	x, rows := syntheticSession()
	scope := Scope{IdentityScope: "otel-observed-provider-frames/v1", ImageID: x.Target.ImageID, JVMBuildID: "build", JVMSHA256: "sha", Profile: x.Profile, Provider: x.Provider}
	frames := rows[3].Evidence.Frames
	frames[0].Mapping = &Mapping{FileID: "real-file", Start: 4096, End: 8192, FileOffset: 3}
	frames = append(frames, Frame{FunctionName: "Synthetic.parent", PositionDomain: "provider_specific"})
	before, _ := json.Marshal(frames)
	id, err := CanonicalStackID(scope, frames)
	if err != nil {
		t.Fatal(err)
	}
	after, _ := json.Marshal(frames)
	if !reflect.DeepEqual(before, after) {
		t.Fatal("caller mutated")
	}
	for _, tc := range []struct {
		name   string
		mutate func(*Scope, []Frame)
		same   bool
	}{
		{"placement", func(s *Scope, f []Frame) { f[0].Mapping.Start++; f[0].Mapping.End++ }, true},
		{"untyped-occurrence-extensions", func(s *Scope, f []Frame) {
			f[0].Extensions = extensions{"host_tid": json.RawMessage(`123`), "session_id": json.RawMessage(`"other"`)}
			f[0].Mapping.Extensions = extensions{"correlation_id": json.RawMessage(`456`)}
		}, true},
		{"method", func(s *Scope, f []Frame) { f[0].FunctionName += "x" }, false},
		{"order", func(s *Scope, f []Frame) { f[0], f[1] = f[1], f[0] }, false},
		{"bci", func(s *Scope, f []Frame) { *f[0].BCI = 1 }, false},
		{"bci-absent", func(s *Scope, f []Frame) { f[0].BCI = nil }, false},
		{"quality", func(s *Scope, f []Frame) { f[0].QualityDiagnostic = "partial" }, false},
		{"raw-position", func(s *Scope, f []Frame) { f[0].RawPosition++ }, false},
		{"mapping-file", func(s *Scope, f []Frame) { f[0].Mapping.FileID += "x" }, false},
		{"mapping-offset", func(s *Scope, f []Frame) { f[0].Mapping.FileOffset++ }, false},
		{"provider", func(s *Scope, f []Frame) { s.Provider.PatchSHA256 += "x" }, false},
		{"image", func(s *Scope, f []Frame) { s.ImageID += "x" }, false},
		{"jvm", func(s *Scope, f []Frame) { s.JVMBuildID += "x" }, false},
		{"jvm-sha", func(s *Scope, f []Frame) { s.JVMSHA256 += "x" }, false},
		{"profile", func(s *Scope, f []Frame) { s.Profile = Strict25 }, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var owned []Frame
			if err := json.Unmarshal(before, &owned); err != nil {
				t.Fatal(err)
			}
			s := scope
			tc.mutate(&s, owned)
			got, err := CanonicalStackID(s, owned)
			if err != nil || (got == id) != tc.same {
				t.Fatal(got, id, err)
			}
		})
	}
	if _, err := CanonicalStackID(scope, nil); err == nil {
		t.Fatal("empty admitted")
	}
	if _, err := CanonicalStackID(scope, []Frame{{ErrorFrame: true}}); err == nil {
		t.Fatal("error frame admitted")
	}
}
