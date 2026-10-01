package corpus

import (
	"fmt"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"strings"
	"testing"
)

func TestConcurrentCorpusRejectsWorkerThreadMixing(t *testing.T) {
	manifest := "run,worker,sequence,branch,filename\n"
	base := corpusRecords(t)
	rows := []model.Record{base[0]}
	for i := 0; i < 4; i++ {
		worker := i / 2
		branch := []string{"A", "B"}[i%2]
		filename := fmt.Sprintf("/fixture/mw-inline-corpus-r-w%d-s%d-%s", worker, worker, branch)
		manifest += fmt.Sprintf("r,%d,%d,%s,%s\n", worker, worker, branch, filename)
		e := *base[1].Event
		e.EventID = fmt.Sprint(i)
		e.TID = uint32(400 + worker)
		e.CorrelationID = uint64(i + 1)
		e.Filename = filename
		e.FilenameBytes = []byte(filename)
		v := *base[2+i%2*2].Evidence
		v.Frames = append([]model.Frame(nil), v.Frames...)
		rows = append(rows, model.Record{SchemaVersion: 1, SessionID: "s", Kind: "event", EventID: e.EventID, Event: &e}, model.Record{SchemaVersion: 1, SessionID: "s", Kind: "enrichment", EventID: e.EventID, Evidence: &v})
	}
	summary := *base[len(base)-1].Summary
	summary.Stats = model.Stats{Received: 4, Resolved: 4}
	rows = append(rows, model.Record{SchemaVersion: 1, SessionID: "s", Kind: "session_summary", Summary: &summary})
	r, err := AnalyzeWorkers(strings.NewReader(manifest), encodeRecords(rows), strings.NewReader(compilerFixture()), 123, 2, 2)
	if err != nil || len(r.WorkerTIDs) != 2 || r.CompiledSegments["A"] != 2 {
		t.Fatalf("valid workers rejected: %+v %v", r, err)
	}
	rows[5].Event.TID = 400
	if _, err := AnalyzeWorkers(strings.NewReader(manifest), encodeRecords(rows), strings.NewReader(compilerFixture()), 123, 2, 2); err == nil {
		t.Fatal("workers sharing an unexpected TID accepted")
	}
}
