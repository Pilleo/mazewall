//go:build mw_kernel

package accounting

import (
	"errors"
	"os"
	"testing"
	"time"

	"github.com/cilium/ebpf"
	"github.com/cilium/ebpf/link"
	"github.com/cilium/ebpf/ringbuf"
	"golang.org/x/sys/unix"
)

// Isolated actual openat producer. This is qualification, not the production collector's all-source proof.
func TestKernelOpenatCountersPressureAndOverflow(t *testing.T) {
	counter, err := ebpf.NewMap(&ebpf.MapSpec{Type: ebpf.PerCPUArray, KeySize: 4, ValueSize: 32, MaxEntries: 1})
	if err != nil {
		t.Fatal(err)
	}
	defer counter.Close()
	ring, err := ebpf.NewMap(&ebpf.MapSpec{Type: ebpf.RingBuf, MaxEntries: 4096})
	if err != nil {
		t.Fatal(err)
	}
	defer ring.Close()
	instructions := counterProgram(counter.FD(), ring.FD(), os.Getpid())
	program, err := ebpf.NewProgram(&ebpf.ProgramSpec{Type: ebpf.Kprobe, License: "GPL", Instructions: instructions})
	if err != nil {
		t.Fatal(err)
	}
	defer program.Close()
	producer, err := link.Kprobe("__x64_sys_openat", program, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer producer.Close()
	for i := 0; i < 512; i++ {
		fd, e := unix.Open("/dev/null", unix.O_RDONLY, 0)
		if e != nil {
			t.Fatal(e)
		}
		if e = unix.Close(fd); e != nil {
			t.Fatal(e)
		}
	}
	if err = producer.Close(); err != nil {
		t.Fatal(err)
	}
	cpus, err := ebpf.PossibleCPU()
	if err != nil {
		t.Fatal(err)
	}
	rows := make([]CPUCounts, cpus)
	zero := uint32(0)
	if err = counter.Lookup(&zero, &rows); err != nil {
		t.Fatal(err)
	}
	counts, err := Aggregate(rows, cpus, true)
	if err != nil {
		t.Fatal(err)
	}
	if counts.Attempted != 512 || counts.ReserveFailed == 0 {
		t.Fatal("missing actual pressure denominator", counts)
	}
	reader, err := ringbuf.NewReader(ring)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	reader.SetDeadline(time.Now().Add(time.Second))
	if err = reader.Flush(); err != nil {
		t.Fatal(err)
	}
	var received uint64
	for {
		_, e := reader.Read()
		if errors.Is(e, ringbuf.ErrFlushed) {
			break
		}
		if e != nil {
			t.Fatal(e)
		}
		received++
	}
	counts.Quiesced = true
	counts.Joined = true
	counts.Received = received
	counts.Terminals = received
	proof := Evaluate(counts)
	if !proof.Complete || proof.Lost != counts.ReserveFailed {
		t.Fatal("conservation failure", counts, proof)
	}
	t.Logf("actual_openat attempted=%d submitted=%d reserve_failed=%d received=%d lost=%d possible_cpus=%d", counts.Attempted, counts.Submitted, counts.ReserveFailed, received, proof.Lost, cpus)
	for i := range rows {
		rows[i] = CPUCounts{Attempted: ^uint64(0), Submitted: ^uint64(0)}
	}
	if err = counter.Update(&zero, rows, ebpf.UpdateAny); err != nil {
		t.Fatal(err)
	}
	producer, err = link.Kprobe("__x64_sys_openat", program, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer producer.Close()
	fd, err := unix.Open("/dev/null", unix.O_RDONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	if err = unix.Close(fd); err != nil {
		t.Fatal(err)
	}
	if err = producer.Close(); err != nil {
		t.Fatal(err)
	}
	if err = counter.Lookup(&zero, &rows); err != nil {
		t.Fatal(err)
	}
	if _, err = Aggregate(rows, cpus, true); err == nil {
		t.Fatal("sticky native overflow falsely known")
	}
}
