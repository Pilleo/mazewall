package benchmark

import (
	"bytes"
	"encoding/json"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"strings"
	"testing"
)

func benchmarkPackets(t *testing.T, m RunManifest, missing bool) *bytes.Buffer {
	t.Helper()
	b := new(bytes.Buffer)
	e := json.NewEncoder(b)
	received := uint64(0)
	for i := 0; i < m.Operations; i++ {
		if missing && i == 1 {
			continue
		}
		id := string(rune('a' + i))
		filename := "/fixture/mw-bench-0123456789abcdef-000000-r0"
		if i == 1 {
			filename = "/fixture/mw-bench-0123456789abcdef-000001-r0"
		}
		v := model.Event{EventID: id, SessionID: "s", PID: 123, TID: 456, Sequence: uint64(i + 1), Filename: filename}
		e.Encode(model.Record{Kind: "event", EventID: id, Event: &v})
		e.Encode(model.Record{Kind: "enrichment", EventID: id, Evidence: &model.StackEvidence{Attribution: "skipped", Reason: "unwind_disabled"}})
		received++
	}
	e.Encode(model.Record{Kind: "session_summary", Summary: &model.Summary{Stats: model.Stats{Received: received, Skipped: received}, TerminalAccountingComplete: true, CollectionHealth: "partial"}})
	return b
}
func validBenchManifest() RunManifest {
	return RunManifest{Mode: "events", Run: "0123456789abcdef", PID: 123, Operations: 2, Warmup: 10, Diversity: 1, PauseNS: 0, JVMImage: "jdk@sha256:abc", PatchSHA256: strings.Repeat("a", 64), GadgetImage: "gadget@sha256:abc", Kernel: "test-kernel"}
}
func benchTarget() string {
	return `{"run":"0123456789abcdef","pid":123,"operations":2,"successful_operations":2,"warmup":10,"diversity":1,"pause_ns":0,"elapsed_ns":1000,"p50_ns":100,"p95_ns":200,"p99_ns":200,"max_ns":200}`
}
func TestBenchmarkReportKeepsMissingEventsSeparateFromSkips(t *testing.T) {
	m := validBenchManifest()
	r, e := Analyze(m, strings.NewReader(benchTarget()), benchmarkPackets(t, m, true))
	if e != nil || r.Observed != 1 || r.Unobserved != 1 || r.Skipped != 1 || r.SequenceGapLowerBound != 0 || r.CollectionLossComplete {
		t.Fatal("missing token mislabeled or loss proof invented", r, e)
	}
}
func TestBenchmarkReportRejectsInvalidMeasurementAndBaselineCollection(t *testing.T) {
	m := validBenchManifest()
	bad := strings.Replace(benchTarget(), `"elapsed_ns":1000`, `"elapsed_ns":0`, 1)
	if _, e := Analyze(m, strings.NewReader(bad), benchmarkPackets(t, m, false)); e == nil {
		t.Fatal("invalid denominator accepted")
	}
	bad = strings.Replace(benchTarget(), `"p95_ns":200`, `"p95_ns":50`, 1)
	if _, e := Analyze(m, strings.NewReader(bad), benchmarkPackets(t, m, false)); e == nil {
		t.Fatal("invalid percentile order accepted")
	}
	m.Mode = "baseline"
	if _, e := Analyze(m, strings.NewReader(benchTarget()), benchmarkPackets(t, m, false)); e == nil {
		t.Fatal("baseline claimed collection")
	}
}
func TestBenchmarkReportRejectsFalseFinalLossProof(t *testing.T) {
	m := validBenchManifest()
	b := benchmarkPackets(t, m, false)
	var rows []model.Record
	d := json.NewDecoder(b)
	for {
		var r model.Record
		if d.Decode(&r) != nil {
			break
		}
		if r.Summary != nil {
			zero := uint64(0)
			r.Summary.EventLoss = &zero
		}
		rows = append(rows, r)
	}
	out := new(bytes.Buffer)
	e := json.NewEncoder(out)
	for _, r := range rows {
		e.Encode(r)
	}
	if _, err := Analyze(m, strings.NewReader(benchTarget()), out); err == nil {
		t.Fatal("unknown loss upgraded to final zero")
	}
}

func TestBenchmarkReportRejectsCounterAndEventsModeFabrication(t *testing.T) {
	for _, kind := range []string{"counters", "events_profiler"} {
		m := validBenchManifest()
		in := benchmarkPackets(t, m, false)
		out := new(bytes.Buffer)
		d, enc := json.NewDecoder(in), json.NewEncoder(out)
		for {
			var r model.Record
			if d.Decode(&r) != nil {
				break
			}
			if r.Summary != nil {
				if kind == "counters" {
					r.Summary.Stats.Skipped = 0
				} else {
					r.Summary.DefinitionReaderObservation = &model.ReaderObservation{}
				}
			}
			if err := enc.Encode(r); err != nil {
				t.Fatal(err)
			}
		}
		if _, err := Analyze(m, strings.NewReader(benchTarget()), out); err == nil {
			t.Fatal("dishonest measurement accepted", kind)
		}
	}
}
