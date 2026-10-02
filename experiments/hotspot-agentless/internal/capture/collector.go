package capture

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"io"
	"io.mazewall/experiments/hotspot-agentless/internal/identity"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/inspektor-gadget/inspektor-gadget/pkg/datasource"
	gadgetcontext "github.com/inspektor-gadget/inspektor-gadget/pkg/gadget-context"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators"
	ocihandler "github.com/inspektor-gadget/inspektor-gadget/pkg/operators/oci-handler"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators/simple"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/runtime/local"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer"
	"golang.org/x/sys/unix"
	orasoci "oras.land/oras-go/v2/content/oci"

	"io.mazewall/experiments/hotspot-agentless/internal/model"
	jsonoutput "io.mazewall/experiments/hotspot-agentless/internal/output"
	"io.mazewall/experiments/hotspot-agentless/internal/preflight"
)

type providerSink struct{ model.Sink }

func (s providerSink) Write(ctx context.Context, record model.Record) error {
	if record.Evidence != nil && record.Evidence.Provider.Name == "" {
		record.Evidence.Provider = providerIdentity()
	}
	return s.Sink.Write(ctx, record)
}

// Run uses one Gadget symbolizer and its existing process discovery, cache,
// waiters, map replacements and pruning. Only event enrichment is queued here.
func Run(ctx context.Context, cfg Config, writer io.Writer) (resultErr error) {
	if err := cfg.Validate(); err != nil {
		return err
	}
	if cfg.StrictEpoch {
		var stop context.CancelFunc
		ctx, stop = context.WithTimeout(ctx, 60*time.Second)
		defer stop()
	}
	if GadgetPatchSHA == "" {
		return fmt.Errorf("structured collector requires the provenance-pinned make build")
	}
	report := preflight.Check(preflight.Observe(cfg.PID))
	if !report.Ready {
		return errors.Join(fmt.Errorf("preflight refused capture: %v", report.Failures), report.NativeError)
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	pidfd, err := unix.PidfdOpen(cfg.PID, 0)
	if err != nil {
		return fmt.Errorf("own target pidfd: %w", err)
	}
	defer func() { resultErr = errors.Join(resultErr, unix.Close(pidfd)) }()
	stat, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", cfg.PID))
	if err != nil {
		return err
	}
	ticks, err := preflight.ParseStartTicks(string(stat))
	if err != nil || ticks != report.Snapshot.TargetStartTicks {
		return errors.Join(fmt.Errorf("target changed during startup"), err)
	}
	var execBarrier *identity.ExecBarrier
	var epochTarget libpf.HotSpotEpochTarget
	var checks []func() error
	if cfg.StrictEpoch {
		execBarrier, err = identity.NewExecBarrier(cfg.PID)
		if err != nil {
			return err
		}
		defer func() { resultErr = errors.Join(resultErr, execBarrier.Close()) }()
		checks = append(checks, execBarrier.Check)
		target, err := identity.Discover(cfg.PID, ticks)
		if err != nil {
			return err
		}
		if err = execBarrier.Check(); err != nil {
			return err
		}
		epochTarget = libpf.HotSpotEpochTarget{PID: libpf.PID(target.PID), ProcessStartTicks: ticks, CounterAddress: libpf.Address(target.CounterAddress), InitialEpoch: target.InitialEpoch}
	}
	fatal := fatalState{cancel: cancel}
	watchDone := make(chan struct{})
	var watchErr error
	watchConsumed := false
	go func() {
		defer close(watchDone)
		watchErr = watchTarget(ctx, pidfd, cancel, checks...)
	}()
	defer func() {
		cancel()
		<-watchDone
		if !watchConsumed {
			resultErr = errors.Join(resultErr, watchErr)
		}
	}()
	var random [16]byte
	if _, err := rand.Read(random[:]); err != nil {
		return err
	}
	sessionID := fmt.Sprintf("%x", random)
	sink := providerSink{jsonoutput.NewJSONL(writer)}
	rt := local.New()
	if err := rt.Init(nil); err != nil {
		return err
	}
	defer rt.Close()
	store, err := orasoci.New(cfg.Store)
	if err != nil {
		return err
	}
	opts := cfg.Enrichment
	if opts == (EnrichmentOptions{}) {
		opts = DefaultEnrichmentOptions()
	}
	var sym *symbolizer.Symbolizer
	var enrichment *Enricher
	var callbackMu sync.Mutex
	var callbackErr error
	var reportedLoss datasource.ReportedLossReader
	var eventsOnly *eventsOnlyState
	operator := simple.New("mw-structured", simple.WithPriority(100), simple.OnInit(func(gc operators.GadgetContext) error {
		var err error
		if cfg.EventsOnly {
			eventsOnly = &eventsOnlyState{sink: sink}
		} else {
			sym, err = symbolizer.NewSymbolizer(symbolizer.SymbolizerOptions{UseOtelEbpfProfiler: true, Context: ctx, HotSpotEpochTarget: epochTarget, OnHotSpotFatal: fatal.Record})
			if err != nil {
				return err
			}
			for name, replacement := range sym.GetEbpfReplacements() {
				gc.SetVar(name, replacement)
			}
			enrichment, err = NewEnricher(ctx, opts, gadgetLookup{symbolizer: sym, sessionID: sessionID, pid: uint32(cfg.PID), startTicks: ticks, strictEpoch: cfg.StrictEpoch, execBarrier: execBarrier}, sink, LinuxBootClock{}, cancel)
			if err != nil {
				return err
			}
		}
		found := false
		for _, ds := range gc.GetDataSources() {
			if ds.Name() != "openat" {
				continue
			}
			found = true
			reportedLoss, _ = ds.(datasource.ReportedLossReader)
			decoder, err := newEventDecoder(ds, sessionID, uint32(cfg.PID), ticks, cfg.StrictEpoch)
			if err != nil {
				return err
			}
			if err := ds.Subscribe(func(source datasource.DataSource, data datasource.Data) error {
				callbackMu.Lock()
				defer callbackMu.Unlock()
				if callbackErr != nil {
					return callbackErr
				}
				event, err := decoder.Decode(data)
				if err == nil {
					if eventsOnly != nil {
						err = eventsOnly.Submit(ctx, event)
					} else {
						err = enrichment.Submit(event)
					}
				}
				if err != nil {
					callbackErr = err
					gc.Cancel()
				}
				return err
			}, 1000); err != nil {
				return err
			}
		}
		if !found {
			return fmt.Errorf("openat datasource missing")
		}
		return nil
	}), simple.OnStart(func(gc operators.GadgetContext) error {
		return sink.Write(ctx, model.Record{SchemaVersion: 1, Kind: "collector_attached", SessionID: sessionID})
	}))
	gc := gadgetcontext.New(ctx, cfg.Image, gadgetcontext.WithName("mw-hotspot-openat-structured"), gadgetcontext.WithOrasReadonlyTarget(pinnedTarget{Store: store, reference: cfg.Image}), gadgetcontext.WithDataOperators(ocihandler.OciHandler, operator))
	params := map[string]string{"operator.oci.ebpf.target_pid": strconv.Itoa(cfg.PID), "operator.oci.ebpf.target_start_ticks": strconv.FormatUint(ticks, 10), "operator.oci.ebpf.collect_ustack": "true", "operator.oci.ebpf.collect_otel_stack": "true", "operator.oci.ebpf.target_safepoint_counter": strconv.FormatUint(uint64(epochTarget.CounterAddress), 10), "operator.oci.ebpf.target_initial_safepoint_epoch": strconv.FormatUint(epochTarget.InitialEpoch, 10)}
	if cfg.EventsOnly {
		params["operator.oci.ebpf.collect_ustack"] = "false"
		params["operator.oci.ebpf.collect_otel_stack"] = "false"
	}
	runErr := rt.RunGadget(gc, nil, params)
	cancel()
	<-watchDone
	watchConsumed = true
	var finishErr error
	if enrichment != nil {
		finishErr = enrichment.Finish()
	}
	var definitionHealth *model.ReaderObservation
	var definitionStop *model.DefinitionStopObservation
	var definitionStopErr error
	if sym != nil {
		stopCtx, stopCancel := context.WithTimeout(context.Background(), 2*time.Second)
		stopped, stopErr := sym.StopDefinitions(stopCtx)
		stopCancel()
		definitionStop = definitionStopModel(stopped)
		definitionStopErr = stopErr
		definitionHealth = reportedDefinitionHealth(sym)
		if stopErr == nil {
			sym.Close()
		}
	}
	callbackMu.Lock()
	err = errors.Join(runErr, finishErr, callbackErr, watchErr, fatal.Err(), definitionStopErr)
	callbackMu.Unlock()
	summary := model.Summary{CollectionHealth: "unknown"}
	summary.DefinitionStop = definitionStop
	applyCollectionHealth(&summary, reportedEventHealth(reportedLoss))
	summary.DefinitionReaderObservation = definitionHealth
	if definitionHealth != nil {
		summary.CollectionHealth = "partial"
	}
	if enrichment != nil {
		summary.Stats = enrichment.Stats()
		s := summary.Stats
		summary.TerminalAccountingComplete = err == nil && s.Resolved+s.Unavailable+s.Skipped == s.Received
	}
	if eventsOnly != nil {
		summary.Stats = eventsOnly.stats
		summary.TerminalAccountingComplete = err == nil && eventsOnly.stats.Received == eventsOnly.stats.Skipped
	}
	if err != nil {
		summary.Errors = []string{err.Error()}
	}
	writeErr := sink.Write(context.Background(), model.Record{SchemaVersion: 1, Kind: "session_summary", SessionID: sessionID, Summary: &summary})
	return errors.Join(err, writeErr)
}
