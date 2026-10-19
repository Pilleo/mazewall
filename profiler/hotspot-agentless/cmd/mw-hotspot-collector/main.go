package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/signal"
	"syscall"

	"io.mazewall/experiments/hotspot-agentless/internal/capture"
	"io.mazewall/experiments/hotspot-agentless/internal/preflight"

	// Link Gadget's actual OTel integration so build verification exercises the
	// selected replacement graph. Registration does not start the profiler.
	_ "github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer/otel"
)

func main() { os.Exit(run(os.Args[1:])) }

func run(args []string) int {
	r, err := parseCaptureRequest(args)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 2
	}
	if !r.preflight {
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		ctx, cancel := context.WithTimeout(ctx, r.Duration)
		defer cancel()
		cfg := r.config
		runCapture := capture.Run
		if r.Protocol == capture.ProductionSchema {
			runCapture = capture.RunProduction
			if r.Control {
				runCapture = func(ctx context.Context, cfg capture.Config, writer io.Writer) error {
					return capture.RunProductionControlled(ctx, cfg, writer, os.Stdin)
				}
			}
		}
		if r.Protocol == capture.ObservationSchema {
			runCapture = capture.RunObserved
			if r.Control {
				runCapture = func(ctx context.Context, cfg capture.Config, writer io.Writer) error {
					return capture.RunObservedControlled(ctx, cfg, writer, os.Stdin)
				}
			}
		}
		if r.debugRendered {
			runCapture = capture.RunDebug
		}
		if err := runCapture(ctx, cfg, os.Stdout); err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		return 0
	}
	report := preflight.Check(preflight.Observe(r.config.PID))
	if err := json.NewEncoder(os.Stdout).Encode(report); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if !report.Ready {
		return 1
	}
	return 0
}
