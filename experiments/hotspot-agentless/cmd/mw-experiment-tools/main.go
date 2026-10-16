package main

import (
	"encoding/json"
	"fmt"
	"io.mazewall/experiments/hotspot-agentless/internal/benchmark"
	"io.mazewall/experiments/hotspot-agentless/internal/candidate"
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
	"os"
)

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
func run(args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("usage: mw-experiment-tools benchmark|candidates|verify-runtime|verify-loom|token")
	}
	root, err := os.Getwd()
	if err != nil {
		return err
	}
	switch args[0] {
	case "loss-source":
		if len(args) != 1 {
			return fmt.Errorf("loss-source takes no arguments")
		}
		source, err := driver.PrepareLossSource(root)
		if err != nil {
			return err
		}
		fmt.Println(source)
		return nil
	case "corpus-environment", "unloading-environment", "reference-environment":
		return driver.RecordEnvironment(root, args[0][:len(args[0])-len("-environment")], args[1:])
	case "matrix-report":
		if len(args) != 2 {
			return fmt.Errorf("matrix-report requires directory")
		}
		return driver.WriteMatrixReport(args[1])
	case "preflight-verdict":
		if len(args) != 2 {
			return fmt.Errorf("preflight-verdict requires directory")
		}
		return driver.VerifyPreflight(args[1])
	case "benchmark":
		return benchmark.Run(root, args[1:])
	case "candidates":
		return candidate.Run(root, args[1:])
	case "verify-runtime", "verify-loom":
		if len(args) != 3 {
			return fmt.Errorf("%s requires oracle and capture paths", args[0])
		}
		verdict, err := candidate.VerifyFiles(args[0] == "verify-loom", args[1], args[2])
		if err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(verdict)
	case "token":
		if len(args) != 1 {
			return fmt.Errorf("token takes no arguments")
		}
		token, err := driver.Token(8)
		if err != nil {
			return err
		}
		fmt.Println(token)
		return nil
	default:
		return fmt.Errorf("unknown experiment command %q", args[0])
	}
}
