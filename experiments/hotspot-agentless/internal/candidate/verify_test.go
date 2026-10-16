package candidate

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const runtimeManifestJSON = `{"pid":123,"start_ticks":456,"tokens":{"/fixture/A":["Fixture.leaf","Fixture.routeA"]}}`
const runtimeRowsJSON = completeRowsJSON
const virtualOracleJSON = `{"vt-7-3-A":{"logical_id":7,"route":["Fixture.leaf","Fixture.routeA"]}}`

var virtualRowsJSON = strings.Replace(runtimeRowsJSON, `"filename":"/fixture/A"`, `"filename":"/fixture/vt-7-3-A","thread_identity":{"scope":"mounted_virtual","source":"candidate_native","logical_id":7,"object_lease":true,"mount_capture":1,"mount_decode":1,"parent_available":false}`, 1)
var virtualTarget = TargetIdentity{PID: 123, StartTicks: 456, FixtureRoot: "/fixture"}

func decode[T any](t *testing.T, value string) T {
	t.Helper()
	var result T
	if err := json.Unmarshal([]byte(value), &result); err != nil {
		t.Fatal(err)
	}
	return result
}

func TestRuntimeCandidatePreservesUsefulUnavailableAndNativeOnlyOutcomes(t *testing.T) {
	manifest := decode[RuntimeManifest](t, runtimeManifestJSON)
	rows := decode[[]Record](t, runtimeRowsJSON)
	result, err := VerifyRuntime(manifest, rows)
	if err != nil || result.Useful != 1 || result.LifetimeQualified {
		t.Fatalf("valid bounded route: %+v %v", result, err)
	}
	for _, attribution := range []string{"unavailable", "event_correlated"} {
		rows = decode[[]Record](t, runtimeRowsJSON)
		rows[1].Evidence.Attribution = attribution
		rows[1].Evidence.Frames = []Frame{}
		if attribution == "unavailable" {
			zero, one := uint64(0), uint64(1)
			rows[3].Summary.Enrichment.Resolved = &zero
			rows[3].Summary.Enrichment.Unavailable = &one
		}
		if attribution == "event_correlated" {
			rows[1].Evidence.Frames = []Frame{{FunctionName: "libc.openat"}}
		}
		result, err = VerifyRuntime(manifest, rows)
		if err != nil || result.Useful != 0 || result.ApplicationUnavailable != 1 {
			t.Fatalf("unavailable application route: %+v %v", result, err)
		}
	}
}

func TestRuntimeCandidateRejectsWrongRouteGenerationDuplicatesAndMissing(t *testing.T) {
	manifest := decode[RuntimeManifest](t, runtimeManifestJSON)
	for _, variant := range []string{strings.ReplaceAll(runtimeRowsJSON, "Fixture.routeA", "Fixture.routeB"), strings.Replace(runtimeRowsJSON, "456", "457", 1), runtimeRowsJSON[:len(runtimeRowsJSON)-1] + "," + runtimeRowsJSON[1:]} {
		if _, err := VerifyRuntime(manifest, decode[[]Record](t, variant)); err == nil {
			t.Fatal("corrupt runtime evidence admitted")
		}
	}
	rows := decode[[]Record](t, runtimeRowsJSON)
	for _, variant := range [][]Record{rows[:1], rows[1:], nil} {
		if _, err := VerifyRuntime(manifest, variant); err == nil {
			t.Fatal("missing evidence admitted")
		}
	}
}

func TestVirtualCandidateRequiresCapturedLeaseAndMountIdentity(t *testing.T) {
	oracle := decode[map[string]VirtualOracle](t, virtualOracleJSON)
	rows := decode[[]Record](t, virtualRowsJSON)
	result, err := VerifyVirtual(oracle, rows, virtualTarget)
	if err != nil || result.LogicalObserved != 1 || result.ProductionQualified || result.ParentAvailable {
		t.Fatalf("valid bounded virtual route: %+v %v", result, err)
	}
	rows[0].Event.Identity = nil
	result, err = VerifyVirtual(oracle, rows, virtualTarget)
	if err != nil || result.LogicalObserved != 0 {
		t.Fatalf("carrier-only outcome: %+v %v", result, err)
	}
	for _, change := range [][2]string{{`"logical_id":7`, `"logical_id":8`}, {`"mount_decode":1`, `"mount_decode":2`}, {`"mount_capture":1`, `"mount_capture":0`}, {`"object_lease":true`, `"object_lease":false`}, {`"source":"candidate_native"`, `"source":"oracle"`}, {`"parent_available":false`, `"parent_available":true`}, {"Fixture.routeA", "Fixture.routeB"}} {
		if _, err := VerifyVirtual(oracle, decode[[]Record](t, strings.Replace(virtualRowsJSON, change[0], change[1], 1)), virtualTarget); err == nil {
			t.Fatalf("virtual corruption admitted: %v", change)
		}
	}
	rows = decode[[]Record](t, virtualRowsJSON)
	for _, variant := range [][]Record{rows[:1], append(rows, rows...), nil} {
		if _, err := VerifyVirtual(oracle, variant, virtualTarget); err == nil {
			t.Fatal("missing/duplicate virtual evidence admitted")
		}
	}
}

func TestAttachmentReadinessRequiresCompleteActualRecord(t *testing.T) {
	path := filepath.Join(t.TempDir(), "capture")
	for _, test := range []struct {
		input string
		ready bool
	}{{`{"schema_version":1,"session_id":"s","kind":"collector_attached"}`, false}, {"{\"schema_version\":1,\"session_id\":\"s\",\"kind\":\"event\"}\n", false}, {"{\"schema_version\":1,\"session_id\":\"s\",\"kind\":\"collector_attached\"}\n", true}} {
		if err := os.WriteFile(path, []byte(test.input), 0644); err != nil {
			t.Fatal(err)
		}
		ready, err := Attached(path)
		if err != nil || ready != test.ready {
			t.Fatalf("readiness %q: %v %v", test.input, ready, err)
		}
	}
}

func TestOfflineCandidateFilesPreserveApplicationPrefixAndRejectDuplicateOracle(t *testing.T) {
	root := t.TempDir()
	oracle := filepath.Join(root, "oracle.jsonl")
	capture := filepath.Join(root, "capture.jsonl")
	row := `{"token":"vt-7-3-A","logical_id":7,"route":["Fixture.leaf","Fixture.routeA"]}` + "\n"
	if err := os.WriteFile(oracle, []byte(row), 0644); err != nil {
		t.Fatal(err)
	}
	rows := decode[[]Record](t, virtualRowsJSON)
	if err := os.WriteFile(filepath.Join(root, "identity.json"), []byte(`{"pid":123,"start_ticks":456,"fixture_root":"/fixture"}`), 0644); err != nil {
		t.Fatal(err)
	}
	rows[1].Evidence.Frames = append([]Frame{{FunctionName: "libc.openat"}}, rows[1].Evidence.Frames...)
	var data strings.Builder
	for _, row := range rows {
		encoded, err := json.Marshal(row)
		if err != nil {
			t.Fatal(err)
		}
		data.Write(encoded)
		data.WriteByte('\n')
	}
	if err := os.WriteFile(capture, []byte(data.String()), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := VerifyFiles(true, oracle, capture); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(oracle, []byte(row+row), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := VerifyFiles(true, oracle, capture); err == nil {
		t.Fatal("duplicate oracle silently replaced")
	}
}
