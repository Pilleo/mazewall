package capture

import "testing"

func TestConfigRejectsUnpinnedGadgetAndMissingTarget(t *testing.T) {
	for _, cfg := range []Config{
		{PID: 123, Image: "mw-hotspot-openat:latest", Store: "/tmp/store"},
		{PID: 0, Image: "mw-hotspot-openat@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", Store: "/tmp/store"},
		{PID: 123, Image: "mw-hotspot-openat@sha256:bad", Store: "/tmp/store"},
		{PID: 123, Image: "mw-hotspot-openat@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
	} {
		if err := cfg.Validate(); err == nil {
			t.Fatalf("accepted unsafe config %+v", cfg)
		}
	}
	valid := Config{PID: 123, Image: "mw-hotspot-openat@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", Store: "/tmp/store"}
	if err := valid.Validate(); err != nil {
		t.Fatal(err)
	}
}
