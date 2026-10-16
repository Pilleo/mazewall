package driver

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPreflightVerdictsRequireExactObservedRefusals(t *testing.T) {
	root := t.TempDir()
	valid := map[string]any{"ready_for_attach_test": true, "failures": []string{}, "environment": map[string]any{"target_uid": 1000}}
	for name, value := range map[string]any{"supported": valid, "root-refused": map[string]any{"ready_for_attach_test": false, "failures": []string{"privileged_target"}}, "unprivileged": map[string]any{"ready_for_attach_test": false, "failures": []string{"bpf_permission_denied"}}} {
		if err := WriteJSON(filepath.Join(root, "preflight-"+name+".json"), value); err != nil {
			t.Fatal(err)
		}
	}
	if err := VerifyPreflight(root); err != nil {
		t.Fatal(err)
	}
	for _, value := range []map[string]any{{"ready_for_attach_test": true, "failures": []string{"bpf_permission_denied"}}, {"ready_for_attach_test": false, "failures": []string{"unrelated_failure"}}} {
		if err := WriteJSON(filepath.Join(root, "preflight-unprivileged.json"), value); err != nil {
			t.Fatal(err)
		}
		if err := VerifyPreflight(root); err == nil {
			t.Fatal("wrong refusal accepted")
		}
	}
}

func TestReferenceEnvironmentBindsActualSourceAndClassBytes(t *testing.T) {
	root := t.TempDir()
	out := t.TempDir()
	source := filepath.Join(root, "fixtures/java/InlineReferenceFixture.java")
	class := filepath.Join(root, "build/reference-classes/InlineReferenceFixture.class")
	for _, path := range []string{source, class} {
		if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(path), 0644); err != nil {
			t.Fatal(err)
		}
	}
	if err := RecordEnvironment(root, "reference", []string{out, "pinned-image"}); err != nil {
		t.Fatal(err)
	}
	var before, after map[string]any
	path := filepath.Join(out, "reference-environment.json")
	if err := ReadJSON(path, &before); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(source, []byte("changed source"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := RecordEnvironment(root, "reference", []string{out, "pinned-image"}); err != nil {
		t.Fatal(err)
	}
	if err := ReadJSON(path, &after); err != nil {
		t.Fatal(err)
	}
	if before["source_sha256"] == after["source_sha256"] || before["class_sha256"] != after["class_sha256"] {
		t.Fatal("source/class identities not bound independently")
	}
	if err := os.Remove(class); err != nil {
		t.Fatal(err)
	}
	if err := RecordEnvironment(root, "reference", []string{out, "pinned-image"}); err == nil {
		t.Fatal("missing class accepted")
	}
}

func TestMatrixReportReadsRealCSVPathsAndPreservesUnknownReportFields(t *testing.T) {
	root := t.TempDir()
	artifact := filepath.Join(root, "capture,one")
	if err := os.Mkdir(artifact, 0755); err != nil {
		t.Fatal(err)
	}
	if err := WriteJSON(filepath.Join(artifact, "inline-report.json"), map[string]any{"actual": true, "unknown_future_quality": 7}); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "runs.csv"), []byte("c2,4,\""+artifact+"\"\n"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := WriteMatrixReport(root); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(root, "matrix-report.json"))
	if err != nil || !strings.Contains(string(data), "unknown_future_quality") {
		t.Fatalf("matrix report lost actual payload: %s %v", data, err)
	}
}
