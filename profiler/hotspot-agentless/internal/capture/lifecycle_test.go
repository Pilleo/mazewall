package capture

import (
	"context"
	"errors"
	"golang.org/x/sys/unix"
	"os"
	"os/exec"
	"testing"
)

func TestInvalidOwnedPidfdIsNotSilentlyClassifiedAsTargetExit(t *testing.T) {
	fd, err := unix.PidfdOpen(os.Getpid(), 0)
	if err != nil {
		t.Fatal(err)
	}
	if err := unix.Close(fd); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	err = watchTarget(ctx, fd, cancel)
	if !errors.Is(err, unix.EBADF) {
		t.Fatalf("invalid lifecycle descriptor downgraded to normal exit: %v", err)
	}
}

func TestRealTargetExitIsAnAbortFailure(t *testing.T) {
	child := exec.Command("/bin/sleep", "0.1")
	if err := child.Start(); err != nil {
		t.Fatal(err)
	}
	defer child.Wait()
	fd, err := unix.PidfdOpen(child.Process.Pid, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fd)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := watchTarget(ctx, fd, cancel); err == nil {
		t.Fatal("target exit silently converted into successful stop")
	}
}

func TestOnlyHistoricalUnownedScopeMayEndAtNaturalTargetExit(t *testing.T) {
	if targetStopError(Config{}, errTargetExited) != nil {
		t.Fatal("historical process-lifetime scope changed")
	}
	for _, cfg := range []Config{{StrictEpoch: true}, {DrainFull: true}, {DrainEvents: true}, {production: true}} {
		if !errors.Is(targetStopError(cfg, errTargetExited), errTargetExited) {
			t.Fatal("strict target exit downgraded")
		}
	}
	if !errors.Is(targetStopError(Config{}, unix.EPERM), unix.EPERM) {
		t.Fatal("legacy stop swallowed permission failure")
	}
}
