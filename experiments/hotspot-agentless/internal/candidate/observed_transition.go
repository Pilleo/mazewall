//go:build mw_hotspot_qualification

package candidate

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
)

func validateObservedExecTransition(before, after wire.Target, expected, executable string) error {
	if before.PID == 0 || before.StartTicks == 0 || before.CgroupID == 0 || before.MountNS == 0 || before.ContainerID == "" || before.ImageID == "" || before != after {
		return fmt.Errorf("exec changed the independently bound target generation")
	}
	if !filepath.IsAbs(expected) || filepath.Clean(expected) != expected || executable != expected {
		return fmt.Errorf("expected actual sleeping replacement, got %q", executable)
	}
	return nil
}

func observeJVMExec(docker candidateDocker, id, dir, gates string, before wire.Target) error {
	link := fmt.Sprintf("/proc/%d/exe", before.PID)
	previous, err := os.Readlink(link)
	if err != nil {
		return err
	}
	if filepath.Base(previous) != "java" {
		return fmt.Errorf("exec control did not start from actual JVM: %s", previous)
	}
	resolved, err := docker.Output(5*time.Second, "exec", id, "readlink", "-f", "/bin/sleep")
	if err != nil {
		return err
	}
	expected := strings.TrimSpace(resolved)
	if !filepath.IsAbs(expected) || filepath.Clean(expected) != expected || strings.ContainsAny(expected, "\x00\n\r") || len(expected) > 4096 {
		return fmt.Errorf("invalid independent replacement executable binding")
	}
	if err := driver.WriteJSON(filepath.Join(dir, "exec-before.json"), map[string]any{"target": before, "previous_executable": previous, "expected_replacement_executable": expected, "resolver": []string{"exec", id, "readlink", "-f", "/bin/sleep"}}); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(gates, "exec"), []byte("exec\n"), 0644); err != nil {
		return err
	}
	var after wire.Target
	var executable string
	err = driver.WaitUntil(3*time.Second, "same-PID JVM exec", func() (bool, error) {
		if data, readErr := os.ReadFile(filepath.Join(gates, "exec-error")); readErr == nil {
			return false, fmt.Errorf("JVM exec action failed: %s", data)
		} else if !os.IsNotExist(readErr) {
			return false, readErr
		}
		var err error
		executable, err = os.Readlink(link)
		if err != nil {
			return false, err
		}
		if filepath.Base(executable) == "java" {
			return false, nil
		}
		after, err = observedTarget(int(before.PID), before.ContainerID, before.ImageID)
		if err != nil {
			return false, err
		}
		if err := validateObservedExecTransition(before, after, expected, executable); err != nil {
			return false, err
		}
		return true, nil
	})
	if err != nil {
		return err
	}
	if err := observedProcReceipt(dir, "exec-after", int(before.PID)); err != nil {
		return err
	}
	return driver.WriteJSON(filepath.Join(dir, "exec-transition.json"), map[string]any{"before": before, "after": after, "previous_executable": previous, "expected_replacement_executable": expected, "replacement_executable": executable})
}

func observeTargetKill(docker candidateDocker, dir, id string) error {
	if _, err := docker.Output(5*time.Second, "kill", "--signal=KILL", id); err != nil {
		return err
	}
	return driver.WaitUntil(3*time.Second, "intentional target exit137", func() (bool, error) {
		text, err := docker.Output(5*time.Second, "inspect", "--format", "{{json .State}}", id)
		if err != nil {
			return false, err
		}
		var state struct {
			Status   string
			Running  *bool
			Pid      *int
			ExitCode *int
			Error    *string
		}
		if err := json.Unmarshal([]byte(text), &state); err != nil {
			return false, err
		}
		if state.Running == nil || state.Pid == nil || state.ExitCode == nil || state.Error == nil {
			return false, fmt.Errorf("incomplete target exit receipt")
		}
		if *state.Running {
			return false, nil
		}
		if state.Status != "exited" || *state.Pid != 0 || *state.ExitCode != 137 || *state.Error != "" {
			return false, fmt.Errorf("unexpected target exit receipt: %s", text)
		}
		return true, os.WriteFile(filepath.Join(dir, "target-exit-state.json"), []byte(text), 0644)
	})
}
