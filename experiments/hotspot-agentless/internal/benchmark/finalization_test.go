package benchmark

import (
	"bytes"
	"encoding/json"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"strings"
	"testing"
)

func TestBenchmarkOwnedDrainRequiresActualPartialComponentEvidence(t *testing.T) {
	for _, kind := range []string{"missing", "valid", "unjoined", "loss_complete", "counter_mismatch", "wrong_scope"} {
		t.Run(kind, func(t *testing.T) {
			m := validBenchManifest()
			m.DrainEvents = true
			in := benchmarkPackets(t, m, false)
			out := new(bytes.Buffer)
			d, e := json.NewDecoder(in), json.NewEncoder(out)
			for {
				var row model.Record
				if d.Decode(&row) != nil {
					break
				}
				if row.Summary != nil && kind != "missing" {
					zero := uint64(0)
					o := &model.EventFinalizationObservation{Scope: "owned_openat_event_ring", Phase: "resources_retired", ProducersQuiesced: true, BufferDrained: true, ReceiverJoined: true, FinalCounterRead: true, ResourcesRetired: true, NativeReserveFailures: &zero}
					row.Summary.EventFinalization = o
					row.Summary.EventLossObservation = &model.LossObservation{Observed: &zero, Semantics: "quiescent_native_reserve_failures_partial_snapshot"}
					switch kind {
					case "unjoined":
						o.ReceiverJoined = false
					case "loss_complete":
						o.LossComplete = true
					case "counter_mismatch":
						one := uint64(1)
						o.NativeReserveFailures = &one
					case "wrong_scope":
						o.Scope = "unknown"
					}
				}
				if err := e.Encode(row); err != nil {
					t.Fatal(err)
				}
			}
			_, err := Analyze(m, strings.NewReader(benchTarget()), out)
			if (kind == "valid") != (err == nil) {
				t.Fatal("incorrect component claim accepted/rejected", kind, err)
			}
		})
	}
}
