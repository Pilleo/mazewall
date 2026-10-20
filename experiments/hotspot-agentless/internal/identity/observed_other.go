//go:build !linux

package identity

import (
	"fmt"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
)

func ObserveProfile(uint32, wire.Target) (ObservedTarget, error) {
	return ObservedTarget{}, fmt.Errorf("observed profile requires Linux")
}
func CheckObservedTarget(uint32, wire.Target) error {
	return fmt.Errorf("observed profile requires Linux")
}
