package benchmark

import (
	"encoding/json"
	"errors"
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestBenchmarkLossControlRequiresExactMissingToken(t *testing.T) {
	missing := 42
	options := benchmarkOptions{ExpectMissing: 1, ExpectMissingSequence: &missing}
	report := RunReport{Unobserved: 1, MissingSequences: []int{43}, CorrectSegments: 10}
	if err := validateReport(options, "full", report); err == nil {
		t.Fatal("wrong missing token admitted")
	}
	report.MissingSequences = []int{42}
	if err := validateReport(options, "full", report); err != nil {
		t.Fatal(err)
	}
	report.CorrectSegments = 0
	if err := validateReport(options, "full", report); err == nil {
		t.Fatal("full mode without useful enrichment admitted")
	}
}

func TestBenchmarkConfigurationKeepsAcquisitionBounds(t *testing.T) {
	for _, args := range [][]string{{"--operations", "0"}, {"--repeats", "11"}, {"--operations", "100000", "--pause-ns", "10000000"}, {"--post-attach-warmup", "1", "--diversity", "8"}, {"--drain-full", "--modes", "events"}, {"--drain-events", "--modes", "full"}, {"--expect-missing-sequence", "42"}} {
		if _, err := parseBenchmarkOptions(args); err == nil {
			t.Fatalf("invalid bounded benchmark options admitted: %v", args)
		}
	}
	if _, err := parseBenchmarkOptions([]string{"--operations", "200", "--warmup", "200", "--repeats", "1", "--expect-missing", "1", "--expect-missing-sequence", "42"}); err != nil {
		t.Fatal(err)
	}
}

func TestBenchmarkRetainsAmbiguousAcquisitionAndOriginalFailure(t *testing.T) {
	for _, denied := range []bool{false, true} {
		root := t.TempDir()
		script := filepath.Join(root, "docker-fixture.sh")
		const fake = `case "$1" in
run|create)
  printf '%s\n' "$@" > request
  printf owned > present
  printf 'ambiguous creation transport failure' >&2
  exit 13;;
ps)
  if [ -e query-denied ]; then printf 'query permission denied' >&2; exit 13; fi
  printf '%s\n' "$@" > query
  if [ -e present ]; then printf '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n'; fi;;
rm) rm present;;
*) printf 'unexpected command' >&2; exit 2;;
esac
`
		if err := os.WriteFile(script, []byte(fake), 0600); err != nil {
			t.Fatal(err)
		}
		if denied {
			if err := os.WriteFile(filepath.Join(root, "query-denied"), nil, 0600); err != nil {
				t.Fatal(err)
			}
		}
		docker := driver.Docker{Root: root, Prefix: []string{"sh", script}}
		_, failure := runOne(root, benchmarkOptions{Operations: 1}, "baseline", 0, root, docker, map[string]string{"jdk": "fixture"}, "patch", "image")
		var exit *exec.ExitError
		if !errors.As(failure, &exit) || exit.ExitCode() != 13 {
			t.Fatalf("original create failure lost: %v", failure)
		}
		var report struct {
			Retired bool                             `json:"resources_retired"`
			Pending []struct{ Name, Session string } `json:"pending_acquisitions"`
		}
		data, err := os.ReadFile(filepath.Join(root, "baseline-0", "cleanup.json"))
		if err != nil {
			t.Error("ambiguous acquisition has no cleanup record", err)
			continue
		}
		if err := json.Unmarshal(data, &report); err != nil {
			t.Fatal(err)
		}
		if denied {
			if report.Retired || len(report.Pending) != 1 || report.Pending[0].Name == "" || report.Pending[0].Session == "" {
				t.Fatalf("pending qualified request lost: %+v", report)
			}
		} else {
			if !report.Retired || len(report.Pending) != 0 {
				t.Fatalf("recoverable receipt not retired: %+v", report)
			}
			if _, err := os.Stat(filepath.Join(root, "present")); !os.IsNotExist(err) {
				t.Fatal("remote acquisition still present")
			}
		}
		if _, err := os.Stat(filepath.Join(root, "baseline-0", "run-report.json")); !os.IsNotExist(err) {
			t.Fatal("failure published success")
		}
	}
}
