package benchmark

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
	"testing"

	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

func TestReadinessRequiresEveryRouteAndCheckedEpoch(t *testing.T) {
	for _, kind := range []string{"complete", "missing_route", "unchecked_epoch", "wrong_route", "wrong_pid", "wrong_owner"} {
		t.Run(kind, func(t *testing.T) {
			m := validBenchManifest()
			m.Mode, m.Diversity, m.PostAttachWarmup = "full", 8, 8
			b := new(bytes.Buffer)
			enc := json.NewEncoder(b)
			for route := 0; route < 8; route++ {
				if kind == "missing_route" && route == 7 {
					continue
				}
				id := fmt.Sprint(route)
				event := model.Event{EventID: id, PID: m.PID, Filename: fmt.Sprintf("/fixture/mw-ready-%s-%06d-r%d", m.Run, route, route)}
				if kind == "wrong_pid" {
					event.PID++
				}
				epoch := uint64(2)
				evidence := model.StackEvidence{Attribution: "event_correlated", HotSpotIdentity: &model.HotSpotIdentity{State: "safepoint_epoch_checked", Epoch: &epoch}, Frames: []model.Frame{{FunctionName: "BenchmarkFixture.leaf(java.lang.String)"}, {FunctionName: fmt.Sprintf("BenchmarkFixture.route%d(java.lang.String)", route)}}}
				if kind == "unchecked_epoch" {
					evidence.HotSpotIdentity.State = "stable"
				}
				if kind == "wrong_route" {
					evidence.Frames[1].FunctionName = "BenchmarkFixture.route0(java.lang.String)"
				}
				if kind == "wrong_owner" {
					for i := range evidence.Frames {
						evidence.Frames[i].FunctionName = strings.ReplaceAll(evidence.Frames[i].FunctionName, "BenchmarkFixture", "WrongBenchmarkFixture")
					}
				}
				if err := enc.Encode(model.Record{Kind: "event", EventID: id, Event: &event}); err != nil {
					t.Fatal(err)
				}
				if err := enc.Encode(model.Record{Kind: "enrichment", EventID: id, Evidence: &evidence}); err != nil {
					t.Fatal(err)
				}
			}
			r, err := ObserveReadiness(m, b)
			if kind == "complete" {
				if err != nil || !r.Ready || len(r.Routes) != 8 || r.Observed != 8 {
					t.Fatal(r, err)
				}
			} else if err == nil && r.Ready {
				t.Fatal("unproved readiness accepted", kind, r)
			}
		})
	}
}

func TestApplicationSegmentRequiresExactRendererOwnerAndMethod(t *testing.T) {
	for _, test := range []struct {
		name           string
		frames         []string
		correct, wrong bool
	}{
		{"renderer", []string{"void BenchmarkFixture.leaf(java.lang.String)", "void BenchmarkFixture.route0(java.lang.String)"}, true, false},
		{"other owner", []string{"void WrongBenchmarkFixture.leaf()", "void WrongBenchmarkFixture.route0()"}, false, false},
		{"qualified other owner", []string{"void other.BenchmarkFixture.leaf()", "void other.BenchmarkFixture.route0()"}, false, false},
		{"malformed renderer", []string{"prefix void BenchmarkFixture.leaf()", "BenchmarkFixture.route0() trailing"}, false, false},
		{"method suffix", []string{"void BenchmarkFixture.leafCorrupt()", "void BenchmarkFixture.route0()"}, false, false},
		{"other-owner route", []string{"BenchmarkFixture.leaf()", "BenchmarkFixture.route0()", "WrongBenchmarkFixture.route1()"}, true, false},
		{"wrong route", []string{"BenchmarkFixture.leaf()", "BenchmarkFixture.route1()"}, false, true},
		{"reordered", []string{"BenchmarkFixture.route0()", "BenchmarkFixture.leaf()"}, false, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			v := model.StackEvidence{Attribution: "event_correlated"}
			for _, name := range test.frames {
				v.Frames = append(v.Frames, model.Frame{FunctionName: name})
			}
			correct, wrong := applicationSegment(v, 0)
			if correct != test.correct || wrong != test.wrong {
				t.Fatalf("wrong application classification: correct=%t wrong=%t", correct, wrong)
			}
		})
	}
}
