package capture

import (
	"context"
	"errors"
	"fmt"
	"golang.org/x/sys/unix"
)

// The caller owns the real pidfd; the watcher only borrows it until return.
func watchTarget(ctx context.Context, fd int, cancel context.CancelFunc) error {
	poll := []unix.PollFd{{Fd: int32(fd), Events: unix.POLLIN}}
	for ctx.Err() == nil {
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
			return nil
		}
	}
	return nil
}
