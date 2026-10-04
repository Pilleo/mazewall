//go:build integration

package integration

import (
	"bufio"
	"encoding/json"
	"os"
	"strconv"
	"strings"
	"testing"

	"io.mazewall/experiments/hotspot-agentless/internal/identity"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

func TestProductionReadinessAndSIGTERMOwnedDrain(t *testing.T) {
	path := os.Getenv("MW_CAPTURE_JSONL")
	if path == "" {
		t.Skip("requires real production capture artifact")
	}
	file, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	pid, err := strconv.ParseUint(os.Getenv("MW_TARGET_PID"), 10, 32)
	if err != nil {
		t.Fatal(err)
	}
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 4096), 1<<20)
	session := ""
	var ready *model.Lifecycle
	var summary *model.Summary
	events := map[string]model.Event{}
	terminals := map[string]*model.StackEvidence{}
	for scanner.Scan() {
		var record model.Record
		if err := json.Unmarshal(scanner.Bytes(), &record); err != nil {
			t.Fatal(err)
		}
		if record.SchemaVersion != 2 {
			t.Fatal("production wire downgraded")
		}
		if session == "" {
			if record.Kind != "session_started" {
				t.Fatal("missing initial start marker")
			}
			session = record.SessionID
		}
		if record.SessionID != session {
			t.Fatal("cross-session lifecycle")
		}
		switch record.Kind {
		case "session_started":
			if record.Lifecycle == nil || record.Lifecycle.JVMBuildID != "" {
				t.Fatal("early marker claimed runtime admission")
			}
		case "collector_ready":
			if ready != nil || record.Lifecycle == nil {
				t.Fatal("duplicate or absent ready identity")
			}
			ready = record.Lifecycle
			if uint64(ready.PID) != pid || ready.ProcessStartTicks == 0 || ready.ClockDomain != "CLOCK_BOOTTIME" || ready.RuntimeProfile != identity.ProfileName || ready.JVMBuildID != identity.JVMBuildID || ready.JVMSHA256 != identity.JVMSHA256 {
				t.Fatal("unverified ready target")
			}
		case "event":
			if record.Event == nil {
				t.Fatal("missing event")
			}
			if _, exists := events[record.EventID]; exists {
				t.Fatal("duplicate occurrence")
			}
			events[record.EventID] = *record.Event
		case "enrichment":
			if record.Evidence == nil {
				t.Fatal("missing evidence")
			}
			if _, exists := terminals[record.EventID]; exists {
				t.Fatal("duplicate terminal")
			}
			terminals[record.EventID] = record.Evidence
		case "recovery":
		case "session_summary":
			if summary != nil {
				t.Fatal("duplicate summary")
			}
			summary = record.Summary
		default:
			t.Fatalf("unexpected production outcome %s", record.Kind)
		}
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	if ready == nil || summary == nil || len(events) != len(terminals) || !summary.TerminalAccountingComplete || len(summary.Errors) != 0 {
		t.Fatal("readiness/accounting/final summary incomplete")
	}
	e := summary.EventFinalization
	d := summary.DefinitionStop
	if e == nil || !e.ProducersQuiesced || !e.BufferDrained || !e.ReceiverJoined || !e.FinalCounterRead || !e.ResourcesRetired || e.LossComplete || d == nil || !d.ReaderStopped || !d.ConsumerStopped || !d.BuffersDrained || !d.AuxiliaryStopped {
		t.Fatal("SIGTERM did not reach checked owned drain")
	}
	if summary.EventLoss != nil || summary.DefinitionLoss != nil || summary.CollectionHealth != "partial" {
		t.Fatal("physical stop fabricated complete collection")
	}
	marked, attributed := 0, 0
	for id, event := range events {
		terminal, exists := terminals[id]
		if !exists || terminal == nil {
			t.Fatal("unpaired occurrence")
		}
		if uint64(event.PID) != pid || event.ProcessStartTicks != ready.ProcessStartTicks {
			t.Fatal("wrong event generation")
		}
		if !strings.Contains(event.Filename, "mw-inline-open-") {
			continue
		}
		marked++
		if event.CaptureBootNS < ready.BootNS {
			t.Fatal("fixture operation preceded native readiness")
		}
		for _, frame := range terminal.Frames {
			if strings.Contains(frame.FunctionName, "InlineOpenFixture") {
				attributed++
				break
			}
		}
	}
	if marked != 400 || attributed == 0 {
		t.Fatalf("marked=%d genuine_attributed=%d", marked, attributed)
	}
	t.Logf("native ready generation=%d; marked=%d useful=%d; SIGTERM event/definition/auxiliary joins=true; loss remains partial", ready.ProcessStartTicks, marked, attributed)
}
