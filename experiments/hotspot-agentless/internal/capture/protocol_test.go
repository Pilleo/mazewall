package capture

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"syscall"
	"testing"

	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

func TestProductionReadyRequiresLiveValidatedTargetCheck(t *testing.T) {
	for _, denied := range []error{syscall.EPERM, syscall.EACCES, context.Canceled} {
		var out bytes.Buffer
		sink := newProductionSink(&out)
		err := writeProductionReady(context.Background(), sink, "session", 123, 456, func() error { return denied })
		if !errors.Is(err, denied) || out.Len() != 0 {
			t.Fatalf("readiness fabricated after failed admission: %v, %s", err, &out)
		}
	}
}

func TestProductionWireDoesNotTreatEarlyMarkerAsReady(t *testing.T) {
	var out bytes.Buffer
	sink := newProductionSink(&out)
	if err := sink.Write(context.Background(), model.Record{SchemaVersion: 1, Kind: "collector_attached", SessionID: "session"}); err != nil {
		t.Fatal(err)
	}
	if out.Len() != 0 {
		t.Fatal("legacy attachment marker leaked into production protocol")
	}
	if err := writeProductionReady(context.Background(), sink, "session", 123, 456, func() error { return nil }); err != nil {
		t.Fatal(err)
	}
	var record model.Record
	if err := json.Unmarshal(out.Bytes(), &record); err != nil {
		t.Fatal(err)
	}
	if record.SchemaVersion != 2 || record.Kind != "collector_ready" || record.Lifecycle == nil || record.Lifecycle.ProcessStartTicks != 456 || record.Lifecycle.PID != 123 || record.Lifecycle.ClockDomain != "CLOCK_BOOTTIME" {
		t.Fatalf("wrong production readiness identity: %+v", record)
	}
}

func TestProductionScopeRefusesUnownedOrNonStrictCapture(t *testing.T) {
	for _, cfg := range []Config{{}, {StrictEpoch: true}, {DrainFull: true}, {StrictEpoch: true, DrainFull: true, EventsOnly: true}} {
		var out bytes.Buffer
		if err := RunProduction(context.Background(), cfg, &out); err == nil || bytes.Contains(out.Bytes(), []byte(`"collector_ready"`)) {
			t.Fatalf("unsupported production scope admitted: %+v", cfg)
		}
	}
}
