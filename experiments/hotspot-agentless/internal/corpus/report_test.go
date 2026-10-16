package corpus

import (
	"bytes"
	"encoding/json"
	"fmt"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"strings"
	"testing"
)

func manifestFixture() string {
	return "run,worker,sequence,branch,filename\nr,0,0,A,/fixture/mw-inline-corpus-r-w0-s0-A\nr,0,0,B,/fixture/mw-inline-corpus-r-w0-s0-B\n"
}
func corpusRecords(t *testing.T) []model.Record {
	t.Helper()
	var rows []model.Record
	rows = append(rows, model.Record{SchemaVersion: 1, Kind: "collector_attached", SessionID: "s"})
	for i, branch := range []string{"A", "B"} {
		id := fmt.Sprint(i)
		e := &model.Event{EventID: id, SessionID: "s", PID: 123, TID: 123, ProcessStartTicks: 7, CorrelationID: uint64(i + 1), ClockDomain: "CLOCK_BOOTTIME", Filename: fmt.Sprintf("/fixture/mw-inline-corpus-r-w0-s0-%s", branch), DirFD: -100, ArgumentRead: 50}
		e.FilenameBytes = []byte(e.Filename)
		rows = append(rows, model.Record{SchemaVersion: 1, Kind: "event", SessionID: "s", EventID: id, Event: e}, model.Record{SchemaVersion: 1, Kind: "enrichment", SessionID: "s", EventID: id, Evidence: &model.StackEvidence{Attribution: "event_correlated", Walk: "returned", Frames: []model.Frame{{ProviderType: uint8(libpf.HotSpotFrame), FunctionName: "InlineCorpusFixture.sharedLeaf(java.lang.String)", ScopeLookup: "exact_pc"}, {ProviderType: uint8(libpf.HotSpotFrame), FunctionName: "InlineCorpusFixture.path" + branch + "(java.lang.String)", ScopeLookup: "exact_pc"}}, Provider: model.Provider{PatchSHA256: "patch"}}})
	}
	zero := uint64(0)
	rows = append(rows, model.Record{SchemaVersion: 1, Kind: "session_summary", SessionID: "s", Summary: &model.Summary{Stats: model.Stats{Received: 2, Resolved: 2}, TerminalAccountingComplete: true, CollectionHealth: "partial", EventLossObservation: &model.LossObservation{Observed: &zero}, DefinitionReaderObservation: &model.ReaderObservation{}}})
	return rows
}
func encodeRecords(rows []model.Record) *bytes.Buffer {
	b := new(bytes.Buffer)
	e := json.NewEncoder(b)
	for _, r := range rows {
		_ = e.Encode(r)
	}
	return b
}

func TestCorpusPairsExactTokensAndRejectsIncorrectEvidence(t *testing.T) {
	r, err := Analyze(strings.NewReader(manifestFixture()), encodeRecords(corpusRecords(t)), strings.NewReader(compilerFixture()), 123, 1)
	if err != nil || r.Observed != 2 || r.ResolvedSegments != 2 || r.CompiledSegments["A"] != 1 || r.CompiledSegments["B"] != 1 {
		t.Fatalf("valid corpus rejected: %+v %v", r, err)
	}
	for _, change := range []func([]model.Record) []model.Record{
		func(r []model.Record) []model.Record {
			r[2].Evidence.Frames[0].ProviderType = uint8(libpf.NativeFrame)
			return r
		},
		func(r []model.Record) []model.Record { r[len(r)-1].Summary.Errors = []string{"failed"}; return r },
		func(r []model.Record) []model.Record {
			r[2].Evidence.Frames[1].FunctionName = "InlineCorpusFixture.pathB(java.lang.String)"
			return r
		},
		func(r []model.Record) []model.Record {
			r[2].Evidence.Frames[0], r[2].Evidence.Frames[1] = r[2].Evidence.Frames[1], r[2].Evidence.Frames[0]
			return r
		},
		func(r []model.Record) []model.Record { r[1].Event.PID = 999; return r },
		func(r []model.Record) []model.Record { return append(r, r[1]) },
		func(r []model.Record) []model.Record { return append(r, r[2]) },
		func(r []model.Record) []model.Record { r[3].Event.Filename = "/fixture/unobserved"; return r },
		func(r []model.Record) []model.Record { r[2].Evidence.Frames[0].ScopeLookup = "interpreter"; return r },
		func(r []model.Record) []model.Record { r[2].Evidence.Walk = "complete"; return r },
		func(r []model.Record) []model.Record {
			r[len(r)-1].Summary.EventLossObservation.Complete = true
			return r
		},
		func(r []model.Record) []model.Record {
			r[len(r)-1].Summary.TerminalAccountingComplete = false
			return r
		},
	} {
		if _, err := Analyze(strings.NewReader(manifestFixture()), encodeRecords(change(corpusRecords(t))), strings.NewReader(compilerFixture()), 123, 1); err == nil {
			t.Fatal("incorrect attribution/count/quality accepted")
		}
	}
	for _, bad := range []string{manifestFixture() + "r,0,0,A,/fixture/mw-inline-corpus-r-w0-s0-A\n", strings.Replace(manifestFixture(), "r,0,0,B", "other,0,0,B", 1), strings.Replace(manifestFixture(), "r,0,0,B", "r,0,1,B", 1)} {
		if _, err := Analyze(strings.NewReader(bad), encodeRecords(corpusRecords(t)), strings.NewReader(compilerFixture()), 123, 1); err == nil {
			t.Fatal("invalid operation manifest accepted")
		}
	}
}
