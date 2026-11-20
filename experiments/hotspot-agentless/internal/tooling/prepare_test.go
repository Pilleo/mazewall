package tooling

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestKobjectWitnessPreparationInputs(t *testing.T) {
	prefix := []string{
		"profiler-health.patch", "profiler-quality.patch", "profiler-identity.patch",
		"profiler-epoch-capture.patch", "profiler-epoch-memory.patch", "profiler-epoch-handler.patch",
		"profiler-shutdown.patch", "profiler-definition-drain.patch", "profiler-metadata-retirement.patch",
		"profiler-auxiliary-ownership.patch", "profiler-target-bootstrap.patch", "profiler-pid-process.patch",
		"profiler-periodic-accounting.patch", "profiler-reload-accounting.patch",
	}
	const witnessPatch = "profiler-kobject-witness.patch"
	if len(profilerPatches) != len(prefix)+1 {
		t.Fatalf("patch count: %d", len(profilerPatches))
	}
	for i, want := range prefix {
		if profilerPatches[i] != want {
			t.Fatalf("patch prefix %d: %s", i, profilerPatches[i])
		}
	}
	if profilerPatches[len(prefix)] != witnessPatch {
		t.Fatal("witness patch is not fifteenth")
	}
	paths, err := preparationInputs(filepath.Join("..", ".."), standard)
	if err != nil {
		t.Fatal(err)
	}
	counts := make(map[string]int)
	start := -1
	for i, name := range paths {
		counts[name]++
		if name == "patches/"+prefix[0] {
			start = i
		}
	}
	if start < 0 || start+len(profilerPatches) > len(paths) {
		t.Fatal("profiler patch inputs missing")
	}
	for i, name := range profilerPatches {
		if paths[start+i] != "patches/"+name || counts["patches/"+name] != 1 {
			t.Fatalf("patch input order or duplication: %s", name)
		}
	}
	witnessInputs := []string{
		"patches/" + witnessPatch,
		"patches/profiler-additions/tracer/kobject_witness.go.txt",
		"patches/profiler-additions/tracer/kobject_witness_test.go.txt",
		"patches/profiler-additions/tracer/kobject_witness_native.go.txt",
		"patches/profiler-additions/tracer/kobject_witness_native_test.go.txt",
		"patches/profiler-additions/tracer/kobject_witness_socket.go.txt",
		"patches/profiler-additions/tracer/kobject_witness_socket_test.go.txt",
		"patches/profiler-additions/tracer/local_snapshot.go.txt",
		"patches/profiler-additions/tracer/auxiliary_stop.go.txt",
		"patches/profiler-additions/tracer/auxiliary_stop_test.go.txt",
	}
	for _, name := range witnessInputs {
		if counts[name] != 1 {
			t.Fatalf("missing or duplicate witness input: %s", name)
		}
	}
	root := t.TempDir()
	for _, dir := range []string{"patches/gadget-additions", "patches/profiler-additions"} {
		if err := os.MkdirAll(filepath.Join(root, dir), 0755); err != nil {
			t.Fatal(err)
		}
	}
	for _, name := range paths {
		path := filepath.Join(root, name)
		if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte("original"), 0644); err != nil {
			t.Fatal(err)
		}
	}
	before, err := patchID(root, standard)
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range witnessInputs {
		path := filepath.Join(root, name)
		if err := os.WriteFile(path, []byte("changed"), 0644); err != nil {
			t.Fatal(err)
		}
		after, err := patchID(root, standard)
		if err != nil {
			t.Fatal(err)
		}
		if after == before {
			t.Fatalf("input excluded from digest: %s", name)
		}
		if err := os.WriteFile(path, []byte("original"), 0644); err != nil {
			t.Fatal(err)
		}
	}
}

func TestPreparedContainerdGraphUsesPatchedV2Client(t *testing.T) {
	root, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, "go", "list", "-mod=readonly", "-modfile="+filepath.Join(root, "build", "experiment.go.mod"), "-deps", "-f", "{{if .Module}}{{.Module.Path}} {{.Module.Version}}{{end}}", "./...", "github.com/inspektor-gadget/inspektor-gadget/pkg/container-utils/testutils", "github.com/inspektor-gadget/inspektor-gadget/cmd/ig/utils")
	cmd.Dir = root
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("selected dependency graph: %v\n%s", err, out)
	}
	found := false
	for _, line := range strings.Split(string(out), "\n") {
		fields := strings.Fields(line)
		if len(fields) == 0 {
			continue
		}
		switch fields[0] {
		case "github.com/containerd/containerd":
			t.Fatal("prepared collector/tools still import the containerd v1 client")
		case "github.com/containerd/containerd/v2":
			if len(fields) != 2 || fields[1] != "v2.3.7" {
				t.Fatalf("unexpected containerd v2 selection: %q", line)
			}
			found = true
		}
	}
	if !found {
		t.Fatal("prepared collector/tools do not use the patched containerd v2 client")
	}
}

func TestPreparationIdentityMatchesAcceptedSource(t *testing.T) {
	root := filepath.Join("..", "..")
	id, err := patchID(root, standard)
	if err != nil {
		t.Fatal(err)
	}
	// Job 5d preparation identity computed over current patches, additions, and scripts/rebuild-profiler-ebpf/main.go
	if id != "0a84c7bd09649a5d671f7fbf954692feb6943c4cccf965c8ebcb5dc9e4b66e54" {
		t.Fatalf("hardened preparation identity changed: %s", id)
	}
	identity, err := patchID(root, identityObservations)
	if err != nil {
		t.Fatal(err)
	}
	recovery, err := patchID(root, dropFirstDefinition)
	if err != nil {
		t.Fatal(err)
	}
	if identity == id || recovery == id || identity == recovery {
		t.Fatal("different preparation modes must not reuse provenance")
	}
}

func TestIncompatiblePreparationModesFailBeforeAcquisition(t *testing.T) {
	if err := Run(t.TempDir(), []string{"prepare", "--drop-first-definition", "--identity-observations"}); err == nil {
		t.Fatal("incompatible preparation modes admitted")
	}
}

func TestPreparedProvenanceRequiresEveryExpectedField(t *testing.T) {
	path := filepath.Join(t.TempDir(), "marker.json")
	expected := map[string]any{"base_sum": "h1:known", "capture_abi": float64(2), "fault_injection": nil}
	if err := os.WriteFile(path, []byte(`{"base_sum":"h1:known","capture_abi":2,"fault_injection":null}`), 0644); err != nil {
		t.Fatal(err)
	}
	if ok, err := markerMatches(path, expected); err != nil || !ok {
		t.Fatalf("valid marker: %v %v", ok, err)
	}
	if err := os.WriteFile(path, []byte(`{"base_sum":"h1:known","capture_abi":2}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := markerMatches(path, expected); err == nil {
		t.Fatal("missing provenance field admitted")
	}
	if err := os.WriteFile(path, []byte(`{"base_sum":"h1:changed","capture_abi":2,"fault_injection":null}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := markerMatches(path, expected); err == nil {
		t.Fatal("changed module provenance admitted")
	}
}

func TestEpochArtifactsRequireCompleteUnchangedNativeBytes(t *testing.T) {
	root := t.TempDir()
	files := map[string]string{}
	for _, name := range []string{"support/types.go", "support/ebpf/tracer.ebpf.amd64", "support/ebpf/tracer.ebpf.arm64"} {
		if err := os.MkdirAll(filepath.Dir(filepath.Join(root, name)), 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(root, name), []byte("a"), 0644); err != nil {
			t.Fatal(err)
		}
		files[name] = "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"
	}
	if err := writeJSON(filepath.Join(root, ".mw-epoch-artifacts.json"), files); err != nil {
		t.Fatal(err)
	}
	if err := verifyEpochArtifacts(root); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "support/types.go"), []byte("tampered"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := verifyEpochArtifacts(root); err == nil {
		t.Fatal("changed generated native bytes admitted")
	}
	if err := writeJSON(filepath.Join(root, ".mw-epoch-artifacts.json"), map[string]string{}); err != nil {
		t.Fatal(err)
	}
	if err := verifyEpochArtifacts(root); err == nil {
		t.Fatal("empty native inventory admitted")
	}
}

func TestPreparationIdentityReadsActualBytesAndRefusesMissingInputs(t *testing.T) {
	root := t.TempDir()
	paths, err := preparationInputs(filepath.Join("..", ".."), standard)
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range paths {
		if err := os.MkdirAll(filepath.Dir(filepath.Join(root, name)), 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(root, name), []byte("first"), 0644); err != nil {
			t.Fatal(err)
		}
	}
	before, err := patchID(root, standard)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, paths[0]), []byte("changed"), 0644); err != nil {
		t.Fatal(err)
	}
	after, err := patchID(root, standard)
	if err != nil {
		t.Fatal(err)
	}
	if before == after {
		t.Fatal("changed patch bytes reused preparation identity")
	}
	if err := os.Remove(filepath.Join(root, paths[0])); err != nil {
		t.Fatal(err)
	}
	if _, err := patchID(root, standard); err == nil {
		t.Fatal("missing patch input admitted")
	}
}

func TestPreparationRetainedA1AdditionsMirrorIntegrity(t *testing.T) {
	// Source-input and mirror integrity for the 11 retained A1 additions.
	retained := []string{
		"patches/profiler-additions/libpf/definition_local.go.txt",
		"patches/profiler-additions/periodiccaller/kind_book.go.txt",
		"patches/profiler-additions/periodiccaller/kind_local_snapshot_test.go.txt",
		"patches/profiler-additions/periodiccaller/local_snapshot.go.txt",
		"patches/profiler-additions/periodiccaller/owned_scope.go.txt",
		"patches/profiler-additions/periodiccaller/scope_local_snapshot_test.go.txt",
		"patches/profiler-additions/tracer/local_snapshot.go.txt",
		"patches/profiler-additions/tracer/pid_mailbox.go.txt",
		"patches/profiler-additions/tracer/pid_mailbox_local_snapshot_test.go.txt",
		"patches/profiler-additions/tracer/pid_process.go.txt",
		"patches/profiler-additions/tracer/pid_process_local_snapshot_test.go.txt",
	}
	moduleRoot, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatalf("failed to resolve module root: %v", err)
	}
	repoRoot := filepath.Clean(filepath.Join(moduleRoot, "..", ".."))
	canonicalBase := filepath.Join(repoRoot, "profiler", "hotspot-agentless")
	mirrorBase := filepath.Join(repoRoot, "experiments", "hotspot-agentless")

	for _, rel := range retained {
		cPath := filepath.Join(canonicalBase, rel)
		mPath := filepath.Join(mirrorBase, rel)
		cBytes, err := os.ReadFile(cPath)
		if err != nil {
			t.Fatalf("missing canonical retained addition %s: %v", rel, err)
		}
		mBytes, err := os.ReadFile(mPath)
		if err != nil {
			t.Fatalf("missing mirror retained addition %s: %v", rel, err)
		}
		if !bytes.Equal(cBytes, mBytes) {
			t.Fatalf("canonical and mirror addition mismatch: %s", rel)
		}
	}
}

func TestPreparationIdentityRequiresProductionHelper(t *testing.T) {
	root := t.TempDir()
	paths, err := preparationInputs(filepath.Join("..", ".."), standard)
	if err != nil {
		t.Fatal(err)
	}
	helperFound := false
	for _, name := range paths {
		if name == "scripts/rebuild-profiler-ebpf/main.go" {
			helperFound = true
		}
		if err := os.MkdirAll(filepath.Dir(filepath.Join(root, name)), 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(root, name), []byte("content"), 0644); err != nil {
			t.Fatal(err)
		}
	}
	if !helperFound {
		t.Fatal("preparationInputs must include scripts/rebuild-profiler-ebpf/main.go")
	}
	before, err := patchID(root, standard)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "scripts/rebuild-profiler-ebpf/main.go"), []byte("altered"), 0644); err != nil {
		t.Fatal(err)
	}
	after, err := patchID(root, standard)
	if err != nil {
		t.Fatal(err)
	}
	if before == after {
		t.Fatal("altering production helper did not change preparation identity")
	}
	if err := os.Remove(filepath.Join(root, "scripts/rebuild-profiler-ebpf/main.go")); err != nil {
		t.Fatal(err)
	}
	if _, err := patchID(root, standard); err == nil {
		t.Fatal("missing production helper admitted in patchID")
	}
}

func TestPreparationFailsIfNativeBuildFails(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	root, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	tmpDir := t.TempDir()
	ownedProvider := filepath.Join(tmpDir, "profiler-fixture")
	if err := os.MkdirAll(ownedProvider, 0700); err != nil {
		t.Fatal(err)
	}
	scriptPath := filepath.Join(root, "scripts", "rebuild-profiler-ebpf.sh")
	deadline := time.Now().Add(60 * time.Second).Unix()

	var stderrBuf bytes.Buffer
	cmd := exec.CommandContext(ctx, "bash", scriptPath, ownedProvider, fmt.Sprintf("%d", deadline))
	cmd.Dir = root
	cmd.Stderr = &stderrBuf
	err = cmd.Run()
	if err == nil {
		t.Fatal("expected rebuild script to fail closed on invalid/empty provider tree, got nil")
	}
	if ctx.Err() != nil {
		t.Fatalf("rebuild script timed out instead of failing cleanly: %v (stderr: %s)", ctx.Err(), stderrBuf.String())
	}

	markerPath := filepath.Join(ownedProvider, ".mw-prepared.json")
	if _, err := os.Stat(markerPath); !os.IsNotExist(err) {
		t.Fatal("marker exists after failed native rebuild command")
	}
}

func TestRebuildScriptStrictArgumentForwarding(t *testing.T) {
	root, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	scriptPath := filepath.Join(root, "scripts", "rebuild-profiler-ebpf.sh")
	ownedProvider := filepath.Join(t.TempDir(), "provider")
	if err := os.MkdirAll(ownedProvider, 0700); err != nil {
		t.Fatal(err)
	}
	future := fmt.Sprintf("%d", time.Now().Add(120*time.Second).Unix())

	cases := []struct {
		name string
		args []string
	}{
		{name: "missing_all_args", args: nil},
		{name: "missing_deadline", args: []string{ownedProvider}},
		{name: "extra_arg", args: []string{ownedProvider, future, "extra"}},
		{name: "zero_deadline", args: []string{ownedProvider, "0"}},
		{name: "negative_deadline", args: []string{ownedProvider, "-10"}},
		{name: "non_numeric_deadline", args: []string{ownedProvider, "notanumber"}},
		{name: "missing_provider_dir", args: []string{filepath.Join(t.TempDir(), "missing"), future}},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			cmdArgs := append([]string{scriptPath}, tc.args...)
			cmd := exec.CommandContext(ctx, "bash", cmdArgs...)
			cmd.Dir = root
			out, err := cmd.CombinedOutput()
			if err == nil {
				t.Fatalf("expected failure for %s, got nil (output: %s)", tc.name, string(out))
			}
			if ctx.Err() != nil {
				t.Fatalf("argument rejection timed out for %s: %v", tc.name, ctx.Err())
			}
		})
	}
}

func TestPrepareBudgetSingleCaptureAndConservativeUnixRounding(t *testing.T) {
	if prepareStageBudget != 540*time.Second {
		t.Fatalf("expected prepareStageBudget 540s, got %v", prepareStageBudget)
	}
	if clientReturnAllowance < prepareWaitDelay {
		t.Fatalf("clientReturnAllowance %v must cover prepareWaitDelay %v", clientReturnAllowance, prepareWaitDelay)
	}

	for _, ns := range []int64{0, 1_000_000, 500_000_000, 999_999_999} {
		overallEnd := time.Unix(1_700_000_540, ns)
		builderEnd := deriveBuilderDeadline(overallEnd)
		if builderEnd.Nanosecond() != 0 {
			t.Fatalf("builderEnd must be whole Unix seconds, got %v", builderEnd)
		}
		if builderEnd.After(overallEnd.Add(-clientReturnAllowance)) {
			t.Fatalf("builderEnd %v exceeded overallEnd - clientReturnAllowance (%v)", builderEnd, overallEnd.Add(-clientReturnAllowance))
		}
		slack := overallEnd.Sub(builderEnd)
		if slack < clientReturnAllowance || slack >= clientReturnAllowance+time.Second {
			t.Fatalf("unexpected slack for ns=%d: %v", ns, slack)
		}
	}
}

func TestRebuildScriptRejectsInvalidOrExpiredDeadlineBeforeGoRun(t *testing.T) {
	root, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	scriptPath := filepath.Join(root, "scripts", "rebuild-profiler-ebpf.sh")
	ownedProvider := filepath.Join(t.TempDir(), "provider")
	if err := os.MkdirAll(ownedProvider, 0700); err != nil {
		t.Fatal(err)
	}

	fakeBinDir := t.TempDir()
	logPath := filepath.Join(t.TempDir(), "fake-go.log")
	fakeGoPath := filepath.Join(fakeBinDir, "go")
	fakeGoScript := "#!/usr/bin/env bash\nprintf 'GOWORK=%s ARGS=%s\\n' \"${GOWORK:-}\" \"$*\" >> " + fmt.Sprintf("%q", logPath) + "\nexit 0\n"
	if err := os.WriteFile(fakeGoPath, []byte(fakeGoScript), 0755); err != nil {
		t.Fatal(err)
	}
	env := append(os.Environ(), "PATH="+fakeBinDir+string(os.PathListSeparator)+os.Getenv("PATH"))

	now := time.Now().Unix()
	rejected := []struct {
		name string
		arg  string
	}{
		{name: "expired_past", arg: fmt.Sprintf("%d", now-5)},
		{name: "expired_now", arg: fmt.Sprintf("%d", now-1)},
		{name: "int64_overflow_max_plus_one", arg: "9223372036854775808"},
		{name: "huge_20_digit_overflow", arg: "99999999999999999999"},
		{name: "zero", arg: "0"},
		{name: "negative", arg: "-100"},
		{name: "leading_zero", arg: "01700000000"},
	}

	for _, tc := range rejected {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			cmd := exec.CommandContext(ctx, "bash", scriptPath, ownedProvider, tc.arg)
			cmd.Dir = root
			cmd.Env = env
			out, err := cmd.CombinedOutput()
			if err == nil {
				t.Fatalf("expected shell rejection for %s (%s), got nil (out=%s)", tc.name, tc.arg, string(out))
			}
			if _, statErr := os.Stat(logPath); !os.IsNotExist(statErr) {
				logged, _ := os.ReadFile(logPath)
				t.Fatalf("fake go was unexpectedly invoked for %s (%s): %s", tc.name, tc.arg, string(logged))
			}
		})
	}

	t.Run("valid_future_invokes_fake_go_with_readonly_and_gowork_off", func(t *testing.T) {
		future := fmt.Sprintf("%d", time.Now().Add(120*time.Second).Unix())
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		cmd := exec.CommandContext(ctx, "bash", scriptPath, ownedProvider, future)
		cmd.Dir = root
		cmd.Env = env
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("expected valid future invocation to reach fake go, got err=%v out=%s", err, string(out))
		}
		loggedBytes, err := os.ReadFile(logPath)
		if err != nil {
			t.Fatalf("expected fake go log file, got %v", err)
		}
		logged := strings.TrimSpace(string(loggedBytes))
		if !strings.Contains(logged, "GOWORK=off") || !strings.Contains(logged, "-mod=readonly") || !strings.Contains(logged, "--deadline "+future) {
			t.Fatalf("unexpected fake go invocation: %q", logged)
		}
	})
}
