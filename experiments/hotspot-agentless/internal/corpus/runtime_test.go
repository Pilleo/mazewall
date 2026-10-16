package corpus

import (
	"strings"
	"testing"
)

func TestRuntimeModesDoNotInventCompiledInlineProof(t *testing.T) {
	rows := corpusRecords(t)
	for _, i := range []int{2, 4} {
		for j := range rows[i].Evidence.Frames {
			rows[i].Evidence.Frames[j].ScopeLookup = "interpreter"
		}
	}
	noCompiler := `<hotspot_log process="123"></hotspot_log>`
	r, err := AnalyzeRuntime(strings.NewReader(manifestFixture()), encodeRecords(rows), strings.NewReader(noCompiler), 123, 1, 1, "interpreter")
	if err != nil || r.ResolvedSegments != 2 || r.CompiledSegments["A"] != 0 || len(r.Compiler) != 0 {
		t.Fatalf("interpreter proof conflated: %+v %v", r, err)
	}
	if _, err := AnalyzeRuntime(strings.NewReader(manifestFixture()), encodeRecords(rows), strings.NewReader(noCompiler), 123, 1, 1, "c2"); err == nil {
		t.Fatal("C2 accepted absent compiler proof")
	}
	rows[2].Evidence.Frames[0].ScopeLookup = "exact_pc"
	if _, err := AnalyzeRuntime(strings.NewReader(manifestFixture()), encodeRecords(rows), strings.NewReader(noCompiler), 123, 1, 1, "interpreter"); err == nil {
		t.Fatal("interpreter corpus claimed compiled frame")
	}
}
