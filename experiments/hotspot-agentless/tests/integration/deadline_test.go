//go:build integration

package integration

import (
	"bufio"
	"encoding/json"
	"os"
	"strings"
	"testing"

	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

// The isolated experiment selects a 1ns original-event budget; production stays at 1s.
func TestNativeOriginalDeadlineCannotRepairExpiredEvent(t *testing.T) {
	path := os.Getenv("MW_CAPTURE_JSONL")
	if path == "" {
		t.Skip("requires real isolated deadline capture")
	}
	file, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 4096), 1<<20)
	marked := map[string]bool{}
	expired := 0
	for scanner.Scan() {
		var r model.Record
		if err := json.Unmarshal(scanner.Bytes(), &r); err != nil {
			t.Fatal(err)
		}
		if r.Event != nil && strings.Contains(r.Event.Filename, "mw-inline-open-") {
			marked[r.EventID] = true
		}
		if r.Evidence != nil && marked[r.EventID] {
			if r.Evidence.Attribution == "event_correlated" || len(r.Evidence.Frames) != 0 {
				t.Fatal("expired event acquired substitute useful frames")
			}
			if r.Evidence.Reason == "deadline_exceeded" {
				expired++
			}
		}
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	if len(marked) != 400 || expired == 0 {
		t.Fatalf("marked=%d expired=%d", len(marked), expired)
	}
}
