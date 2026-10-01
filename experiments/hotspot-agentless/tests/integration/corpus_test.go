//go:build integration

package integration

import (
	"encoding/json"
	"io.mazewall/experiments/hotspot-agentless/internal/corpus"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func TestDeterministicInlineCorpus(t *testing.T) {
	artifact := filepath.Dir(os.Getenv("MW_CAPTURE_JSONL"))
	pid, err := strconv.ParseUint(os.Getenv("MW_TARGET_PID"), 10, 32)
	if err != nil || pid == 0 {
		t.Fatal("expected target PID required")
	}
	manifest, err := os.Open(filepath.Join(artifact, "operations.csv"))
	if err != nil {
		t.Fatal(err)
	}
	defer manifest.Close()
	compiler, err := os.Open(filepath.Join(artifact, "compiler.xml"))
	if err != nil {
		t.Fatal(err)
	}
	defer compiler.Close()
	records, err := os.Open(os.Getenv("MW_CAPTURE_JSONL"))
	if err != nil {
		t.Fatal(err)
	}
	defer records.Close()
	r, validationErr := corpus.Analyze(manifest, records, compiler, uint32(pid), 1000)
	encoded, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(artifact, "inline-report.json"), append(encoded, '\n'), 0600); err != nil {
		t.Fatal(err)
	}
	log, err := os.ReadFile(filepath.Join(artifact, "target.log"))
	if err != nil || !strings.Contains(string(log), "completed 3000") {
		t.Fatalf("fixture did not complete: %v", err)
	}
	if validationErr != nil {
		t.Fatal(validationErr)
	}
}
