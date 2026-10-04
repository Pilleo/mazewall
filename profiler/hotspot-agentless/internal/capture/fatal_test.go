package capture

import (
	"context"
	"errors"
	"syscall"
	"testing"
)

func TestProviderFatalCancelsWithoutAnEventLookup(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	f := fatalState{cancel: cancel}
	f.Record(syscall.EIO)
	f.Record(syscall.EACCES)
	if ctx.Err() == nil || !errors.Is(f.Err(), syscall.EACCES) || !errors.Is(f.Err(), syscall.EIO) {
		t.Fatal("unlooked-up fatal causes lost", f.Err())
	}
}
