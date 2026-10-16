package driver

import (
	"encoding/csv"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func RecordEnvironment(root, kind string, args []string) error {
	minimum := map[string]int{"corpus": 5, "unloading": 3, "reference": 2}[kind]
	if minimum == 0 || len(args) < minimum {
		return fmt.Errorf("invalid %s environment arguments", kind)
	}
	kernel, err := Output(root, 5*time.Second, "uname", "-r")
	if err != nil {
		return err
	}
	data := map[string]any{}
	out := args[0]
	var sources []string
	var classPath, classKey string
	if kind == "reference" {
		data = map[string]any{"jdk_image": args[1], "target_uid": os.Getuid(), "kernel": strings.TrimSpace(kernel), "reference": "USER_NOTIF", "independent_jvm": true}
		sources = []string{"fixtures/java/InlineReferenceFixture.java"}
		classPath = "build/reference-classes/InlineReferenceFixture.class"
		classKey = "class_sha256"
	} else {
		pidIndex := 1
		if kind == "corpus" {
			pidIndex = 2
		}
		pid, err := strconv.Atoi(args[pidIndex])
		if err != nil || pid <= 0 {
			return fmt.Errorf("invalid environment host PID: %q", args[pidIndex])
		}
		var patch any
		if err := ReadJSON(filepath.Join(root, "build/patch-manifest.json"), &patch); err != nil {
			return err
		}
		image, err := os.ReadFile(filepath.Join(root, "build/gadget-image.ref"))
		if err != nil {
			return err
		}
		data = map[string]any{"host_pid": pid, "jdk_image": args[pidIndex+1], "jvm_flags": args[pidIndex+2:], "kernel_release": strings.TrimSpace(kernel), "target_agent_injected": false, "gadget_image": strings.TrimSpace(string(image)), "patch_manifest": patch}
		if kind == "corpus" {
			workers, err := strconv.Atoi(args[4])
			if err != nil || (workers != 1 && workers != 4 && workers != 16) {
				return fmt.Errorf("invalid corpus worker count")
			}
			var dependencies any
			if err := ReadJSON(filepath.Join(root, "dependencies.lock.json"), &dependencies); err != nil {
				return err
			}
			runtimeMode := os.Getenv("MW_CORPUS_MODE")
			if runtimeMode == "" {
				runtimeMode = "c2"
			}
			data["run"] = args[1]
			data["jvm_flags"] = args[5:]
			data["workers"] = workers
			data["runtime_mode"] = runtimeMode
			data["target_uid"] = os.Getuid()
			data["per_path"] = 1000
			data["warmup_per_path"] = 3000
			data["event_semantics"] = "openat_handler_entry"
			data["dependencies"] = dependencies
			sources = []string{"fixtures/java/InlineCorpusFixture.java", "internal/corpus/compiler.go", "internal/corpus/report.go", "tests/integration/corpus_test.go", "scripts/run-demo.sh", "internal/driver/evidence.go", "cmd/mw-experiment-tools/main.go"}
			classPath = "build/inline-corpus-classes/InlineCorpusFixture.class"
			classKey = "class_sha256"
		} else {
			sources = []string{"fixtures/java/UnloadDriver.java", "fixtures/java/UnloadG00000.java", "internal/unloading/report.go", "tests/integration/unloading_test.go", "scripts/run-demo.sh", "internal/driver/evidence.go", "cmd/mw-experiment-tools/main.go"}
			classPath = "build/unload-classes/UnloadG00000.class"
			classKey = "template_class_sha256"
		}
	}
	hashes := map[string]string{}
	for _, source := range sources {
		hash, err := HashFile(filepath.Join(root, source))
		if err != nil {
			return err
		}
		hashes[source] = hash
	}
	if kind == "reference" {
		data["source_sha256"] = hashes[sources[0]]
	} else {
		data["source_sha256"] = hashes
	}
	classHash, err := HashFile(filepath.Join(root, classPath))
	if err != nil {
		return err
	}
	data[classKey] = classHash
	return WriteJSON(filepath.Join(out, kind+"-environment.json"), data)
}

func WriteMatrixReport(directory string) error {
	input, err := os.Open(filepath.Join(directory, "runs.csv"))
	if err != nil {
		return err
	}
	rows, readErr := csv.NewReader(input).ReadAll()
	closeErr := input.Close()
	if readErr != nil {
		return readErr
	}
	if closeErr != nil {
		return closeErr
	}
	runs := []map[string]any{}
	for _, row := range rows {
		if len(row) != 3 {
			return fmt.Errorf("invalid corpus matrix row")
		}
		workers, err := strconv.Atoi(row[1])
		if err != nil {
			return err
		}
		var report json.RawMessage
		if err := ReadJSON(filepath.Join(row[2], "inline-report.json"), &report); err != nil {
			return err
		}
		runs = append(runs, map[string]any{"mode": row[0], "workers": workers, "artifact": row[2], "report": report})
	}
	return WriteJSON(filepath.Join(directory, "matrix-report.json"), map[string]any{"schema_version": 1, "runs": runs})
}

func VerifyPreflight(directory string) error {
	type result struct {
		Ready       bool `json:"ready_for_attach_test"`
		Failures    []string
		Environment struct {
			UID *int `json:"target_uid"`
		}
	}
	for _, test := range []struct {
		name    string
		failure string
	}{{"supported", ""}, {"root-refused", "privileged_target"}, {"unprivileged", "bpf_permission_denied"}} {
		var actual result
		if err := ReadJSON(filepath.Join(directory, "preflight-"+test.name+".json"), &actual); err != nil {
			return err
		}
		if test.failure == "" {
			if !actual.Ready || len(actual.Failures) != 0 || actual.Environment.UID == nil || *actual.Environment.UID != 1000 {
				return fmt.Errorf("ordinary-user preflight was not admitted")
			}
		} else {
			found := false
			for _, failure := range actual.Failures {
				found = found || failure == test.failure
			}
			if actual.Ready || !found {
				return fmt.Errorf("preflight %s did not refuse %s", test.name, test.failure)
			}
		}
	}
	return nil
}
