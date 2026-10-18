//go:build mw_hotspot_qualification

package capture

import (
	"context"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"strings"
	"testing"
)

func TestObservedCandidateRequiresValidScopeBeforeControlOwner(t *testing.T) {
	if err := RunObservedCandidate(context.Background(), Config{}, nil, nil); err == nil || !strings.Contains(err.Error(), "observed profile/expected target") {
		t.Fatal(err)
	}
	cfg := Config{PID: 123, Image: "gadget@sha256:" + strings.Repeat("a", 64), Store: "unused", DrainFull: true, ExpectedTarget: wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42}}
	if err := RunObservedCandidate(context.Background(), cfg, nil, nil); err == nil || !strings.Contains(err.Error(), "parent control pipe") {
		t.Fatal("candidate did not pass pure negotiation to control boundary", err)
	}
}
