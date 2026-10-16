package unloading

import (
	"bytes"
	"encoding/json"
	"fmt"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"strings"
	"testing"
)

func TestUnloadingReportDistinguishesUnavailableFromWrongGeneration(t *testing.T) {
	manifest := "generation,sequence,branch,filename\n0,0,A,/fixture/mw-unload-g00000-s000-A\n1,0,A,/fixture/mw-unload-g00001-s000-A\n"
	encode := func(wrong bool) *bytes.Buffer {
		b := new(bytes.Buffer)
		e := json.NewEncoder(b)
		for g := 0; g < 2; g++ {
			id := string(rune('a' + g))
			name := "UnloadG00000"
			if g == 1 && !wrong {
				name = "UnloadG00001"
			}
			event := model.Event{PID: 123, TID: 124, EventID: id, CorrelationID: 7, Filename: strings.Split(strings.TrimSpace(strings.Split(manifest, "\n")[g+1]), ",")[3]}
			_ = e.Encode(model.Record{Kind: "event", EventID: id, Event: &event})
			_ = e.Encode(model.Record{Kind: "enrichment", EventID: id, Evidence: &model.StackEvidence{Attribution: "event_correlated", Frames: []model.Frame{{FunctionName: name + ".sharedLeaf(java.lang.String)"}, {FunctionName: name + ".pathA(java.lang.String)"}}}})
		}
		_ = e.Encode(model.Record{Kind: "session_summary", Summary: &model.Summary{TerminalAccountingComplete: true}})
		return b
	}
	unload := "[info][class,unload] unloading class UnloadG00000 0x1\n[info][class,unload] unloading class UnloadG00001 0x2\n"
	r, err := Analyze(strings.NewReader(manifest), encode(false), strings.NewReader(unload), strings.NewReader(""), 123)
	if err != nil || r.UnloadedGenerations != 2 || r.WrongGeneration != 0 || r.CrossGenerationReferences != 1 {
		t.Fatalf("valid lifetime evidence rejected: %+v %v", r, err)
	}
	r, err = Analyze(strings.NewReader(manifest), encode(true), strings.NewReader(unload), strings.NewReader(""), 123)
	if err == nil || r.WrongGeneration != 1 {
		t.Fatal("old generation labelled as correct or merely unavailable")
	}
	if _, err := Analyze(strings.NewReader(manifest), encode(false), strings.NewReader(""), strings.NewReader(""), 123); err == nil {
		t.Fatal("unloading inferred without JVM evidence")
	}
}

func TestEpochGateRejectsUnavailableMissingBranchAndReferenceMixing(t *testing.T) {
	for _, mode := range []string{"valid", "all_unavailable", "missing_B", "mixed_reference", "missing_identity"} {
		t.Run(mode, func(t *testing.T) {
			var manifest, log strings.Builder
			var records bytes.Buffer
			enc := json.NewEncoder(&records)
			manifest.WriteString("generation,sequence,branch,filename\n")
			for g := 0; g < 2; g++ {
				fmt.Fprintf(&log, "unloading class UnloadG%05d 0x1\n", g)
				for _, branch := range []string{"A", "B"} {
					filename := fmt.Sprintf("/fixture/mw-unload-g%05d-s000-%s", g, branch)
					fmt.Fprintf(&manifest, "%d,0,%s,%s\n", g, branch, filename)
					id := fmt.Sprintf("%d-%s", g, branch)
					epoch := uint64(2 + g*2)
					ref := uint64(10 + g*2)
					if mode == "mixed_reference" {
						ref = 10
					}
					e := model.Event{PID: 123, TID: 456, EventID: id, Filename: filename, CorrelationID: ref, HotSpotIdentity: model.CapturedHotSpotIdentity(libpf.HotSpotIdentity{Epoch: epoch, State: libpf.HotSpotEpochStable})}
					v := model.StackEvidence{Attribution: "event_correlated", HotSpotIdentity: model.CheckedHotSpotIdentity(libpf.HotSpotIdentity{Epoch: epoch, State: libpf.HotSpotEpochStable}), Frames: []model.Frame{{FunctionName: fmt.Sprintf("UnloadG%05d.sharedLeaf(java.lang.String)", g)}, {FunctionName: fmt.Sprintf("UnloadG%05d.path%s(java.lang.String)", g, branch)}}}
					if mode == "all_unavailable" || mode == "missing_B" && g == 1 && branch == "B" {
						v.Attribution = "unavailable"
						v.Frames = nil
					}
					if mode == "missing_identity" {
						v.HotSpotIdentity = nil
					}
					if err := enc.Encode(model.Record{Kind: "event", EventID: id, Event: &e}); err != nil {
						t.Fatal(err)
					}
					if err := enc.Encode(model.Record{Kind: "enrichment", EventID: id, Evidence: &v}); err != nil {
						t.Fatal(err)
					}
				}
			}
			enc.Encode(model.Record{Kind: "session_summary", Summary: &model.Summary{TerminalAccountingComplete: true}})
			r, e := AnalyzeEpoch(strings.NewReader(manifest.String()), &records, strings.NewReader(log.String()), strings.NewReader(""), 123)
			if mode == "valid" {
				if e != nil || r.CheckedSegments != 4 {
					t.Fatal(r, e)
				}
			} else if e == nil {
				t.Fatal("strict acceptance falsely passed", mode, r)
			}
		})
	}
}
