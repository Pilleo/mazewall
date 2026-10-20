//go:build mw_hotspot_qualification

package candidate

import (
	"context"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"io.mazewall/experiments/hotspot-agentless/internal/driver"
)

func TestObservedCollectorWaitsForProcessNotCaptureReturn(t *testing.T) {
	for _, code := range []string{"0", "7"} {
		t.Run(code, func(t *testing.T) {
			dir := t.TempDir()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestObservedCollectorLifetimeHelper$")
			cmd.Env = append(os.Environ(), "MW_OBSERVED_PROCESS_HELPER="+dir, "MW_OBSERVED_PROCESS_EXIT="+code)
			read, write := io.Pipe()
			defer read.Close()
			done := make(chan observedProcessExit, 1)
			go func() { done <- runObservedCollectorProcess(cmd, write) }()
			if err := driver.WaitUntil(time.Second, "collector capture returned", func() (bool, error) {
				return driver.Exists(filepath.Join(dir, "capture-returned"))
			}); err != nil {
				t.Fatal(err)
			}
			select {
			case exit := <-done:
				t.Fatal("target could be released while collector still owns resources", exit)
			default:
			}
			if err := os.WriteFile(filepath.Join(dir, "retire"), nil, 0600); err != nil {
				t.Fatal(err)
			}
			select {
			case exit := <-done:
				if !exit.Reaped || exit.PID != cmd.Process.Pid || cmd.ProcessState == nil {
					t.Fatal("collector process was not actually joined", exit)
				}
				if code == "7" {
					var failure *exec.ExitError
					if !errors.As(exit.cause, &failure) || exit.ExitCode != 7 {
						t.Fatal("original failed collector exit was lost", exit)
					}
				} else if exit.cause != nil || exit.ExitCode != 0 {
					t.Fatal("successful collector exit failed", exit)
				}
			case <-ctx.Done():
				t.Fatal("collector process was not reaped", ctx.Err())
			}
		})
	}
}

func TestObservedCollectorCancellationReapsBeforeTargetRelease(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	dir := t.TempDir()
	cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestObservedCollectorLifetimeHelper$")
	cmd.Env = append(os.Environ(), "MW_OBSERVED_PROCESS_HELPER="+dir)
	read, write := io.Pipe()
	defer read.Close()
	done := make(chan observedProcessExit, 1)
	go func() { done <- runObservedCollectorProcess(cmd, write) }()
	if err := driver.WaitUntil(time.Second, "collector running", func() (bool, error) {
		return driver.Exists(filepath.Join(dir, "capture-returned"))
	}); err != nil {
		t.Fatal(err)
	}
	cancel()
	select {
	case exit := <-done:
		if !exit.Reaped || exit.cause == nil || cmd.ProcessState == nil {
			t.Fatal("cancelled collector was not reaped before target release", exit)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("cancelled collector did not exit")
	}
}

func TestObservedCollectorLifetimeHelper(t *testing.T) {
	dir := os.Getenv("MW_OBSERVED_PROCESS_HELPER")
	if dir == "" {
		return
	}
	if err := os.WriteFile(filepath.Join(dir, "capture-returned"), nil, 0600); err != nil {
		os.Exit(2)
	}
	if err := driver.WaitUntil(4*time.Second, "owned collector resources", func() (bool, error) {
		return driver.Exists(filepath.Join(dir, "retire"))
	}); err != nil {
		os.Exit(3)
	}
	if os.Getenv("MW_OBSERVED_PROCESS_EXIT") == "7" {
		os.Exit(7)
	}
	os.Exit(0)
}
