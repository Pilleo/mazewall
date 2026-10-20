//go:build mw_hotspot_qualification

package candidate

import (
	"fmt"
	"os/exec"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
)

type observedCollectorFailure struct {
	Error                  string
	TargetExec, TargetExit bool
}

func verifyObservedControl(name string, ready bool, exit observedProcessExit, failure observedCollectorFailure, summary *wire.Summary, decodeErr error) error {
	if !ready || exit.PID <= 0 || !exit.Reaped || exit.ExitCode == 0 || exit.cause == nil || failure.Error == "" || summary != nil || decodeErr == nil {
		return fmt.Errorf("control lacks a verified failed capture and retired collector")
	}
	switch name {
	case "target-exec":
		if failure.TargetExec {
			return nil
		}
	case "target-exit":
		if failure.TargetExit {
			return nil
		}
	}
	return fmt.Errorf("control lacks the original typed target-change cause")
}

// Only the deliberate, independently checked SIGKILL control may accept exit137.
func onlyExpectedTargetKill(err error) bool {
	if err == nil {
		return false
	}
	if pending, ok := err.(interface{ HasPendingOwners() bool }); ok && pending.HasPendingOwners() {
		return false
	}
	if exit, ok := err.(*exec.ExitError); ok {
		return exit.ExitCode() == 137
	}
	switch cause := err.(type) {
	case interface{ Unwrap() []error }:
		children := cause.Unwrap()
		if len(children) == 0 {
			return false
		}
		for _, child := range children {
			if !onlyExpectedTargetKill(child) {
				return false
			}
		}
		return true
	case interface{ Unwrap() error }:
		return onlyExpectedTargetKill(cause.Unwrap())
	default:
		return false
	}
}
