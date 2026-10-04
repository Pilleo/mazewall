package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"io.mazewall/experiments/hotspot-agentless/internal/capture"
	"io.mazewall/experiments/hotspot-agentless/internal/preflight"

	// Link Gadget's actual OTel integration so build verification exercises the
	// selected replacement graph. Registration does not start the profiler.
	_ "github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer/otel"
)

func main() { os.Exit(run(os.Args[1:])) }

func run(args []string) int {
	flags := flag.NewFlagSet("mw-hotspot-collector", flag.ContinueOnError)
	pid := flags.Int("pid", 0, "ordinary-user target host PID")
	check := flags.Bool("preflight", false, "inspect environment without attaching a probe")
	captureMode := flags.Bool("capture", false, "run the first live capture gate")
	debugRendered := flags.Bool("debug-rendered", false, "use the legacy debug rendering gate")
	identityMode := flags.String("hotspot-identity", "unknown", "HotSpot identity: unknown or safepoint-epoch (pinned interpreter/Serial only)")
	collectionMode := flags.String("collection-mode", "full", "Capture mode: full or events (no Java unwinder)")
	drainEvents := flags.Bool("drain-events", false, "owned bounded stop for the events-only openat ring")
	drainFull := flags.Bool("drain-full", false, "owned event/definition drain for strict full capture")
	workers := flags.Int("workers", 4, "bounded enrichment workers")
	maxPending := flags.Int("max-pending", 1024, "pending events including active lookups")
	maxBytes := flags.Int("max-pending-bytes", 16*1024*1024, "pending event payload byte cap")
	enrichmentDeadline := flags.Duration("enrichment-deadline", time.Second, "absolute boot-time event enrichment budget")
	image := flags.String("image", "", "digest-pinned gadget image")
	store := flags.String("store", "", "local OCI store containing the gadget")
	duration := flags.Duration("duration", 15*time.Second, "maximum capture duration")
	if err := flags.Parse(args); err != nil {
		return 2
	}
	if (*identityMode != "unknown" && *identityMode != "safepoint-epoch") || (*identityMode == "safepoint-epoch" && (*debugRendered || *duration > 60*time.Second)) {
		fmt.Fprintln(os.Stderr, "invalid HotSpot identity mode, rendering mode or strict session duration")
		return 2
	}
	if (*collectionMode != "full" && *collectionMode != "events") || (*collectionMode == "events" && (*identityMode != "unknown" || *debugRendered)) {
		fmt.Fprintln(os.Stderr, "invalid collection mode or incompatible identity/rendering option")
		return 2
	}
	if *drainEvents && (!*captureMode || *collectionMode != "events" || *debugRendered) {
		fmt.Fprintln(os.Stderr, "--drain-events requires structured events-only capture")
		return 2
	}
	if *drainFull && (!*captureMode || *collectionMode != "full" || *identityMode != "safepoint-epoch" || *debugRendered || *drainEvents) {
		fmt.Fprintln(os.Stderr, "--drain-full requires strict structured full capture")
		return 2
	}
	if *check == *captureMode || flags.NArg() != 0 || *duration <= 0 {
		fmt.Fprintln(os.Stderr, "choose --preflight or --capture; --capture requires --pid, --image and --store")
		return 2
	}
	if *captureMode {
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		ctx, cancel := context.WithTimeout(ctx, *duration)
		defer cancel()
		cfg := capture.Config{PID: *pid, Image: *image, Store: *store, Enrichment: capture.EnrichmentOptions{Workers: *workers, MaxPending: *maxPending, MaxPendingBytes: *maxBytes, Deadline: *enrichmentDeadline}}
		cfg.StrictEpoch = *identityMode == "safepoint-epoch"
		cfg.EventsOnly = *collectionMode == "events"
		cfg.DrainEvents = *drainEvents
		cfg.DrainFull = *drainFull
		runCapture := capture.Run
		if *debugRendered {
			runCapture = capture.RunDebug
		}
		if err := runCapture(ctx, cfg, os.Stdout); err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		return 0
	}
	report := preflight.Check(preflight.Observe(*pid))
	if err := json.NewEncoder(os.Stdout).Encode(report); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	if !report.Ready {
		return 1
	}
	return 0
}
