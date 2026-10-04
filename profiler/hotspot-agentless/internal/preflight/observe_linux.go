//go:build linux

package preflight

import (
	"errors"
	"fmt"
	"os"
	"runtime"
	"strconv"
	"strings"

	"github.com/cilium/ebpf"
	"github.com/cilium/ebpf/asm"
	"github.com/cilium/ebpf/btf"
	"golang.org/x/sys/unix"
)

// Observe performs only read-only host inspection and a temporary BPF load.
// It does not change sysctls, ptrace authorization, or attach a tracing probe.
func Observe(pid int) Snapshot {
	s := Snapshot{OS: runtime.GOOS, Arch: runtime.GOARCH, PID: pid, Probe: "__x64_sys_openat", Details: map[string]string{}}
	var uname unix.Utsname
	if err := unix.Uname(&uname); err == nil {
		s.Kernel = unix.ByteSliceToString(uname.Release[:])
	} else {
		s.Details["uname"] = err.Error()
	}
	spec, err := btf.LoadKernelSpec()
	if err != nil {
		s.Details["btf"] = err.Error()
	} else {
		s.BTFReadable = true
		var fn *btf.Func
		if err := spec.TypeByName(s.Probe, &fn); err != nil {
			s.Details["probe_abi"] = err.Error()
		} else if proto, ok := fn.Type.(*btf.FuncProto); ok && len(proto.Params) == 1 {
			if ptr, ok := btf.UnderlyingType(proto.Params[0].Type).(*btf.Pointer); ok {
				if regs, ok := btf.UnderlyingType(ptr.Target).(*btf.Struct); ok {
					s.WrapperABI = regs.Name == "pt_regs"
				}
			}
		}
	}
	for _, name := range []string{"/sys/kernel/tracing/available_filter_functions", "/sys/kernel/debug/tracing/available_filter_functions"} {
		data, err := os.ReadFile(name)
		if err == nil {
			s.ProbeAvailable = ProbeListed(string(data), s.Probe)
			s.Details["probe_list"] = name
			break
		}
		s.Details[name] = err.Error()
	}
	if pid > 0 {
		base := fmt.Sprintf("/proc/%d/", pid)
		stat, err := os.ReadFile(base + "stat")
		if err == nil {
			s.TargetStartTicks, err = ParseStartTicks(string(stat))
		}
		if err != nil {
			s.Details["target_stat"] = err.Error()
		}
		status, statusErr := os.ReadFile(base + "status")
		uid, uidErr := parseUID(string(status))
		if statusErr == nil && uidErr == nil {
			s.TargetUID = uid
		} else {
			s.Details["target_uid"] = errors.Join(statusErr, uidErr).Error()
		}
		_, mapsErr := os.ReadFile(base + "maps")
		mem, memErr := os.Open(base + "mem")
		if memErr == nil {
			memErr = mem.Close()
		}
		if err := errors.Join(mapsErr, memErr); err != nil {
			s.Details["target_metadata"] = err.Error()
		}
		// Re-read starttime to detect exit/reuse during inspection.
		second, secondErr := os.ReadFile(base + "stat")
		var secondTicks uint64
		if secondErr == nil {
			secondTicks, secondErr = ParseStartTicks(string(second))
		}
		s.TargetMetadataReadable = statusErr == nil && uidErr == nil && mapsErr == nil && memErr == nil && secondErr == nil && s.TargetStartTicks != 0 && secondTicks == s.TargetStartTicks
	}
	program, err := ebpf.NewProgram(&ebpf.ProgramSpec{
		Name: "mw_preflight", Type: ebpf.SocketFilter, License: "GPL",
		Instructions: asm.Instructions{asm.Mov.Imm(asm.R0, 0), asm.Return()},
	})
	if err != nil {
		s.NativeError = err
		s.Details["bpf_load"] = err.Error()
	} else if err := program.Close(); err != nil {
		s.NativeError = err
		s.Details["bpf_close"] = err.Error()
	} else {
		s.BPFPermission = true
	}
	return s
}

func parseUID(status string) (uint32, error) {
	for line := range strings.SplitSeq(status, "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 2 && fields[0] == "Uid:" {
			uid, err := strconv.ParseUint(fields[1], 10, 32)
			return uint32(uid), err
		}
	}
	return 0, fmt.Errorf("proc status lacks target UID")
}
