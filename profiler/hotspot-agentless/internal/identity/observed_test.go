package identity

import (
	"strings"
	"testing"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
)

func TestObservedExpectedTargetExactBeforeDiscovery(t *testing.T) {
	want := wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42}
	if err := validateObservedTarget(123, want, want); err != nil {
		t.Fatal("matching tuple", err)
	}
	for _, field := range []string{"pid", "birth", "cgroup", "mount"} {
		for _, missing := range []bool{false, true} {
			x := want
			v := func(n uint64) uint64 {
				if missing {
					return 0
				}
				return n + 1
			}
			switch field {
			case "pid":
				x.PID = uint32(v(uint64(x.PID)))
			case "birth":
				x.StartTicks = v(x.StartTicks)
			case "cgroup":
				x.CgroupID = v(x.CgroupID)
			case "mount":
				x.MountNS = v(x.MountNS)
			}
			if err := validateObservedTarget(123, x, want); err == nil || !strings.Contains(err.Error(), "expected target") {
				t.Fatal(field, missing, err)
			}
			if err := validateObservedTarget(123, want, x); err == nil {
				t.Fatal("changed current target admitted", field)
			}
		}
	}
	// Invalid expected fields must be rejected before even consulting /proc/PID.
	if _, err := ObserveProfile(123, wire.Target{PID: 123}); err == nil || !strings.Contains(err.Error(), "expected target") {
		t.Fatal(err)
	}
}

func TestObservedPinnedOrdinaryProfileDoesNotWeakenStrict(t *testing.T) {
	p := Profile{BuildID: JVMBuildID, SHA256: JVMSHA256, UseCompiler: true, useG1GC: true, tieredCompilation: true}
	if err := validateObservedProfile(p); err != nil {
		t.Fatal("ordinary tiered/G1", err)
	}
	if ValidateProfile(p) == nil {
		t.Fatal("ordinary runtime admitted as strict")
	}
	for _, mutate := range []func(*Profile){func(p *Profile) { p.BuildID = "unknown" }, func(p *Profile) { p.SHA256 = strings.Repeat("0", 64) }, func(p *Profile) { p.UseSerialGC = true }, func(p *Profile) { p.useG1GC = false }, func(p *Profile) { p.UseCompiler = false }, func(p *Profile) { p.tieredCompilation = false }} {
		x := p
		mutate(&x)
		if validateObservedProfile(x) == nil {
			t.Fatal("unsupported observed runtime admitted", x)
		}
	}
}
