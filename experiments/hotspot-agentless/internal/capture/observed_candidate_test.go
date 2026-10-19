//go:build mw_hotspot_qualification

package capture

import (
	"context"
	"errors"
	"fmt"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"strings"
	"syscall"
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

func TestObservedTargetExitCauseRequiresOriginalSentinel(t *testing.T) {
	for _, test := range []struct {
		name string
		err  error
		want bool
	}{
		{"wrapped", errors.Join(context.Canceled, fmt.Errorf("watcher: %w", errTargetExited)), true},
		{"nil", nil, false},
		{"same message", errors.New(errTargetExited.Error()), false},
		{"permission", syscall.EPERM, false},
		{"deadline", context.DeadlineExceeded, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			if got := ObservedTargetExited(test.err); got != test.want {
				t.Fatal("incorrect typed target-exit classification", got, test.want)
			}
		})
	}
}
