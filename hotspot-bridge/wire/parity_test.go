package wire

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"testing"
)

func TestOriginalNativeSerializedBytesAndFrozenReady(t *testing.T) {
	data, err := os.ReadFile("testdata/legacy-serialized.jsonl")
	if err != nil {
		t.Fatal(err)
	}
	lines := bytes.Split(bytes.TrimSpace(data), []byte{'\n'})
	for i, line := range lines {
		var r Record
		if err := json.Unmarshal(line, &r); err != nil {
			t.Fatal(err)
		}
		b, err := json.Marshal(r)
		if err != nil || !bytes.Equal(b, line) {
			t.Fatalf("original native bytes changed at row %d: %s (%v)", i, b, err)
		}
	}
	var ready Record
	if err := json.Unmarshal(lines[1], &ready); err != nil {
		t.Fatal(err)
	}
	x := Expected{Schema: 2, Profile: Strict25, Provider: ready.Lifecycle.Provider, Target: Target{PID: ready.Lifecycle.PID, StartTicks: ready.Lifecycle.ProcessStartTicks}}
	d := NewDecoder(x)
	for _, line := range lines[:2] {
		if err := d.Accept(line); err != nil {
			t.Fatal(err)
		}
	}
	got, ok := d.Ready()
	if !ok || got.JVMBuildID != "e4dad22744e32e4d71755f4a58c5cf1f1d336f87" || got.Provider.PatchSHA256 != "e908bb2689936481f8a696dd2347457cc44b6344c1fa78ca65b37772032703eb" {
		t.Fatal("historical identity changed", got)
	}
	if _, err := d.Finish(nil); !errors.Is(err, ErrOrder) {
		t.Fatal("lifecycle-only projection became completion", err)
	}
}
