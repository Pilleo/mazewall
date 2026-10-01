//go:build integration

package integration

import (
	"bufio"
	"encoding/json"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"io.mazewall/experiments/hotspot-agentless/internal/quality"
	"os"
	"strings"
	"testing"
)

func TestHotSpotQualityMetadataIsDelivered(t *testing.T) {
	TestStructuredCaptureHasOneTerminalPerEventAndTypedJavaFrames(t)
	f, err := os.Open(os.Getenv("MW_CAPTURE_JSONL"))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 4096), 4*1024*1024)
	application := 0
	for scanner.Scan() {
		var r model.Record
		if err := json.Unmarshal(scanner.Bytes(), &r); err != nil {
			t.Fatal(err)
		}
		if r.Evidence == nil || r.Evidence.Attribution != "event_correlated" {
			continue
		}
		c := quality.Classify(r.Evidence.Frames)
		if r.Evidence.Walk != c.Walk || r.Evidence.ScopeLookup != c.ScopeLookup || r.Evidence.Walk == "complete" {
			t.Fatal("unsupported stack quality claim")
		}
		for _, frame := range r.Evidence.Frames {
			if frame.BCI != nil && (frame.BCIState != "observed_in_range" || frame.ScopeLookup == "method_only" || frame.ScopeLookup == "stub") {
				t.Fatal("fallback/stub BCI claimed available")
			}
			if strings.Contains(frame.FunctionName, "InlineOpenFixture") {
				if frame.ScopeLookup == "" || frame.ScopeLookup == "unknown" || frame.BCIState == "" {
					t.Fatal("fixture frame missing provider quality")
				}
				application++
				if os.Getenv("MW_JAVA_XINT") == "true" && frame.ScopeLookup != "interpreter" {
					t.Fatal("interpreter fixture claimed compiled scope lookup")
				}
			}
		}
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	if application == 0 {
		t.Fatal("no genuine application quality delivered")
	}
}
