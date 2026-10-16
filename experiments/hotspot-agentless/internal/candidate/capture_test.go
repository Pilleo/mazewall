package candidate

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const completeRowsJSON = `[{"schema_version":1,"kind":"event","session_id":"s","event_id":"s:0:1","event":{"session_id":"s","event_id":"s:0:1","host_pid":123,"host_tid":124,"process_start_ticks":456,"clock_domain":"CLOCK_BOOTTIME","event_kind":"openat_handler_entry","capture_boot_ns":0,"filename":"/fixture/A"}},{"schema_version":1,"kind":"enrichment","session_id":"s","event_id":"s:0:1","evidence":{"attribution":"event_correlated","frames":[{"function_name":"Fixture.leaf"},{"function_name":"Fixture.routeA"}]}},{"schema_version":1,"kind":"collector_attached","session_id":"s"},{"schema_version":1,"kind":"session_summary","session_id":"s","summary":{"enrichment":{"received":1,"resolved":1,"unavailable":0,"skipped":0},"terminal_accounting_complete":true}}]`

func TestRuntimeRequiresWholeCaptureIdentityAndPairing(t *testing.T) {
	manifest := decode[RuntimeManifest](t, runtimeManifestJSON)
	if _, err := VerifyRuntime(manifest, decode[[]Record](t, completeRowsJSON)); err != nil {
		t.Fatal(err)
	}
	for _, change := range [][2]string{
		{`"schema_version":1`, `"schema_version":99`},
		{`"session_id":"s"`, `"session_id":""`},
		{`"event_id":"s:0:1"`, `"event_id":"foreign:1"`},
		{`"host_tid":124`, `"host_tid":0`},
		{`"host_pid":123`, `"host_pid":124`},
		{`"process_start_ticks":456`, `"process_start_ticks":457`},
		{`"capture_boot_ns":0,`, ``},
		{`"filename":"/fixture/A"`, `"filename":null`},
		{`"function_name":"Fixture.leaf"`, `"function_name":null`},
		{`"clock_domain":"CLOCK_BOOTTIME"`, `"clock_domain":"CLOCK_MONOTONIC"`},
		{`"resolved":1`, `"resolved":0`},
		{`"terminal_accounting_complete":true`, `"terminal_accounting_complete":false`},
	} {
		rows := decode[[]Record](t, strings.Replace(completeRowsJSON, change[0], change[1], 1))
		if _, err := VerifyRuntime(manifest, rows); err == nil {
			t.Errorf("accepted corrupt %v", change)
		}
	}
	rows := decode[[]Record](t, completeRowsJSON)
	for _, bad := range [][]Record{rows[:2], append(rows, rows[0]), {rows[0], rows[2], rows[3]}, {rows[1], rows[2], rows[3]}} {
		if _, err := VerifyRuntime(manifest, bad); err == nil {
			t.Fatal("accepted missing pair/finality")
		}
	}
}

func TestVirtualValidatesCarrierOnlyPairsAndIndependentIdentity(t *testing.T) {
	oracle := decode[map[string]VirtualOracle](t, virtualOracleJSON)
	carrier := strings.Replace(virtualRowsJSON, `"scope":"mounted_virtual"`, `"scope":"carrier_only"`, 1)
	if result, err := VerifyVirtual(oracle, decode[[]Record](t, carrier), virtualTarget); err != nil || result.LogicalObserved != 0 {
		t.Fatalf("carrier-only: %+v %v", result, err)
	}
	for _, change := range [][2]string{
		{`"kind":"enrichment"`, `"kind":"recovery"`},
		{`"host_pid":123`, `"host_pid":124`},
		{`"filename":"/fixture/vt-7-3-A"`, `"filename":"/other/vt-7-3-A"`},
	} {
		if _, err := VerifyVirtual(oracle, decode[[]Record](t, strings.Replace(carrier, change[0], change[1], 1)), virtualTarget); err == nil {
			t.Errorf("carrier-only accepted %v", change)
		}
	}
	for _, replacement := range []string{`"parent_available":null`, `"unused":false`} {
		rows := decode[[]Record](t, strings.Replace(virtualRowsJSON, `"parent_available":false`, replacement, 1))
		if _, err := VerifyVirtual(oracle, rows, virtualTarget); err == nil {
			t.Errorf("missing/null mount boolean accepted: %s", replacement)
		}
	}
}

func TestCaptureNumericTypesAndOracleIdentityAreStrict(t *testing.T) {
	root := t.TempDir()
	oracle := filepath.Join(root, "oracle.jsonl")
	capture := filepath.Join(root, "capture.jsonl")
	if err := os.WriteFile(filepath.Join(root, "identity.json"), []byte(`{"pid":123,"start_ticks":456,"fixture_root":"/fixture"}`), 0600); err != nil {
		t.Fatal(err)
	}
	var raw []json.RawMessage
	if err := json.Unmarshal([]byte(virtualRowsJSON), &raw); err != nil {
		t.Fatal(err)
	}
	var text strings.Builder
	for _, row := range raw {
		text.Write(row)
		text.WriteByte('\n')
	}
	goodOracle := `{"token":"vt-7-3-A","logical_id":7,"route":["Fixture.leaf","Fixture.routeA"]}` + "\n"
	if err := os.WriteFile(oracle, []byte(goodOracle), 0600); err != nil {
		t.Fatal(err)
	}
	for _, change := range [][2]string{{`"schema_version":1`, `"schema_version":1.0`}, {`"host_tid":124`, `"host_tid":true`}, {`"capture_boot_ns":0`, `"capture_boot_ns":null`}, {`"logical_id":7`, `"logical_id":7.0`}, {`"mount_decode":1`, `"mount_decode":true`}, {`"object_lease":true`, `"object_lease":1`}} {
		if err := os.WriteFile(capture, []byte(strings.Replace(text.String(), change[0], change[1], 1)), 0600); err != nil {
			t.Fatal(err)
		}
		if _, err := VerifyFiles(true, oracle, capture); err == nil {
			t.Errorf("accepted noninteger/nonboolean %v", change)
		}
	}
	if err := os.WriteFile(capture, []byte(text.String()), 0600); err != nil {
		t.Fatal(err)
	}
	for _, bad := range []string{strings.Replace(goodOracle, `"logical_id":7`, `"logical_id":7.0`, 1), strings.Replace(goodOracle, `"logical_id":7`, `"logical_id":null`, 1), goodOracle + goodOracle} {
		if err := os.WriteFile(oracle, []byte(bad), 0600); err != nil {
			t.Fatal(err)
		}
		if _, err := VerifyFiles(true, oracle, capture); err == nil {
			t.Error("accepted invalid/duplicate oracle identity")
		}
	}
	if err := os.WriteFile(oracle, []byte(goodOracle), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(root, "identity.json")); err != nil {
		t.Fatal(err)
	}
	if _, err := VerifyFiles(true, oracle, capture); err == nil {
		t.Error("inferred expected identity from capture")
	}
}

func TestCaptureFileRequiresNewlineAndBoundedRows(t *testing.T) {
	root := t.TempDir()
	manifest := filepath.Join(root, "manifest.json")
	capture := filepath.Join(root, "capture.jsonl")
	if err := os.WriteFile(manifest, []byte(runtimeManifestJSON), 0600); err != nil {
		t.Fatal(err)
	}
	var raw []json.RawMessage
	if err := json.Unmarshal([]byte(completeRowsJSON), &raw); err != nil {
		t.Fatal(err)
	}
	var text strings.Builder
	for _, row := range raw {
		text.Write(row)
		text.WriteByte('\n')
	}
	if err := os.WriteFile(capture, []byte(text.String()), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := VerifyFiles(false, manifest, capture); err != nil {
		t.Fatal(err)
	}
	for _, bad := range []string{strings.TrimSuffix(text.String(), "\n"), strings.Repeat(" ", (1<<20)+1) + "\n", text.String() + "\n"} {
		if err := os.WriteFile(capture, []byte(bad), 0600); err != nil {
			t.Fatal(err)
		}
		if _, err := VerifyFiles(false, manifest, capture); err == nil {
			t.Fatal("accepted truncated/oversized/blank input")
		}
	}
	for _, bad := range []string{strings.Repeat("{}\n", 65537), `{"ignored":"` + strings.Repeat("a", 1048576) + "\"}\n"} {
		if err := os.WriteFile(capture, []byte(bad), 0600); err != nil {
			t.Fatal(err)
		}
		if _, err := readLines[map[string]any](capture); err == nil {
			t.Error("reader accepted excessive rows/line")
		}
	}
}

func TestAttachmentRejectsForeignSchemaAndOversizedInput(t *testing.T) {
	path := filepath.Join(t.TempDir(), "capture")
	for _, text := range []string{"{\"schema_version\":99,\"kind\":\"collector_attached\",\"session_id\":\"s\"}\n", "{\"schema_version\":1,\"kind\":\"collector_attached\"}\n", `{"ignored":"` + strings.Repeat("a", 1048576) + "\"}\n"} {
		if err := os.WriteFile(path, []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
		if ready, err := Attached(path); err == nil || ready {
			t.Errorf("unqualified readiness: %v %v", ready, err)
		}
	}
}
