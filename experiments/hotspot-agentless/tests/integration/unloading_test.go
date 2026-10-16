//go:build integration

package integration

import (
	"encoding/json"
	"io.mazewall/experiments/hotspot-agentless/internal/unloading"
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

func TestDisposableLoaderAttributionInvestigation(t *testing.T) {
	runUnloadingGate(t, false)
}

func TestDisposableLoaderEpochAttribution(t *testing.T) {
	runUnloadingGate(t, true)
}

func runUnloadingGate(t *testing.T, strict bool) {
	t.Helper()
	artifact := filepath.Dir(os.Getenv("MW_CAPTURE_JSONL"))
	pid, err := strconv.ParseUint(os.Getenv("MW_TARGET_PID"), 10, 32)
	if err != nil {
		t.Fatal(err)
	}
	files := make([]*os.File, 0, 4)
	for _, name := range []string{"unload-operations.csv", "events.jsonl", "unloading.log", "collector.log"} {
		f, err := os.Open(filepath.Join(artifact, name))
		if err != nil {
			t.Fatal(err)
		}
		defer f.Close()
		files = append(files, f)
	}
	analyze := unloading.Analyze
	if strict {
		analyze = unloading.AnalyzeEpoch
	}
	r, investigationErr := analyze(files[0], files[1], files[2], files[3], uint32(pid))
	data, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(artifact, "unloading-report.json"), append(data, '\n'), 0600); err != nil {
		t.Fatal(err)
	}
	if !strict && os.Getenv("MW_REQUIRE_IDENTITY_DIAGNOSTICS") != "false" && len(r.Identities) == 0 {
		t.Fatal("diagnostic build produced no raw dynamic-class identities")
	}
	if investigationErr != nil {
		t.Fatal(investigationErr)
	}
	if strict && (r.Expected != 1600 || r.Observed != 1600 || r.UnloadedGenerations != 80 || r.CheckedSegments == 0 || r.SameEpochReferenceReuses == 0) {
		t.Fatalf("strict corpus dimensions/reuse not demonstrated: %+v", r)
	}
}
