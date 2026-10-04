package capture

import (
	"context"
	"errors"
	"fmt"
	"golang.org/x/sys/unix"
)

var errTargetExited = errors.New("target exited during owned capture")

// The historical schema-1 unknown-identity experiment observes a process-lifetime
// window. Strict identity and owned drains require the target alive until retirement.
func targetStopError(cfg Config, err error) error {
	if !cfg.production && !cfg.StrictEpoch && !cfg.DrainEvents && !cfg.DrainFull && errors.Is(err, errTargetExited) {
		return nil
	}
	return err
}

// The caller owns the real pidfd; the watcher only borrows it until return.
func watchTarget(ctx context.Context, fd int, cancel context.CancelFunc, checks ...func() error) error {
	poll := []unix.PollFd{{Fd: int32(fd), Events: unix.POLLIN}}
	for ctx.Err() == nil {
		for _, check := range checks {
			if err := check(); err != nil {
				cancel()
				return err
			}
		}
		_, err := unix.Poll(poll, 50)
		if errors.Is(err, unix.EINTR) {
			continue
		}
		if err != nil {
			cancel()
			return fmt.Errorf("watch target pidfd: %w", err)
		}
		if poll[0].Revents&unix.POLLNVAL != 0 {
			cancel()
			return fmt.Errorf("target pidfd invalid: %w", unix.EBADF)
		}
		if poll[0].Revents&unix.POLLERR != 0 {
			cancel()
			return fmt.Errorf("target pidfd polling failed: %w", unix.EIO)
		}
		if poll[0].Revents&(unix.POLLIN|unix.POLLHUP) != 0 {
			cancel()
			return errTargetExited
		}
	}
	return nil
}
