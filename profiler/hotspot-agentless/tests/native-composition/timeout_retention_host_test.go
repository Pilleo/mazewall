//go:build retentionhost

package retentionhost

import (
	"bytes"
	"crypto/sha256"
	"fmt"
	"go/ast"
	"go/format"
	"go/parser"
	"go/token"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestTimeoutRetention(t *testing.T) {
	root := os.Getenv("MAZEWALL_ROOT")
	if root == "" {
		root = "/home/leanid/Documents/code/java/jseccomp"
	}
	read := func(tree, name string) []byte {
		b, err := os.ReadFile(filepath.Join(root, tree, "tests/native-composition", name))
		if err != nil {
			t.Fatal(err)
		}
		return b
	}
	canonical, mirror := "profiler/hotspot-agentless", "experiments/hotspot-agentless"
	for _, n := range []string{"capture_helpers_test.go.txt", "capture_controls_test.go.txt", "capture_protocol_controls_test.go.txt"} {
		a, b := read(canonical, n), read(mirror, n)
		if !bytes.Equal(a, b) {
			t.Fatalf("asset mismatch: %s", n)
		}
		if n == "capture_protocol_controls_test.go.txt" && fmt.Sprintf("%x", sha256.Sum256(a)) != "a071488b43ce0b1496379a4d484b3d056e8140252c5c8f6ec02ad29f15beb10f" {
			t.Fatal("protocol asset changed")
		}
	}
	helpers := read(canonical, "capture_helpers_test.go.txt")
	controls := string(read(canonical, "capture_controls_test.go.txt"))
	controlsAST, err := parser.ParseFile(token.NewFileSet(), "controls.go", controls, 0)
	if err != nil {
		t.Fatal(err)
	}
	var callback *ast.FuncLit
	ast.Inspect(controlsAST, func(n ast.Node) bool {
		call, ok := n.(*ast.CallExpr)
		if ok && len(call.Args) == 4 {
			if id, ok := call.Fun.(*ast.Ident); ok && id.Name == "nativeAwaitReporting" {
				callback, _ = call.Args[3].(*ast.FuncLit)
			}
		}
		return true
	})
	if callback == nil {
		t.Fatal("reporter callback missing")
	}
	var callbackSource bytes.Buffer
	if err := format.Node(&callbackSource, token.NewFileSet(), callback); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(controls, "nativeAwaitReporting(t, f.ctx, \"actual reporter blocked on existing consumer mutex\"") || !bytes.Contains(helpers, []byte("raw_truncated=")) || !bytes.Contains(helpers, []byte("matched=")) {
		t.Fatal("retention absent from reporter timeout fatal")
	}
	fset := token.NewFileSet()
	f, err := parser.ParseFile(fset, "helpers.go", helpers, 0)
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]bool{"nativeAwaitTimeoutHeaderMax": true, "nativeJoinedDiagnosticTextLimit": true, "nativeAwaitObservation": true, "nativeBlockedStackMatch": true, "formatNativeAwaitTimeout": true, "nativeAwaitReporting": true, "nativeHeldRunCompletion": true}
	var extracted bytes.Buffer
	functions := make(map[string]string)
	for _, d := range f.Decls {
		name := ""
		switch v := d.(type) {
		case *ast.FuncDecl:
			name = v.Name.Name
			var buf bytes.Buffer
			if err := format.Node(&buf, fset, v); err != nil {
				t.Fatal(err)
			}
			functions[name] = buf.String()
		case *ast.GenDecl:
			if len(v.Specs) == 1 {
				switch s := v.Specs[0].(type) {
				case *ast.TypeSpec:
					name = s.Name.Name
				case *ast.ValueSpec:
					if len(s.Names) == 1 {
						name = s.Names[0].Name
					}
				}
			}
		}
		if want[name] {
			if err := format.Node(&extracted, fset, d); err != nil {
				t.Fatal(err)
			}
			extracted.WriteString("\n\n")
			delete(want, name)
		}
	}
	if len(want) != 0 {
		t.Fatalf("missing extracted declarations: %v", want)
	}
	if strings.Count(controls, "nativeAwait(t,") != 4 || strings.Count(controls, "nativeAwaitReporting(t,") != 1 || bytes.Count(helpers, []byte("runtime.Stack(")) != 3 {
		t.Fatal("unexpected await or snapshot wiring")
	}
	await, reporting, observe := functions["nativeAwait"], functions["nativeAwaitReporting"], functions["nativeObserveBlockedStack"]
	if strings.Contains(await, "formatNativeAwaitTimeout") || !strings.Contains(await, `t.Fatalf("%s: %v", description, err)`) || strings.Count(reporting, "formatNativeAwaitTimeout(") != 1 || !strings.Contains(reporting, `t.Fatalf("%s: %v\n%s", description, err, formatNativeAwaitTimeout(description, last))`) || !strings.Contains(reporting, "if matched {\n\t\t\treturn\n\t\t}") || strings.Count(observe, "runtime.Stack(") != 1 || !strings.Contains(observe, "1<<20") || strings.Contains(functions["nativeBlockedStack"], "runtime.Stack(") {
		t.Fatal("unexpected timeout reporting wiring")
	}
	dir := t.TempDir()
	write := func(name, data string) {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(data), 0600); err != nil {
			t.Fatal(err)
		}
	}
	write("go.mod", "module retentionchild\n\ngo 1.22\n")
	write("retention_test.go", "package retentionchild\nimport (\"context\"; \"errors\"; \"fmt\"; \"runtime\"; \"strings\"; \"testing\"; \"time\")\n"+extracted.String()+childCases)
	write("callback_test.go", "package retentionchild\nimport (\"context\"; \"errors\"; \"fmt\"; \"strings\"; \"testing\")\n"+callbackFixture+"\nfunc runCallback(t *fakeT, f *fixture, failureDiagnostic bool) (bool, nativeAwaitObservation) { return ("+callbackSource.String()+")() }\n"+callbackCases)
	cmd := exec.Command("go", "test", "-count=1", "-timeout=20s", "-vet=off", ".")
	cmd.Dir = dir
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("extracted host test: %v\n%s", err, out)
	}
	t.Logf("extracted host test: %s", out)
}

const callbackFixture = `
var steps []string
var matchedStack bool
type readerCounts struct { HandedOff, Received uint64 }
type snapshot struct { Reader readerCounts; SourceError error; ReaderJoined bool }
type fakeTracer struct { value snapshot; err error; done <-chan struct{} }
func (x *fakeTracer) Snapshot() (snapshot,error) { select { case <-x.done: steps=append(steps,"done closed"); default: panic("snapshot before done") }; steps=append(steps,"snapshot"); return x.value,x.err }
type fakeResolver struct { Tracer *fakeTracer }
type fixture struct { ctx context.Context; done chan struct{}; runError error; resolver fakeResolver }
type fakeT struct { log, fatal string }
func (t *fakeT) Logf(f string,a ...any) { steps=append(steps,"log"); t.log=fmt.Sprintf(f,a...) }
func (t *fakeT) Fatalf(f string,a ...any) { steps=append(steps,"fatal"); t.fatal=fmt.Sprintf(f,a...); panic("fatal") }
func nativeObserveBlockedStack(string) (bool,nativeAwaitObservation) { steps=append(steps,"observe"); return matchedStack,nativeAwaitObservation{} }
func nativeHeldEarlyRunFailure(context.Context,<-chan struct{},func() error) error { return nil }
func nativeJoinedFatal(*fakeT,string,error) { panic("unexpected early failure") }
`

const callbackCases = `
func TestCallbackReadout(t *testing.T) {
 for _, tc := range []struct{name string; snapshotErr error; value snapshot; want []string}{
  {"success",nil,snapshot{Reader:readerCounts{HandedOff:7,Received:9},SourceError:errors.New("source-error"),ReaderJoined:true},[]string{"snapshotErr=<nil>","handedOff=7","received=9","sourceError=source-error","readerJoined=true"}},
  {"snapshot error",errors.New("snapshot-error"),snapshot{},[]string{"snapshotErr=snapshot-error"}},
 } { t.Run(tc.name,func(t *testing.T) {
  steps=nil; matchedStack=false; done:=make(chan struct{}); close(done)
  f:=&fixture{done:done,runError:errors.New("run-error"),resolver:fakeResolver{Tracer:&fakeTracer{value:tc.value,err:tc.snapshotErr,done:done}}}; ft:=&fakeT{}
  func(){ defer func(){ if recover()!="fatal" { t.Fatal("missing fatal") } }(); runCallback(ft,f,false) }()
  if fmt.Sprint(steps)!="[observe done closed snapshot log fatal]" {t.Fatal(steps)}
  for _, w:=range tc.want {if !strings.Contains(ft.log,w) {t.Fatalf("log %q lacks %q",ft.log,w)}}
  if !strings.Contains(ft.fatal,"run-error") || !strings.Contains(ft.fatal,"raw_truncated=false") {t.Fatal(ft.fatal)}
 }) }
}
func TestCallbackOpenAndMatched(t *testing.T) {
 for _,matched:=range []bool{false,true} { steps=nil; matchedStack=matched; done:=make(chan struct{}); if matched {close(done)}; f:=&fixture{done:done,resolver:fakeResolver{Tracer:&fakeTracer{done:done}}}; ft:=&fakeT{}; got,_:=runCallback(ft,f,false); if got!=matched || fmt.Sprint(steps)!="[observe]" {t.Fatal(got,steps)} }
}
`

const childCases = `
func TestRunCompletion(t *testing.T) {
 done:=make(chan struct{}); calls:=0; sentinel:=errors.New("published")
 callback:=func() error { calls++; return sentinel }
 if closed,err:=nativeHeldRunCompletion(done,callback); closed || err!=nil || calls!=0 {t.Fatal(closed,err,calls)}
 close(done)
 closed,err:=nativeHeldRunCompletion(done,callback)
 if !closed || !errors.Is(err,sentinel) || calls!=1 || !strings.Contains(err.Error(),"run_error_nil=false") {t.Fatal(closed,err,calls)}
 select {case <-done: default: t.Fatal("closed channel was consumed")}
 closed,err=nativeHeldRunCompletion(done,func() error {calls++; return nil})
 if !closed || err==nil || !strings.Contains(err.Error(),"run_error_nil=true") || calls!=2 {t.Fatal(closed,err,calls)}
}
func TestRunDoneHeader(t *testing.T) {
 for _,c:=range []struct{known,closed bool; want string}{{false,false,""},{true,false,"run_done=open"},{true,true,"run_done=closed"}} {
  out:=formatNativeAwaitTimeout("actual reporter blocked on existing consumer mutex",nativeAwaitObservation{runDoneKnown:c.known,runDoneClosed:c.closed})
  header:=strings.SplitN(out,"\n",2)[0]
  if len(header)>256 || (c.want=="" && strings.Contains(header,"run_done=")) || (c.want!="" && !strings.Contains(header,c.want)) {t.Fatal(header)}
 }
}
func TestMatchAndFormat(t *testing.T) {
 name := "traceReporter.ReportTraceEvent"
 match := "goroutine 1 [sync.Mutex.Lock]:\nlockSlow\ntraceReporter.ReportTraceEvent"
 wrong := "goroutine 1:\nlockSlow\n\ngoroutine 2:\ntraceReporter.ReportTraceEvent"
 for _, c := range []struct{ raw string; truncated bool; names []string; want bool }{
  {match,false,[]string{name},true}, {"lockSlow\n(*traceReporter).ReportTraceEvent",false,[]string{name},false},
  {"lockSlow",false,[]string{name},false}, {wrong,false,[]string{name},false},
  {match,true,[]string{name},false}, {match,true,nil,false}, {".Lock(\n"+name,false,[]string{name},true}, {"lockSlow",false,nil,true},
 } { if got:=nativeBlockedStackMatch([]byte(c.raw),c.truncated,c.names...); got!=c.want { t.Fatalf("match %q got %t",c.raw,got) } }
 for _, c := range []struct{ raw string; truncated, matched bool; contains []string }{
  {match,false,true,[]string{"matched=true","raw_truncated=false",name,"emit_truncated=false"}},
  {wrong,false,false,[]string{"goroutine 1", "goroutine 2", "matched=false"}},
  {"lockSlow",false,false,[]string{"lockSlow","relevant_absent=false"}},
  {"nothing",false,false,[]string{"relevant_absent=true","relevant_goroutine_absent"}},
  {strings.Repeat("x",20000),true,false,[]string{"raw_truncated=true","incomplete_snapshot_prefix","emit_truncated=true"}},
  {"",false,false,[]string{"snapshot_absent"}},
 } { out:=formatNativeAwaitTimeout("actual reporter blocked on existing consumer mutex",nativeAwaitObservation{names:[]string{name},matched:c.matched,rawTruncated:c.truncated,raw:[]byte(c.raw)}); parts:=strings.SplitN(out,"\n",2); if len(parts)!=2 || len(parts[0])>256 || len(parts[1])>16384 || !strings.Contains(parts[0],"actual reporter blocked on existing consumer mutex") {t.Fatal("header or body bound")}; for _,s:=range c.contains {if !strings.Contains(out,s) {t.Fatalf("missing %q in %q",s,out)}} }
 out:=formatNativeAwaitTimeout(strings.Repeat("a",200),nativeAwaitObservation{}); if len(out)>256+16384 {t.Fatal("long stage")}
}
func TestSuccessNoOutput(t *testing.T) { ctx,cancel:=context.WithDeadline(context.Background(),time.Unix(0,1)); defer cancel(); calls:=0; nativeAwaitReporting(t,ctx,"stage",func()(bool,nativeAwaitObservation){ calls++; return true,nativeAwaitObservation{} }); if calls!=1 {t.Fatal(calls)} }
`

func retentionWiring(helpers, controls []byte) error {
	set := token.NewFileSet()
	h, err := parser.ParseFile(set, "helpers.go", helpers, 0)
	if err != nil {
		return err
	}
	c, err := parser.ParseFile(set, "controls.go", controls, 0)
	if err != nil {
		return err
	}
	normal := func(n ast.Node) string {
		var b bytes.Buffer
		if err := format.Node(&b, set, n); err != nil {
			return ""
		}
		return b.String()
	}
	var reporting *ast.FuncDecl
	var held *ast.FuncDecl
	for _, d := range h.Decls {
		if f, ok := d.(*ast.FuncDecl); ok && f.Name.Name == "nativeAwaitReporting" {
			reporting = f
		}
	}
	for _, d := range c.Decls {
		if f, ok := d.(*ast.FuncDecl); ok && f.Name.Name == "heldComposition" {
			held = f
		}
	}
	if reporting == nil || held == nil {
		return fmt.Errorf("reporting or heldComposition absent")
	}
	expected, err := parser.ParseFile(set, "expected.go", `package capture
func nativeAwaitReporting(t *testing.T, ctx context.Context, description string, observe func() (bool, nativeAwaitObservation)) {
	t.Helper()
	for {
		matched, last := observe()
		if matched { return }
		if err := ctx.Err(); err != nil { t.Fatalf("%s: %v\n%s", description, err, formatNativeAwaitTimeout(description, last)) }
		runtime.Gosched()
	}
}`, 0)
	if err != nil {
		return err
	}
	if normal(reporting) != normal(expected.Decls[0]) {
		return fmt.Errorf("timeout/success reporting structure changed")
	}
	want, err := parser.ParseExprFrom(set, "expected-call.go", `nativeAwaitReporting(t, f.ctx, "actual reporter blocked on existing consumer mutex", func() (bool, nativeAwaitObservation) {
		if failureDiagnostic {
			if err := nativeHeldEarlyRunFailure(f.ctx, f.done, func() error { return f.runError }); err != nil {
				nativeJoinedFatal(t, "held reporter", err)
			}
		}
		matched, observation := nativeObserveBlockedStack("traceReporter.ReportTraceEvent")
		if matched { return matched, observation }
		if closed, err := nativeHeldRunCompletion(f.done, func() error { return f.runError }); closed {
			snapshot, snapshotErr := f.resolver.Tracer.Snapshot()
			t.Logf("snapshotErr=%v handedOff=%d received=%d sourceError=%v readerJoined=%t", snapshotErr, snapshot.Reader.HandedOff, snapshot.Reader.Received, snapshot.SourceError, snapshot.ReaderJoined)
			t.Fatalf("actual Run completed before expected reporter observation raw_truncated=%t: %v", observation.rawTruncated, err)
		}
		observation.runDoneKnown = true
		observation.runDoneClosed = false
		return matched, observation
	})`, 0)
	if err != nil {
		return err
	}
	count := 0
	valid := false
	ast.Inspect(held.Body, func(n ast.Node) bool {
		call, ok := n.(*ast.CallExpr)
		if !ok {
			return true
		}
		id, ok := call.Fun.(*ast.Ident)
		if ok && id.Name == "nativeAwaitReporting" {
			count++
			valid = normal(call) == normal(want)
		}
		return true
	})
	if count != 1 || !valid {
		return fmt.Errorf("reporter await callback or stage changed")
	}
	return nil
}

func TestRetentionWiring(t *testing.T) {
	root := os.Getenv("MAZEWALL_ROOT")
	if root == "" {
		root = "/home/leanid/Documents/code/java/jseccomp"
	}
	dir := filepath.Join(root, "profiler/hotspot-agentless/tests/native-composition")
	helpers, err := os.ReadFile(filepath.Join(dir, "capture_helpers_test.go.txt"))
	if err != nil {
		t.Fatal(err)
	}
	controls, err := os.ReadFile(filepath.Join(dir, "capture_controls_test.go.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if err := retentionWiring(helpers, controls); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name, from, to string
		helper         bool
	}{
		{"old await", "nativeAwaitReporting(t, f.ctx, \"actual reporter", "nativeAwait(t, f.ctx, \"actual reporter", false},
		{"missing observation", `matched, observation := nativeObserveBlockedStack("traceReporter.ReportTraceEvent")`, `matched, observation := false, nativeAwaitObservation{}`, false},
		{"output on success", "if matched {\n\t\t\treturn", "if matched {\n\t\t\tfmt.Println(\"unexpected\")\n\t\t\treturn", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			input := controls
			if tc.helper {
				input = helpers
			}
			changed := bytes.Replace(input, []byte(tc.from), []byte(tc.to), 1)
			if bytes.Equal(input, changed) {
				t.Fatal("mutation did not apply")
			}
			h, c := helpers, controls
			if tc.helper {
				h = changed
			} else {
				c = changed
			}
			if err := retentionWiring(h, c); err == nil {
				t.Fatal("wiring mutation was accepted")
			} else {
				t.Log(err)
			}
		})
	}
}
