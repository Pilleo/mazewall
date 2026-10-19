package model

import (
	"bytes"
	"encoding/json"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"os"
	"testing"
)

func TestSharedDTOsPreserveOriginalNativeJSON(t *testing.T) {
	data, err := os.ReadFile("../../../../hotspot-bridge/wire/testdata/legacy-serialized.jsonl")
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range bytes.Split(bytes.TrimSpace(data), []byte{'\n'}) {
		var r Record
		if err := json.Unmarshal(line, &r); err != nil {
			t.Fatal(err)
		}
		b, err := json.Marshal(r)
		if err != nil || !bytes.Equal(b, line) {
			t.Fatalf("native JSON changed: %s (%v)", b, err)
		}
		if r.Event != nil {
			e := Event(*r.Event)
			native, err := json.Marshal(e)
			plain, plainErr := json.Marshal(wire.Event(e))
			if err != nil || plainErr != nil || !bytes.Equal(native, plain) {
				t.Fatal("runtime Event adapter changed bytes", err, plainErr)
			}
		}
	}
}
