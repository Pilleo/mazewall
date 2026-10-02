package capture

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
	"sync"

	"github.com/inspektor-gadget/inspektor-gadget/pkg/datasource"
	jsonformatter "github.com/inspektor-gadget/inspektor-gadget/pkg/datasource/formatters/json"
	_ "github.com/inspektor-gadget/inspektor-gadget/pkg/environment/local"
	gadgetcontext "github.com/inspektor-gadget/inspektor-gadget/pkg/gadget-context"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators"
	_ "github.com/inspektor-gadget/inspektor-gadget/pkg/operators/ebpf"
	ocihandler "github.com/inspektor-gadget/inspektor-gadget/pkg/operators/oci-handler"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators/simple"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators/ustack"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/runtime/local"
	_ "github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer/otel"
	"github.com/opencontainers/go-digest"
	orasoci "oras.land/oras-go/v2/content/oci"

	"io.mazewall/experiments/hotspot-agentless/internal/preflight"
)

type Config struct {
	EventsOnly  bool
	StrictEpoch bool
	PID         int
	Image       string
	Store       string
	Enrichment  EnrichmentOptions
}

func (c Config) Validate() error {
	if c.EventsOnly && c.StrictEpoch {
		return fmt.Errorf("events-only capture cannot claim HotSpot epoch identity")
	}
	if c.PID <= 0 {
		return fmt.Errorf("target host PID must be positive")
	}
	_, hash, ok := strings.Cut(c.Image, "@")
	if !ok || digest.Digest(hash).Validate() != nil || !strings.HasPrefix(hash, "sha256:") {
		return fmt.Errorf("gadget image must be pinned by sha256 digest")
	}
	if c.Store == "" {
		return fmt.Errorf("gadget OCI store is required")
	}
	return nil
}

// RunDebug uses Gadget's existing synchronous symbolization for the first live
// capture gate. It is deliberately not the production structured enrichment API.
func RunDebug(ctx context.Context, cfg Config, output io.Writer) error {
	if cfg.EventsOnly {
		return fmt.Errorf("events-only mode requires structured event accounting")
	}
	if cfg.StrictEpoch {
		return fmt.Errorf("strict epoch mode requires structured capture")
	}
	if err := cfg.Validate(); err != nil {
		return err
	}
	report := preflight.Check(preflight.Observe(cfg.PID))
	if !report.Ready {
		return errors.Join(fmt.Errorf("preflight refused capture: %v", report.Failures), report.NativeError)
	}
	rt := local.New()
	if err := rt.Init(nil); err != nil {
		return err
	}
	defer rt.Close()
	store, err := orasoci.New(cfg.Store)
	if err != nil {
		return fmt.Errorf("open gadget OCI store: %w", err)
	}
	var mu sync.Mutex
	var outputErr error
	encoder := json.NewEncoder(output)
	operator := simple.New("mw-debug-output", simple.WithPriority(1000), simple.OnInit(func(gc operators.GadgetContext) error {
		for _, ds := range gc.GetDataSources() {
			formatter, err := jsonformatter.New(ds, jsonformatter.WithShowAll(true))
			if err != nil {
				return err
			}
			if err := ds.Subscribe(func(source datasource.DataSource, data datasource.Data) error {
				mu.Lock()
				defer mu.Unlock()
				if outputErr != nil {
					return outputErr
				}
				outputErr = encoder.Encode(struct {
					Kind    string          `json:"kind"`
					Payload json.RawMessage `json:"payload"`
				}{Kind: "capture_debug", Payload: json.RawMessage(formatter.Marshal(data))})
				if outputErr != nil {
					gc.Cancel()
				}
				return outputErr
			}, 1000); err != nil {
				return err
			}
		}
		return nil
	}), simple.OnStart(func(gc operators.GadgetContext) error {
		mu.Lock()
		defer mu.Unlock()
		outputErr = encoder.Encode(map[string]string{"kind": "collector_attached"})
		return outputErr
	}))
	gc := gadgetcontext.New(ctx, cfg.Image,
		gadgetcontext.WithName("mw-hotspot-openat"),
		gadgetcontext.WithOrasReadonlyTarget(pinnedTarget{Store: store, reference: cfg.Image}),
		gadgetcontext.WithDataOperators(ocihandler.OciHandler, &ustack.Operator{}, operator),
	)
	err = rt.RunGadget(gc, nil, map[string]string{
		"operator.oci.ebpf.target_pid":         strconv.Itoa(cfg.PID),
		"operator.oci.ebpf.target_start_ticks": strconv.FormatUint(report.Snapshot.TargetStartTicks, 10),
		"operator.oci.ebpf.collect_ustack":     "true",
		"operator.oci.ebpf.collect_otel_stack": "true",
		"operator.ustack.symbolizers":          "otel-ebpf-profiler",
	})
	mu.Lock()
	defer mu.Unlock()
	return errors.Join(err, outputErr)
}
