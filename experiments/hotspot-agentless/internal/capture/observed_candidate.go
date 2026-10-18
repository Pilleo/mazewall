//go:build mw_hotspot_qualification

package capture

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"io"
	"os"
	"syscall"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
)

// RunObservedCandidate is internal qualification support, absent from default builds.
func RunObservedCandidate(ctx context.Context, cfg Config, writer io.Writer, control *os.File) error {
	cfg.profile = wire.Observed25
	if err := cfg.Validate(); err != nil {
		return err
	}
	return runControlled(ctx, cfg, writer, control, runObservedCandidate)
}

func runObservedCandidate(ctx context.Context, cfg Config, writer io.Writer) error {
	var random [16]byte
	if _, err := rand.Read(random[:]); err != nil {
		return err
	}
	cfg.sessionID = fmt.Sprintf("%x", random)
	sink := newObservedSink(writer)
	r, err := observedLifecycleRecord("session_started", cfg.sessionID, cfg.ExpectedTarget, "", "")
	if err != nil {
		return err
	}
	if err := sink.Write(ctx, r); err != nil {
		return err
	}
	err = Run(ctx, cfg, writer)
	if err == nil || errors.Is(err, errReadoutPending) {
		return err
	}
	r, clockErr := observedLifecycleRecord("collector_error", cfg.sessionID, cfg.ExpectedTarget, "", "")
	if clockErr != nil {
		return errors.Join(err, clockErr)
	}
	r.Lifecycle.Message = err.Error()
	var errno syscall.Errno
	if errors.As(err, &errno) {
		r.Lifecycle.Errno = int(errno)
	}
	return errors.Join(err, sink.Write(context.Background(), r))
}
