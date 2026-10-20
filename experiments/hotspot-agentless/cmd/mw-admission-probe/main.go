package main

import (
	"fmt"
	"os"
	"strconv"

	"io.mazewall/experiments/hotspot-agentless/internal/admissionprobe"
)

func main() {
	if len(os.Args) != 3 {
		fmt.Fprintln(os.Stderr, "usage: mw-admission-probe FILE MAXIMUM")
		os.Exit(2)
	}
	maximum, err := strconv.ParseInt(os.Args[2], 10, 64)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	digest, err := admissionprobe.DigestFile(os.Args[1], maximum)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Printf("%x\n", digest)
}
