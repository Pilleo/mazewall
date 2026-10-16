package capture

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"
)

func TestControlStopRequestsDrainButEOFAbortsParentLifetime(t *testing.T) {
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	request, cancel := context.WithCancel(context.Background())
	defer cancel()
	abort, fail := context.WithCancelCause(context.Background())
	defer fail(nil)
	watch, stop := context.WithCancel(context.Background())
	defer stop()
	done := make(chan error, 1)
	go func() { done <- watchControl(watch, int(reader.Fd()), cancel, fail) }()
	if _, err := writer.WriteString("stop\n"); err != nil {
		t.Fatal(err)
	}
	select {
	case <-request.Done():
	case <-time.After(time.Second):
		t.Fatal("stop request lost")
	}
	if abort.Err() != nil {
		t.Fatal("graceful stop aborted enrichment lifetime")
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err == nil || !errors.Is(context.Cause(abort), err) {
			t.Fatalf("EOF did not preserve parent loss: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("parent EOF did not join control worker")
	}
}

func TestControlCancellationJoinsWithoutClosingBorrowedDescriptor(t *testing.T) {
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	defer writer.Close()
	watch, stop := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- watchControl(watch, int(reader.Fd()), func() {}, func(error) {}) }()
	stop()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("control worker remained blocked")
	}
	if _, err := writer.WriteString("borrowed descriptor still open"); err != nil {
		t.Fatal(err)
	}
}
