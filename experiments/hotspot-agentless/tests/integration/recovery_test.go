//go:build integration

package integration

import (
	"bufio"
	"encoding/json"
	"os"
	"regexp"
	"strconv"
	"strings"
	"testing"

	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

func TestLostFirstDefinitionRecoversSubsequentOccurrences(t *testing.T) {
	log, err := os.ReadFile(os.Getenv("MW_CAPTURE_LOG"))
	if err != nil {
		t.Fatal(err)
	}
	drops := regexp.MustCompile(`mw-fault-dropped-definition id=(\d+) pid=(\d+) tid=(\d+)`).FindAllStringSubmatch(string(log), -1)
	if len(drops) != 1 {
		t.Fatalf("expected exactly one genuine dropped application definition, got %d", len(drops))
	}
	dropped, err := strconv.ParseUint(drops[0][1], 10, 64)
	if err != nil {
		t.Fatal(err)
	}
	pid, err := strconv.ParseUint(os.Getenv("MW_TARGET_PID"), 10, 32)
	if err != nil {
		t.Fatal(err)
	}
	if drops[0][2] != strconv.FormatUint(pid, 10) {
		t.Fatal("fault applied to another process")
	}
	f, err := os.Open(os.Getenv("MW_CAPTURE_JSONL"))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 4096), 4*1024*1024)
	events := map[string]model.Event{}
	results := map[string]model.StackEvidence{}
	invalidated := 0
	var recoveryAt uint64
	for scanner.Scan() {
		var r model.Record
		if err := json.Unmarshal(scanner.Bytes(), &r); err != nil {
			t.Fatal(err)
		}
		if r.Kind == "event" {
			events[r.EventID] = *r.Event
		} else if r.Kind == "enrichment" {
			if _, ok := results[r.EventID]; ok {
				t.Fatal("terminal rewritten")
			}
			results[r.EventID] = *r.Evidence
		}
		if r.Kind == "recovery" {
			var action struct {
				Recovery struct {
					CorrelationID uint64 `json:"correlation_id"`
					Invalidated   bool   `json:"invalidated"`
					BootNS        uint64 `json:"recovery_boot_ns"`
				} `json:"recovery"`
			}
			if err := json.Unmarshal(scanner.Bytes(), &action); err != nil {
				t.Fatal(err)
			}
			if action.Recovery.CorrelationID == dropped && action.Recovery.Invalidated {
				invalidated++
				if action.Recovery.BootNS == 0 {
					t.Fatal("missing recovery clock")
				}
				recoveryAt = action.Recovery.BootNS
			}
		}
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	var unavailableAt uint64
	reused := 0
	for id, e := range events {
		if uint64(e.PID) != pid {
			t.Fatal("another target's event")
		}
		if e.CorrelationID == dropped {
			reused++
			r := results[id]
			if r.Attribution != "unavailable" || len(r.Frames) != 0 {
				t.Fatal("lost definition received substitute evidence")
			}
			if unavailableAt == 0 || e.CaptureBootNS < unavailableAt {
				unavailableAt = e.CaptureBootNS
			}
		}
	}
	if reused < 2 || unavailableAt == 0 {
		t.Fatalf("did not prove resident-cache reuse of dropped ID: %d", reused)
	}
	if invalidated == 0 {
		t.Fatal("lost first definition was not invalidated from the resident BPF cache")
	}
	recovered := 0
	for id, e := range events {
		if e.CaptureBootNS <= recoveryAt || e.CorrelationID == dropped || !strings.HasSuffix(e.Filename, "mw-inline-open-A") {
			continue
		}
		r := results[id]
		if r.Attribution != "event_correlated" {
			continue
		}
		for _, frame := range r.Frames {
			if strings.Contains(frame.FunctionName, "InlineOpenFixture.pathB") {
				t.Fatal("wrong branch substituted")
			}
			if strings.Contains(frame.FunctionName, "InlineOpenFixture.pathA") {
				recovered++
				break
			}
		}
	}
	if recovered == 0 {
		t.Fatal("lost first definition poisoned subsequent identical call-path observations")
	}
}
