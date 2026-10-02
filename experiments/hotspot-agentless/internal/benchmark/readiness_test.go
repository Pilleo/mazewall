package benchmark

import (
	"bytes"
	"encoding/json"
	"fmt"
	"testing"

	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

func TestReadinessRequiresEveryRouteAndCheckedEpoch(t *testing.T) {
	for _, kind := range []string{"complete", "missing_route", "unchecked_epoch", "wrong_route", "wrong_pid"} {
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
