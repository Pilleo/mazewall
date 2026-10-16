//go:build !linux

package preflight

import "runtime"

func Observe(pid int) Snapshot {
	return Snapshot{OS: runtime.GOOS, Arch: runtime.GOARCH, PID: pid}
}
