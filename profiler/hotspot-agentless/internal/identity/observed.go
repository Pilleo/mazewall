package identity

import (
	"fmt"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
)

type ObservedTarget struct {
	Target                wire.Target
	JVMBuildID, JVMSHA256 string
}

func validateObservedTarget(pid uint32, expected, actual wire.Target) error {
	if pid == 0 || expected.PID != pid || expected.StartTicks == 0 || expected.CgroupID == 0 || expected.MountNS == 0 || actual.PID != pid || actual.StartTicks != expected.StartTicks || actual.CgroupID != expected.CgroupID || actual.MountNS != expected.MountNS {
		return fmt.Errorf("missing or changed expected target PID/birth/cgroup/mount namespace")
	}
	return nil
}
func validateObservedProfile(p Profile) error {
	if p.BuildID != JVMBuildID || p.SHA256 != JVMSHA256 || p.UseSerialGC || !p.UseCompiler || !p.useG1GC || !p.tieredCompilation {
		return fmt.Errorf("unsupported observed JVM: need pinned Temurin25 ordinary tiered/G1")
	}
	return nil
}
