//go:build integration

package integration

import (
	"bufio"
	"encoding/json"
	"os"
	"strconv"
	"strings"
	"testing"

	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

func TestStructuredCaptureHasOneTerminalPerEventAndTypedJavaFrames(t *testing.T) {
	name := os.Getenv("MW_CAPTURE_JSONL")
	pid, err := strconv.ParseUint(os.Getenv("MW_TARGET_PID"), 10, 32)
	if err != nil || pid == 0 || name == "" {
		t.Fatal("genuine capture file and expected target PID are required; never skip")
	}
	f, err := os.Open(name)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 4096), 4*1024*1024)
	events := map[string]model.Event{}
	terminals := map[string]model.StackEvidence{}
	var summary *model.Summary
	session := ""
	for scanner.Scan() {
		var record model.Record
		if err := json.Unmarshal(scanner.Bytes(), &record); err != nil {
			t.Fatal(err)
		}
		if record.SchemaVersion != 1 || record.SessionID == "" {
			t.Fatal("missing version/session")
		}
		if session == "" {
			session = record.SessionID
		}
		if session != record.SessionID {
			t.Fatal("cross-session record")
		}
		switch record.Kind {
		case "collector_attached":
		case "event":
			if record.Event == nil {
				t.Fatal("missing event")
			}
			e := *record.Event
			if uint64(e.PID) != pid || e.TID == 0 || e.ProcessStartTicks == 0 || e.ClockDomain != "CLOCK_BOOTTIME" || e.EventID != record.EventID || e.SessionID != session {
				t.Fatal("wrong event identity")
			}
			if _, exists := events[e.EventID]; exists {
				t.Fatal("duplicate event occurrence")
			}
			events[e.EventID] = e
		case "enrichment":
			if record.Evidence == nil {
				t.Fatal("missing terminal evidence")
			}
			if _, exists := events[record.EventID]; !exists {
				t.Fatal("terminal has no event")
			}
			if _, exists := terminals[record.EventID]; exists {
				t.Fatal("duplicate terminal result")
			}
			if record.Evidence.Provider.PatchSHA256 == "" {
				t.Fatal("missing patch provenance")
			}
			terminals[record.EventID] = *record.Evidence
		case "session_summary":
			summary = record.Summary
		case "recovery":
			if record.Recovery == nil {
				t.Fatal("missing recovery evidence")
			}
			if _, exists := terminals[record.EventID]; !exists {
				t.Fatal("recovery preceded terminal outcome")
			}
		default:
			t.Fatalf("unexpected record kind %q", record.Kind)
		}
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	if len(events) == 0 || len(events) != len(terminals) || summary == nil || !summary.TerminalAccountingComplete {
		t.Fatalf("incomplete terminal accounting: events=%d terminals=%d summary=%+v", len(events), len(terminals), summary)
	}
	if summary.Stats.Received != uint64(len(events)) || summary.Stats.Resolved+summary.Stats.Unavailable+summary.Stats.Skipped != summary.Stats.Received {
		t.Fatal("summary accounting mismatch")
	}
	if summary.EventLossObservation == nil || summary.EventLossObservation.Observed == nil || summary.EventLossObservation.Semantics != "forwarded_reports_lower_bound" || summary.EventLossObservation.Complete || summary.EventLoss != nil || summary.DefinitionLoss != nil {
		t.Fatal("reported loss observation became an unsupported whole-session zero-loss claim")
	}
	if summary.DefinitionReaderObservation == nil || summary.DefinitionReaderObservation.Complete || summary.DefinitionReaderObservation.BufferBytesPerCPU <= 0 || summary.DefinitionReaderObservation.Semantics != "consumed_trace_perf_records_partial_snapshot" {
		t.Fatal("missing partial definition-reader snapshot and actual buffer size")
	}
	marked := 0
	javaFrames := 0
	for id, event := range events {
		if !strings.Contains(event.Filename, "mw-inline-open") {
			continue
		}
		marked++
		if event.RegisterRead != 0 || event.DirFD != -100 || event.Flags != 0 || string(event.FilenameBytes) != event.Filename {
			t.Fatal("invalid fixture argument evidence")
		}
		evidence := terminals[id]
		if evidence.Attribution != "event_correlated" {
			if len(evidence.Frames) != 0 {
				t.Fatal("unavailable result contains substitute frames")
			}
			continue
		}
		if event.CorrelationID == 0 {
			t.Fatal("attribution without event reference")
		}
		for _, frame := range evidence.Frames {
			if frame.BCI != nil && (frame.BCIState != "observed_in_range" || frame.RawBCI == nil || *frame.RawBCI != *frame.BCI) {
				t.Fatal("BCI availability lacks explicit provider evidence")
			}
			if strings.Contains(frame.FunctionName, "InlineOpenFixture") {
				javaFrames++
				break
			}
		}
	}
	if marked != 400 || javaFrames == 0 {
		t.Fatalf("marked=%d typed Java evidence=%d", marked, javaFrames)
	}
}
