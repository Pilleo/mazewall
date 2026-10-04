package capture

import (
	"context"
	"errors"
	"golang.org/x/sys/unix"
	"os"
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
