package capture

import (
	"context"
	"errors"
	"fmt"

	"golang.org/x/sys/unix"
)

// The caller owns stdin's real descriptor. Polling is finite and never closes it.
// stop keeps definition/enrichment lifetime live; EOF/invalid input aborts that lifetime.
func watchControl(ctx context.Context, fd int, request context.CancelFunc, abort context.CancelCauseFunc) error {
	poll := []unix.PollFd{{Fd: int32(fd), Events: unix.POLLIN}}
	var command []byte
	requested := false
	for ctx.Err() == nil {
		_, err := unix.Poll(poll, 50)
		if errors.Is(err, unix.EINTR) {
			continue
		}
		if err != nil {
			abort(err)
			return err
		}
		if poll[0].Revents == 0 {
			continue
		}
		if poll[0].Revents&(unix.POLLNVAL|unix.POLLERR) != 0 {
			err := fmt.Errorf("parent control descriptor failed")
			abort(err)
			return err
		}
		var data [6]byte
		n, err := unix.Read(fd, data[:])
		if errors.Is(err, unix.EINTR) {
			continue
		}
		if err != nil {
			abort(err)
			return err
		}
		if n == 0 {
			err := fmt.Errorf("parent control EOF before collector retirement")
			abort(err)
			return err
		}
		command = append(command, data[:n]...)
		if requested || len(command) > len("stop\n") || string(command) != "stop\n"[:len(command)] {
			err := fmt.Errorf("invalid parent control command")
			abort(err)
			return err
		}
		if string(command) == "stop\n" {
			requested = true
			command = nil
			request()
		}
	}
	return nil
}
