//go:build mw_kernel

package accounting

import (
	"testing"

	"github.com/cilium/ebpf"
)

func TestActualMapReplacementDoesNotCertifyOriginalOwner(t *testing.T) {
	makeMap := func() *ebpf.Map {
		value, err := ebpf.NewMap(&ebpf.MapSpec{Type: ebpf.PerCPUArray, KeySize: 4, ValueSize: 32, MaxEntries: 1})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			if err := value.Close(); err != nil {
				t.Error(err)
			}
		})
		return value
	}
	original, replacement := makeMap(), makeMap()
	originalInfo, err := original.Info()
	if err != nil {
		t.Fatal(err)
	}
	replacementInfo, err := replacement.Info()
	if err != nil {
		t.Fatal(err)
	}
	originalID, ok := originalInfo.ID()
	if !ok {
		t.Fatal("actual original map ID missing")
	}
	replacementID, ok := replacementInfo.ID()
	if !ok || replacementID == originalID {
		t.Fatal("distinct actual replacement map ID missing")
	}
	cpus, err := ebpf.PossibleCPU()
	if err != nil {
		t.Fatal(err)
	}
	rows := make([]CPUCounts, cpus)
	zero := uint32(0)
	if err := original.Lookup(&zero, &rows); err != nil {
		t.Fatal(err)
	}
	expected := Binding{Session: "s", PID: 123, Birth: 456, MapID: uint32(originalID), PossibleCPUs: cpus}
	snapshot := Snapshot{Binding: expected, Rows: rows, Quiesced: true, Joined: true}
	snapshot.MapID = uint32(replacementID)
	if _, proof, err := BindAndEvaluate(expected, snapshot); err == nil && proof.Complete {
		t.Fatal("foreign actual map certified")
	}
	snapshot.Binding = expected
	snapshot.Rows = rows[:len(rows)-1]
	if _, proof, err := BindAndEvaluate(expected, snapshot); err == nil || proof.Complete {
		t.Fatal("missing actual CPU slot certified")
	}
	t.Logf("original_map=%d replacement_map=%d possible_cpus=%d replacement/missing-slot refused", originalID, replacementID, cpus)
}
