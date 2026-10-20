//go:build mw_kernel

package correlation

import (
	"encoding/binary"
	"errors"
	"fmt"
	"os"
	"runtime"
	"sync"
	"testing"

	"github.com/cilium/ebpf"
	"golang.org/x/sys/unix"
)

func TestKernelSharedCorrelationAllocator(t *testing.T) {
	spec, err := ebpf.LoadCollectionSpec(os.Getenv("MW_CORRELATION_OBJECT"))
	if err != nil {
		t.Fatal(err)
	}
	coll, err := ebpf.NewCollection(spec)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		for name, p := range coll.Programs {
			if err := p.Close(); err != nil {
				t.Errorf("retire program %s: %v", name, err)
			}
		}
		for name, m := range coll.Maps {
			if err := m.Close(); err != nil {
				t.Errorf("retire map %s: %v", name, err)
			}
		}
	}()
	m, p := coll.Maps["correlation_ids"], coll.Programs["allocator_test"]
	info, err := m.Info()
	if err != nil {
		t.Fatal(err)
	}
	id, ok := info.ID()
	if !ok || info.Type != ebpf.Array || info.KeySize != 4 || info.ValueSize != 8 || info.MaxEntries != 1 {
		t.Fatal("kernel allocator ABI", info)
	}
	pi, err := p.Info()
	if err != nil {
		t.Fatal(err)
	}
	ids, ok := pi.MapIDs()
	if !ok || len(ids) != 1 || ids[0] != id {
		t.Fatal("program uses a different allocator", ids, id)
	}
	var affinity unix.CPUSet
	if err := unix.SchedGetaffinity(0, &affinity); err != nil {
		t.Fatal(err)
	}
	var cpus []uint32
	for cpu := 0; cpu < 1024 && len(cpus) < 8; cpu++ {
		if affinity.IsSet(cpu) {
			cpus = append(cpus, uint32(cpu))
		}
	}
	if len(cpus) < 2 {
		t.Fatal("multi-CPU qualification requires two allowed CPUs")
	}
	run := func(cpu uint32) (value uint64, err error) {
		// XDP test-run rejects BPF_F_TEST_RUN_ON_CPU. Pin this caller instead,
		// and verify the CPU reported by the actual BPF helper in the output.
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		var previous, selected unix.CPUSet
		if err := unix.SchedGetaffinity(0, &previous); err != nil {
			return 0, err
		}
		selected.Set(int(cpu))
		if err := unix.SchedSetaffinity(0, &selected); err != nil {
			return 0, err
		}
		defer func() { err = errors.Join(err, unix.SchedSetaffinity(0, &previous)) }()
		opts := &ebpf.RunOptions{Data: make([]byte, 64), DataOut: make([]byte, 64), Repeat: 1}
		ret, err := p.Run(opts)
		if err != nil || ret != 2 {
			return 0, fmt.Errorf("CPU %d run ret=%d: %w", cpu, ret, err)
		}
		if actual := binary.NativeEndian.Uint64(opts.DataOut[8:]); actual != uint64(cpu) {
			return 0, fmt.Errorf("requested CPU %d, ran on %d", cpu, actual)
		}
		return binary.NativeEndian.Uint64(opts.DataOut), nil
	}
	const count = 512
	results := make(chan uint64, count*len(cpus))
	errors := make(chan error, len(cpus))
	start := make(chan struct{})
	var workers sync.WaitGroup
	for _, cpu := range cpus {
		workers.Go(func() {
			<-start
			for range count {
				value, err := run(cpu)
				if err != nil {
					errors <- err
					return
				}
				results <- value
			}
		})
	}
	close(start)
	workers.Wait()
	close(results)
	close(errors)
	for err := range errors {
		t.Fatal(err)
	}
	seen := map[uint64]bool{}
	unavailable := 0
	for value := range results {
		if value == 0 {
			unavailable++
			continue
		}
		if value > 0x7fffffffffffffff || seen[value] {
			t.Fatal("duplicate/invalid allocator result", value)
		}
		seen[value] = true
	}
	if len(seen) == 0 || len(seen)+unavailable != count*len(cpus) {
		t.Fatal("lost allocator results", len(seen))
	}
	// Seeding is test-only, after every worker has joined. Production has no reset.
	zero := uint32(0)
	for _, last := range []uint64{1 << 53, 0x7ffffffffffffffe, 0x7fffffffffffffff, 1 << 63, ^uint64(0)} {
		if err := m.Update(zero, last, ebpf.UpdateAny); err != nil {
			t.Fatal(err)
		}
		want := uint64(0)
		if last < 0x7fffffffffffffff {
			want = last + 1
		}
		got, err := run(cpus[0])
		if err != nil {
			t.Fatal("boundary run", last, err)
		}
		var state uint64
		if err := m.Lookup(zero, &state); err != nil {
			t.Fatal("boundary lookup", last, err)
		}
		if got != want || state != max(last, want) {
			t.Fatal("boundary", last, got, state)
		}
	}
	t.Logf("actual production C allocator: map_id=%d CPUs=%v unique=%d unavailable=%d; high/saturated/invalid states checked; XDP test context only", id, cpus, len(seen), unavailable)
}
