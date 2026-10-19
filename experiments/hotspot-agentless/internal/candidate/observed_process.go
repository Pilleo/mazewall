//go:build mw_hotspot_qualification

package candidate

import (
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
)

type observedCollectorInput struct {
	Target       wire.Target
	Image, Store string
}

type observedProcessExit struct {
	PID      int
	Reaped   bool
	ExitCode int
	Error    string
	cause    error
}

// A failed capture can retain native owners; only process exit permits target release.
func runObservedCollectorProcess(command *exec.Cmd, output *io.PipeWriter) (exit observedProcessExit) {
	defer output.Close()
	exit.ExitCode = -1
	err := command.Start()
	if err == nil {
		exit.PID = command.Process.Pid
		err = command.Wait()
		if command.ProcessState != nil {
			exit.Reaped = true
			exit.ExitCode = command.ProcessState.ExitCode()
		} else {
			err = errors.Join(err, fmt.Errorf("collector process exit unconfirmed"))
		}
	}
	exit.cause = err
	if err != nil {
		exit.Error = err.Error()
	}
	return exit
}

type observedPendingOwners struct {
	collector *exec.Cmd
	targets   *driver.ContainerOwners
	logs      []*os.File
	cause     error
}

func (e *observedPendingOwners) Error() string {
	return fmt.Sprintf("collector retirement unconfirmed; target retained: %v", e.cause)
}
func (e *observedPendingOwners) Unwrap() error { return e.cause }
