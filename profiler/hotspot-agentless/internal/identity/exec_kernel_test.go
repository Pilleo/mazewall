//go:build mw_kernel && linux

package identity

import (
	"bufio"
	"errors"
	"os/exec"
	"testing"
)

func TestExecBarrierPoisonsSamePIDBeforeNewImageRuns(t *testing.T) {
	cmd := exec.Command("/bin/sh", "-c", "printf 'ready\\n'; read token; exec /bin/true")
	in, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err = cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer cmd.Process.Kill()
	s := bufio.NewScanner(out)
	if !s.Scan() || s.Text() != "ready" {
		t.Fatal("child did not reach gate", s.Err())
	}
	b, err := NewExecBarrier(cmd.Process.Pid)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if err = b.Check(); err != nil {
		t.Fatal("initially poisoned", err)
	}
	if _, err = in.Write([]byte("exec\n")); err != nil {
		t.Fatal(err)
	}
	if err = cmd.Wait(); err != nil {
		t.Fatal(err)
	}
	if err = b.Check(); !errors.Is(err, ErrTargetExec) {
		t.Fatalf("same-PID exec was not rejected: %v", err)
	}
}
