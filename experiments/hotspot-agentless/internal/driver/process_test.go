package driver

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestOwnedChildFixture(t *testing.T) {
	if os.Args[len(os.Args)-1] != "owned-wait" {
		return
	}
	fmt.Println("started")
	time.Sleep(time.Hour)
}

func TestOwnedChildTimeoutKillsAndReapsOnlyStartedProcess(t *testing.T) {
	root := t.TempDir()
	log, err := os.Create(filepath.Join(root, "child.log"))
	if err != nil {
		t.Fatal(err)
	}
	defer log.Close()
	docker := Docker{Root: root, Prefix: []string{os.Args[0], "-test.run=^TestOwnedChildFixture$", "--"}}
	child, err := docker.Start(log, log, "owned-wait")
	if err != nil {
		t.Fatal(err)
	}
	if err := WaitUntil(5*time.Second, "fixture startup", func() (bool, error) { data, err := os.ReadFile(log.Name()); return string(data) == "started\n", err }); err != nil {
		child.Process.Kill()
		child.Wait()
		t.Fatal(err)
	}
	if err := WaitChild(child, 20*time.Millisecond); err == nil {
		t.Fatal("timeout was swallowed")
	}
	if child.ProcessState == nil || child.ProcessState.Success() {
		t.Fatal("owned child was not killed and reaped")
	}
	if _, err := os.Stat(log.Name()); err != nil {
		t.Fatal("owned logs were removed")
	}
}

func TestCommandStdoutRemainsSeparateFromDiagnostics(t *testing.T) {
	text, err := Output(t.TempDir(), time.Second, "sh", "-c", "printf id; printf diagnostic >&2")
	if err != nil || text != "id" {
		t.Fatalf("command identity corrupted by diagnostics: %q %v", text, err)
	}
	if _, err := Output(t.TempDir(), time.Second, "sh", "-c", "printf denied >&2; exit 13"); err == nil {
		t.Fatal("native command failure swallowed")
	}
}
