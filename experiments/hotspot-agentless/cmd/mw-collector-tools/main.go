package main

import (
	"errors"
	"flag"
	"fmt"
	"os"

	"io.mazewall/experiments/hotspot-agentless/internal/tooling"
)

func main() {
	root, err := os.Getwd()
	if err == nil {
		err = tooling.Run(root, os.Args[1:])
	}
	if errors.Is(err, flag.ErrHelp) {
		return
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
