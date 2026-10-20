//go:build mw_kernel

package accounting

import "github.com/cilium/ebpf/asm"

// Mirrors candidate32byte counters at the actual filtered openat reserve/submit boundary.
func counterProgram(counterFD, ringFD, pid int) asm.Instructions {
	inc := func(offset int16, label string) asm.Instructions {
		return asm.Instructions{
			asm.LoadMem(asm.R8, asm.R6, offset, asm.DWord),
			asm.JNE.Imm(asm.R8, -1, label+"_add"),
			asm.Mov.Imm(asm.R9, 1),
			asm.StoreMem(asm.R6, 24, asm.R9, asm.DWord),
			asm.Add.Imm(asm.R8, 1).WithSymbol(label + "_add"),
			asm.StoreMem(asm.R6, offset, asm.R8, asm.DWord),
		}
	}
	code := asm.Instructions{
		asm.FnGetCurrentPidTgid.Call(), asm.RSh.Imm(asm.R0, 32), asm.JNE.Imm(asm.R0, int32(pid), "exit"),
		asm.StoreImm(asm.R10, -4, 0, asm.Word), asm.LoadMapPtr(asm.R1, counterFD), asm.Mov.Reg(asm.R2, asm.R10), asm.Add.Imm(asm.R2, -4),
		asm.FnMapLookupElem.Call(), asm.JEq.Imm(asm.R0, 0, "exit"), asm.Mov.Reg(asm.R6, asm.R0),
	}
	code = append(code, inc(0, "attempt")...)
	code = append(code, asm.LoadMapPtr(asm.R1, ringFD), asm.Mov.Imm(asm.R2, 8), asm.Mov.Imm(asm.R3, 0), asm.FnRingbufReserve.Call(), asm.JEq.Imm(asm.R0, 0, "failed"),
		asm.Mov.Reg(asm.R7, asm.R0), asm.StoreMem(asm.R7, 0, asm.R8, asm.DWord), asm.Mov.Reg(asm.R1, asm.R7), asm.Mov.Imm(asm.R2, 0), asm.FnRingbufSubmit.Call())
	code = append(code, inc(8, "submitted")...)
	code = append(code, asm.Ja.Label("exit"), asm.Mov.Reg(asm.R6, asm.R6).WithSymbol("failed"))
	code = append(code, inc(16, "reserve_failed")...)
	return append(code, asm.Mov.Imm(asm.R0, 0).WithSymbol("exit"), asm.Return())
}
