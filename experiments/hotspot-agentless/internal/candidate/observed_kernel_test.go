//go:build mw_hotspot_qualification

package candidate

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/Pilleo/mazewall/hotspot-bridge/bundle"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"golang.org/x/sys/unix"
	"io.mazewall/experiments/hotspot-agentless/internal/capture"
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
	"io.mazewall/experiments/hotspot-agentless/internal/identity"
)

// Only the explicitly tagged qualification binary has this internal process entry.
func TestObservedCollectorProcess(t *testing.T) {
	input := os.Getenv("MW_OBSERVED_CAPTURE_INPUT")
	if input == "" {
		return
	}
	var cfg observedCollectorInput
	err := driver.ReadJSON(input, &cfg)
	if err == nil {
		ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
		err = capture.RunObservedCandidate(ctx, capture.Config{PID: int(cfg.Target.PID), ExpectedTarget: cfg.Target, DrainFull: true, Image: cfg.Image, Store: cfg.Store}, os.Stdout, os.Stdin)
		cancel()
	}
	if err != nil {
		failure := observedCollectorFailure{Error: err.Error(), TargetExec: errors.Is(err, identity.ErrTargetExec), TargetExit: capture.ObservedTargetExited(err)}
		err = errors.Join(err, driver.WriteJSON(filepath.Join(filepath.Dir(input), "collector-failure.json"), failure))
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	// Exit directly: test-runner PASS text is not part of the JSONL protocol.
	os.Exit(0)
}

func TestObservedMatrixNative(t *testing.T) {
	assets, output := os.Getenv("MW_HOTSPOT_ASSETS"), os.Getenv("MW_OBSERVED_OUTPUT")
	if assets == "" || output == "" {
		t.Fatal("explicit native gate inputs required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 180*time.Second)
	defer cancel()
	v, err := RunObservedMatrix(ctx, assets, output)
	if err != nil {
		t.Fatal(err)
	}
	if v.Status != "qualified" {
		t.Fatalf("ordinary-JVM gate rejected: %#v", v)
	}
}

func TestObservedPublicCLINative(t *testing.T) {
	t.Setenv("MW_OBSERVED_PUBLIC_CLI", "true")
	TestObservedMatrixNative(t)
	output := os.Getenv("MW_OBSERVED_OUTPUT")
	assets := os.Getenv("MW_HOTSPOT_ASSETS")
	want, err := driver.HashFile(filepath.Join(assets, "mw-hotspot-collector"))
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"ordinary", "compiled-churn", "class-unload"} {
		var input struct {
			Argv []string `json:"argv"`
			Hash string   `json:"binary_sha256"`
		}
		if err := driver.ReadJSON(filepath.Join(output, name, "collector-process-input.json"), &input); err != nil {
			t.Fatal(err)
		}
		if input.Hash != want || len(input.Argv) < 2 || input.Argv[0] != filepath.Join(assets, "mw-hotspot-collector") || input.Argv[1] != "--capture" {
			t.Fatal("public CLI acceptance executed a different binary or entry", name, input)
		}
	}
}

func TestObservedPermissionNative(t *testing.T) {
	dir, assets := os.Getenv("MW_OBSERVED_DENIAL"), os.Getenv("MW_HOTSPOT_ASSETS")
	if dir == "" || assets == "" {
		t.Fatal("explicit denial inputs required")
	}
	var target wire.Target
	if err := driver.ReadJSON(filepath.Join(dir, "denial-input.json"), &target); err != nil {
		t.Fatal(err)
	}
	var data [1]byte
	n, nativeErr := unix.ProcessVMReadv(int(target.PID), []unix.Iovec{{Base: &data[0], Len: 1}}, []unix.RemoteIovec{{Base: 1, Len: 1}}, 0)
	if !errors.Is(nativeErr, unix.EPERM) && !errors.Is(nativeErr, unix.EACCES) {
		t.Fatalf("real remote read denial required: n=%d err=%v", n, nativeErr)
	}
	hash, err := driver.HashFile(filepath.Join(assets, "manifest.json"))
	if err != nil {
		t.Fatal(err)
	}
	v, err := bundle.Verify(assets, hash, wire.Observed25)
	if err != nil {
		t.Fatal(err)
	}
	store, err := v.Store()
	if err != nil {
		t.Fatal(err)
	}
	image, err := v.Image()
	if err != nil {
		t.Fatal(err)
	}
	control, send, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	defer send.Close()
	raw, err := os.Create(filepath.Join(dir, "denial-capture.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	defer raw.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	captureErr := capture.RunObservedCandidate(ctx, capture.Config{PID: int(target.PID), ExpectedTarget: target, DrainFull: true, Image: image, Store: store}, raw, control)
	if err := driver.WriteJSON(filepath.Join(dir, "denial-result.json"), map[string]any{"native_read_count": n, "native_error": nativeErr.Error(), "native_errno": int(nativeErr.(unix.Errno)), "capture_error": errorText(captureErr), "capture_retains_permission": errors.Is(captureErr, unix.EPERM) || errors.Is(captureErr, unix.EACCES)}); err != nil {
		t.Fatal(err)
	}
	if !errors.Is(captureErr, unix.EPERM) && !errors.Is(captureErr, unix.EACCES) {
		t.Fatalf("candidate lost actual permission rejection: %v", captureErr)
	}
}

func errorText(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
