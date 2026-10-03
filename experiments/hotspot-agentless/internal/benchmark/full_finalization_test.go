package benchmark

import (
	"bytes"
	"encoding/json"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"strings"
	"testing"
)

func TestFullBenchmarkRequiresDefinitionDrainEvidence(t *testing.T) {
	for _, good := range []bool{false, true} {
		m := validBenchManifest()
		m.Mode = "full"
		m.DrainFull = true
		in := benchmarkPackets(t, m, false)
		out := new(bytes.Buffer)
		decoder, encoder := json.NewDecoder(in), json.NewEncoder(out)
		for {
			var row model.Record
			if decoder.Decode(&row) != nil {
				break
			}
			if row.Summary != nil {
				zero := uint64(0)
				row.Summary.EventFinalization = &model.EventFinalizationObservation{Scope: "owned_openat_event_ring", Phase: "resources_retired", ProducersQuiesced: true, BufferDrained: true, ReceiverJoined: true, FinalCounterRead: true, ResourcesRetired: true, NativeReserveFailures: &zero}
				row.Summary.EventLossObservation = &model.LossObservation{Observed: &zero, Semantics: "quiescent_native_reserve_failures_partial_snapshot"}
				if good {
					row.Summary.DefinitionStop = &model.DefinitionStopObservation{ReaderStopped: true, ConsumerStopped: true, BuffersDrained: true, Semantics: "owned_definition_perf_drained_partial_loss_snapshot"}
				}
			}
			if err := encoder.Encode(row); err != nil {
				t.Fatal(err)
			}
		}
		_, err := Analyze(m, strings.NewReader(benchTarget()), out)
		if good != (err == nil) {
			t.Fatal("incorrect full finalization evidence", good, err)
		}
	}
}
