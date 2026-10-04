//go:build !linux

package identity

import "fmt"

func Discover(int, uint64) (Target, error) {
	return Target{}, fmt.Errorf("HotSpot epoch profile requires Linux")
}
