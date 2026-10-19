//go:build linux

package identity

import (
	"fmt"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"golang.org/x/sys/unix"
	"io.mazewall/experiments/hotspot-agentless/internal/preflight"
	"os"
	"path/filepath"
	"strings"
)

// ObserveProfile is read-only; the caller owns an exec barrier through discovery.
func ObserveProfile(pid uint32, expected wire.Target) (ObservedTarget, error) {
	if err := CheckObservedTarget(pid, expected); err != nil {
		return ObservedTarget{}, err
	}
	t, err := discoverProfile(int(pid), expected.StartTicks, true)
	if err != nil {
		return ObservedTarget{}, err
	}
	if err := CheckObservedTarget(pid, expected); err != nil {
		return ObservedTarget{}, err
	}
	return ObservedTarget{Target: expected, JVMBuildID: t.JVMBuildID, JVMSHA256: t.JVMSHA256}, nil
}

// CheckObservedTarget is shared by pre-attach, ready, and the session watcher.
func CheckObservedTarget(pid uint32, expected wire.Target) error {
	if err := validateObservedTarget(pid, expected, expected); err != nil {
		return err
	}
	stat, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return err
	}
	ticks, err := preflight.ParseStartTicks(string(stat))
	if err != nil {
		return err
	}
	raw, err := os.ReadFile(fmt.Sprintf("/proc/%d/cgroup", pid))
	if err != nil {
		return err
	}
	var path string
	for _, line := range strings.Split(string(raw), "\n") {
		if p, ok := strings.CutPrefix(line, "0::"); ok {
			if path != "" || !filepath.IsAbs(p) || filepath.Clean(p) != p {
				return fmt.Errorf("ambiguous/invalid cgroup v2 path")
			}
			path = p
		}
	}
	if path == "" {
		return fmt.Errorf("target has no unified cgroup")
	}
	var cg, ns unix.Stat_t
	if err := unix.Stat(filepath.Join("/sys/fs/cgroup", path), &cg); err != nil {
		return err
	}
	if err := unix.Stat(fmt.Sprintf("/proc/%d/ns/mnt", pid), &ns); err != nil {
		return err
	}
	return validateObservedTarget(pid, expected, wire.Target{PID: pid, StartTicks: ticks, CgroupID: cg.Ino, MountNS: ns.Ino})
}
