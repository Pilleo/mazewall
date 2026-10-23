package capture

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/format"
	"go/parser"
	"go/token"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

// Compile the real private reader/stop owner and Run's exit boundary together;
// no native export, source-cache edit, target attach or replacement algorithm.
func TestObservedReaderPendingReadoutReturn(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	modfile, err := filepath.Abs("../../build/experiment.go.mod")
	if err != nil {
		t.Fatal(err)
	}
	moduleDir := func(module string) string {
		t.Helper()
		out, err := exec.CommandContext(ctx, "go", "list", "-modfile="+modfile, "-m", "-f", "{{.Dir}}", module).CombinedOutput()
		if err != nil {
			t.Fatalf("selected module %s: %v\n%s", module, err, out)
		}
		return string(bytes.TrimSpace(out))
	}
	temp := t.TempDir()
	cwd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	replacements := map[string]string{}
	project := func(name, source, authored string, imports []string, keep func(ast.Decl) bool) {
		t.Helper()
		data, err := os.ReadFile(source)
		if err != nil {
			t.Fatal(err)
		}
		if authored != "" {
			want, err := os.ReadFile(authored)
			if err != nil || !bytes.Equal(data, want) {
				t.Fatalf("selected/authored source differs: %s: %v", source, err)
			}
		}
		t.Logf("source %s SHA256=%x", source, sha256.Sum256(data))
		set := token.NewFileSet()
		file, err := parser.ParseFile(set, source, data, 0)
		if err != nil {
			t.Fatal(err)
		}
		var out bytes.Buffer
		out.WriteString("package capture\nimport (\n")
		for _, path := range imports {
			fmt.Fprintf(&out, "%q\n", path)
		}
		out.WriteString(")\n")
		for _, decl := range file.Decls {
			if g, ok := decl.(*ast.GenDecl); ok && g.Tok == token.IMPORT {
				continue
			}
			if keep != nil && !keep(decl) {
				continue
			}
			if err := format.Node(&out, set, decl); err != nil {
				t.Fatal(err)
			}
			out.WriteByte('\n')
		}
		path := filepath.Join(temp, name)
		if err := os.WriteFile(path, out.Bytes(), 0600); err != nil {
			t.Fatal(err)
		}
		replacements[filepath.Join(cwd, name)] = path
	}
	profiler := moduleDir("go.opentelemetry.io/ebpf-profiler")
	gadget := moduleDir("github.com/inspektor-gadget/inspektor-gadget")
	project("reader_projection_test.go", filepath.Join(profiler, "interpreter/hotspot/observed.go"), "../../patches/profiler-additions/interpreter/hotspot/observed.go.txt", []string{"errors", "fmt", "io", "math", "sync", "syscall"}, func(d ast.Decl) bool {
		f, ok := d.(*ast.FuncDecl)
		return !ok || f.Name.Name != "AttachObserved"
	})
	project("owner_projection_test.go", filepath.Join(gadget, "pkg/operators/ebpf/event_finalization_owner.go"), "../../patches/gadget-additions/pkg/operators/ebpf/event_finalization_owner.go.txt", []string{"context", "errors", "fmt", "sync", "github.com/inspektor-gadget/inspektor-gadget/pkg/operators"}, nil)
	project("owner_fixture_projection_test.go", filepath.Join(gadget, "pkg/operators/ebpf/event_finalization_owner_test.go"), "../../patches/gadget-additions/pkg/operators/ebpf/event_finalization_owner_test.go.txt", []string{"context", "errors", "sync"}, func(d ast.Decl) bool {
		f, ok := d.(*ast.FuncDecl)
		return !ok || len(f.Name.Name) < 4 || f.Name.Name[:4] != "Test"
	})
	projectRunExit(t, cwd, temp, replacements)
	fixture, err := filepath.Abs("testdata/fatal_readout_test.go")
	if err != nil {
		t.Fatal(err)
	}
	replacements[filepath.Join(cwd, "fatal_readout_fixture_test.go")] = fixture
	overlay, err := json.Marshal(struct{ Replace map[string]string }{replacements})
	if err != nil {
		t.Fatal(err)
	}
	overlayPath := filepath.Join(temp, "overlay.json")
	if err := os.WriteFile(overlayPath, overlay, 0600); err != nil {
		t.Fatal(err)
	}
	binary := filepath.Join(temp, "pending-readout.test")
	cmd := exec.CommandContext(ctx, "go", "test", "-modfile="+modfile, "-race", "-buildvcs=false", "-overlay="+overlayPath, "-c", "-o", binary, ".")
	t.Log("compile", cmd.Args)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("compile boundary: %v\n%s", err, out)
	}
	data, err := os.ReadFile(binary)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("compiled boundary SHA256=%x", sha256.Sum256(data))
	cmd = exec.CommandContext(ctx, binary, "-test.run=^TestRunExitObservedReaderAndPendingOwner$", "-test.v", "-test.count=1", "-test.timeout=30s")
	out, err := cmd.CombinedOutput()
	t.Logf("%s", out)
	if err != nil {
		t.Fatalf("actual reader/owner/Run-exit regression: %v", err)
	}
}

// Isolate only the actual pending branch, its registered fatal defers, and the
// actual summary fallthrough, so this regression needs no kernel/target effects.
func projectRunExit(t *testing.T, cwd, temp string, replacements map[string]string) {
	t.Helper()
	data, err := os.ReadFile("collector.go")
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("Run source SHA256=%x", sha256.Sum256(data))
	set := token.NewFileSet()
	file, err := parser.ParseFile(set, "collector.go", data, 0)
	if err != nil {
		t.Fatal(err)
	}
	var run *ast.FuncDecl
	for _, d := range file.Decls {
		if f, ok := d.(*ast.FuncDecl); ok && f.Name.Name == "Run" {
			run = f
		}
	}
	if run == nil {
		t.Fatal("Run boundary missing")
	}
	var guard []ast.Stmt
	var pending *ast.IfStmt
	for _, stmt := range run.Body.List {
		if d, ok := stmt.(*ast.DeferStmt); ok {
			usesFatal := false
			ast.Inspect(d, func(n ast.Node) bool {
				if id, ok := n.(*ast.Ident); ok && id.Name == "fatal" {
					usesFatal = true
				}
				return true
			})
			if usesFatal {
				guard = append(guard, d)
			}
		}
		ast.Inspect(stmt, func(n ast.Node) bool {
			if i, ok := n.(*ast.IfStmt); ok {
				ast.Inspect(i.Cond, func(n ast.Node) bool {
					if s, ok := n.(*ast.SelectorExpr); ok && s.Sel.Name == "ReceiverJoined" {
						pending = i
					}
					return true
				})
			}
			return true
		})
		if pending != nil {
			break
		}
	}
	if pending == nil {
		t.Fatal("pending readout boundary missing")
	}
	var out bytes.Buffer
	out.WriteString(`package capture
import("errors";"fmt";"io.mazewall/experiments/hotspot-agentless/internal/model")
func projectedRunExit(fatal *fatalState, runErr, eventFinalizationErr error, eventFinalization *model.EventFinalizationObservation, sink model.Sink) (resultErr error) {
cfg := Config{DrainFull:true}
retainFullResources := false
_ = retainFullResources
sessionID := "pending-fixture"
summary := model.Summary{}
var err error
`)
	for _, stmt := range append(append(guard, pending), run.Body.List[len(run.Body.List)-2:]...) {
		if err := format.Node(&out, set, stmt); err != nil {
			t.Fatal(err)
		}
		out.WriteByte('\n')
	}
	out.WriteString("}\n")
	path := filepath.Join(temp, "run_exit_projection_test.go")
	if err := os.WriteFile(path, out.Bytes(), 0600); err != nil {
		t.Fatal(err)
	}
	replacements[filepath.Join(cwd, "run_exit_projection_test.go")] = path
}
