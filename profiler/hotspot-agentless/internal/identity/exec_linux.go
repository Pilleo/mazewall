//go:build linux

package identity

import (
	"errors"
	"fmt"
	"sync"

	"github.com/cilium/ebpf"
	"github.com/cilium/ebpf/asm"
	"github.com/cilium/ebpf/link"
)

var ErrTargetExec = errors.New("strict HotSpot target executed a new image")

// ExecBarrier owns one monotonic session poison word and its exec attachment.
// This is not a metadata cache. Attach it before discovering the target JVM.
type ExecBarrier struct {
	mu      sync.Mutex
	state   *ebpf.Map
	program *ebpf.Program
	hook    link.Link
	closed  bool
}

func NewExecBarrier(pid int) (*ExecBarrier, error) {
	if pid <= 0 || uint64(pid) > 0x7fffffff {
		return nil, fmt.Errorf("invalid exec barrier PID")
	}
	b := &ExecBarrier{}
	m, err := ebpf.NewMap(&ebpf.MapSpec{Name: "mw_exec_poison", Type: ebpf.Array, KeySize: 4, ValueSize: 4, MaxEntries: 1})
	if err != nil {
		return nil, fmt.Errorf("create exec barrier: %w", err)
	}
	b.state = m
	p, err := ebpf.NewProgram(&ebpf.ProgramSpec{Name: "mw_exec_barrier", Type: ebpf.TracePoint, License: "GPL", Instructions: asm.Instructions{
		asm.FnGetCurrentPidTgid.Call(), asm.RSh.Imm(asm.R0, 32), asm.JNE.Imm(asm.R0, int32(pid), "done"),
		asm.StoreImm(asm.RFP, -4, 0, asm.Word), asm.StoreImm(asm.RFP, -8, 1, asm.Word),
		asm.LoadMapPtr(asm.R1, m.FD()), asm.Mov.Reg(asm.R2, asm.RFP), asm.Add.Imm(asm.R2, -4), asm.Mov.Reg(asm.R3, asm.RFP), asm.Add.Imm(asm.R3, -8), asm.Mov.Imm(asm.R4, 0), asm.FnMapUpdateElem.Call(),
		asm.Mov.Imm(asm.R0, 0).WithSymbol("done"), asm.Return(),
	}})
	if err != nil {
		return nil, errors.Join(fmt.Errorf("load exec barrier: %w", err), b.Close())
	}
	b.program = p
	h, err := link.Tracepoint("sched", "sched_process_exec", p, nil)
	if err != nil {
		return nil, errors.Join(fmt.Errorf("attach exec barrier: %w", err), b.Close())
	}
	b.hook = h
	return b, nil
}
func (b *ExecBarrier) Check() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		return fmt.Errorf("exec barrier closed")
	}
	var state uint32
	if err := b.state.Lookup(uint32(0), &state); err != nil {
		return fmt.Errorf("read exec barrier: %w", err)
	}
	if state == 1 {
		return ErrTargetExec
	}
	if state != 0 {
		return fmt.Errorf("invalid exec barrier state %d", state)
	}
	return nil
}
func (b *ExecBarrier) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		return nil
	}
	b.closed = true
	var err error
	if b.hook != nil {
		err = errors.Join(err, b.hook.Close())
	}
	if b.program != nil {
		err = errors.Join(err, b.program.Close())
	}
	if b.state != nil {
		err = errors.Join(err, b.state.Close())
	}
	return err
}
