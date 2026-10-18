//go:build mw_hotspot_qualification

package candidate

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/Pilleo/mazewall/hotspot-bridge/bundle"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"golang.org/x/sys/unix"
	"io.mazewall/experiments/hotspot-agentless/internal/capture"
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
)

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
