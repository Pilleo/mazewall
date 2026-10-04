package main

import (
	"github.com/inspektor-gadget/inspektor-gadget/cmd/common/image"
	"os"
)

// Keep build/packaging mechanics in Gadget's maintained command.
func main() {
	if err := image.NewBuildCmd().Execute(); err != nil {
		os.Exit(1)
	}
}
