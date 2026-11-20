package output

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"math"
	"os"
	"testing"

	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

type shortWriter struct{}

func (shortWriter) Write(p []byte) (int, error) { return len(p) - 1, nil }

func TestShortWriteIsNotSuccessfulDelivery(t *testing.T) {
	s := NewJSONL(shortWriter{})
	if err := s.Write(context.Background(), model.Record{Kind: "event"}); err == nil {
		t.Fatal("short JSONL write admitted as complete")
	}
}

type overWriter struct{}

func (overWriter) Write(p []byte) (int, error) { return len(p) + 1, nil }

func TestOverReportedWriteIsNotSuccessfulDelivery(t *testing.T) {
	s := NewJSONL(overWriter{})
	err := s.Write(context.Background(), model.Record{Kind: "event"})
	if err == nil {
		t.Fatal("over-reported JSONL write admitted as complete")
	}
	if !errors.Is(err, ErrInvalidWrite) {
		t.Fatalf("expected ErrInvalidWrite, got %v", err)
	}

	if err2 := s.Write(context.Background(), model.Record{Kind: "event"}); !errors.Is(err2, ErrInvalidWrite) {
		t.Fatalf("expected latched ErrInvalidWrite, got %v", err2)
	}

	if _, _, _, prefixErr := s.AccountingPrefix(); !errors.Is(prefixErr, ErrInvalidWrite) {
		t.Fatalf("expected latched ErrInvalidWrite in prefix, got %v", prefixErr)
	}
}

func TestSuccessfulDeliveryAndAccountingPrefix(t *testing.T) {
	var buf bytes.Buffer
	s := NewJSONL(&buf)
	ctx := context.Background()

	r1 := model.Record{Kind: "session_started", SessionID: "test-sess-1"}
	r2 := model.Record{Kind: "event", SessionID: "test-sess-1"}

	if err := s.Write(ctx, r1); err != nil {
		t.Fatalf("Write r1 failed: %v", err)
	}
	if err := s.Write(ctx, r2); err != nil {
		t.Fatalf("Write r2 failed: %v", err)
	}

	records, byteCount, hash, err := s.AccountingPrefix()
	if err != nil {
		t.Fatalf("AccountingPrefix returned error: %v", err)
	}
	if records != 2 {
		t.Fatalf("expected 2 records, got %d", records)
	}
	if byteCount != uint64(buf.Len()) {
		t.Fatalf("expected %d bytes, got %d", buf.Len(), byteCount)
	}

	expectedHash := sha256.Sum256(buf.Bytes())
	if hash != hex.EncodeToString(expectedHash[:]) {
		t.Fatalf("hash mismatch: expected %s, got %s", hex.EncodeToString(expectedHash[:]), hash)
	}
}

func TestStickyErrorLatching(t *testing.T) {
	s := NewJSONL(shortWriter{})
	ctx := context.Background()

	err1 := s.Write(ctx, model.Record{Kind: "event"})
	if !errors.Is(err1, ErrShortWrite) {
		t.Fatalf("expected ErrShortWrite, got %v", err1)
	}

	// Subsequent write should immediately fail with sticky error
	err2 := s.Write(ctx, model.Record{Kind: "event"})
	if !errors.Is(err2, ErrShortWrite) {
		t.Fatalf("expected latched ErrShortWrite, got %v", err2)
	}

	_, _, _, prefixErr := s.AccountingPrefix()
	if !errors.Is(prefixErr, ErrShortWrite) {
		t.Fatalf("expected latched error in AccountingPrefix, got %v", prefixErr)
	}
}

func TestContextCancellationDoesNotCorruptPrefix(t *testing.T) {
	var buf bytes.Buffer
	s := NewJSONL(&buf)

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	err := s.Write(ctx, model.Record{Kind: "event"})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected context.Canceled, got %v", err)
	}

	records, byteCount, _, prefixErr := s.AccountingPrefix()
	if prefixErr != nil {
		t.Fatalf("unexpected prefix error: %v", prefixErr)
	}
	if records != 0 || byteCount != 0 {
		t.Fatalf("expected 0 records and 0 bytes, got %d and %d", records, byteCount)
	}
}

func TestArithmeticOverflowLatching(t *testing.T) {
	var buf bytes.Buffer
	s := NewJSONL(&buf)
	s.records = math.MaxUint64

	err := s.Write(context.Background(), model.Record{Kind: "event"})
	if !errors.Is(err, ErrOverflow) {
		t.Fatalf("expected ErrOverflow on records overflow, got %v", err)
	}

	s2 := NewJSONL(&buf)
	s2.bytes = math.MaxUint64

	err2 := s2.Write(context.Background(), model.Record{Kind: "event"})
	if !errors.Is(err2, ErrOverflow) {
		t.Fatalf("expected ErrOverflow on bytes overflow, got %v", err2)
	}
}

func findFixturePath() string {
	candidates := []string{
		"../../../src/test/resources/hotspot/measured-event-projection.jsonl",
		"../../../../profiler/src/test/resources/hotspot/measured-event-projection.jsonl",
	}
	for _, c := range candidates {
		if _, err := os.Stat(c); err == nil {
			return c
		}
	}
	return candidates[0]
}

func TestGenuineMeasuredEventProjectionParity(t *testing.T) {
	raw, err := os.ReadFile(findFixturePath())
	if err != nil {
		t.Fatalf("failed to read measured-event-projection.jsonl: %v", err)
	}
	lines := bytes.Split(bytes.TrimSpace(raw), []byte("\n"))
	if len(lines) < 3 {
		t.Fatalf("expected at least 3 lines, got %d", len(lines))
	}

	var records []model.Record
	for i, l := range lines {
		var r model.Record
		if err := json.Unmarshal(l, &r); err != nil {
			t.Fatalf("failed to unmarshal line %d: %v", i+1, err)
		}
		records = append(records, r)
	}

	var buf bytes.Buffer
	w := NewJSONL(&buf)
	ctx := context.Background()

	// Write prefix records (event and enrichment)
	for i := 0; i < 2; i++ {
		if err := w.Write(ctx, records[i]); err != nil {
			t.Fatalf("failed to write record %d: %v", i+1, err)
		}
	}

	recs, byteCount, hash, err := w.AccountingPrefix()
	if err != nil {
		t.Fatalf("AccountingPrefix error: %v", err)
	}

	const (
		expectedRecords = 2
		expectedBytes   = 2139
		expectedSHA256  = "ae6cc748e937db293cbce043a6bed51bf2834702bc519faaf72c94afdc93046c"
	)

	if recs != expectedRecords {
		t.Fatalf("expected %d prefix records, got %d", expectedRecords, recs)
	}
	if byteCount != expectedBytes {
		t.Fatalf("expected %d prefix bytes, got %d", expectedBytes, byteCount)
	}
	if hash != expectedSHA256 {
		t.Fatalf("expected prefix sha256 %s, got %s", expectedSHA256, hash)
	}

	// Verify exact byte equality of prefix against fixture prefix
	fixturePrefix := append(append([]byte(nil), lines[0]...), '\n')
	fixturePrefix = append(append(fixturePrefix, lines[1]...), '\n')
	if !bytes.Equal(buf.Bytes(), fixturePrefix) {
		t.Fatalf("emitted prefix bytes do not match genuine fixture prefix bytes")
	}

	// Write terminal summary record (mirroring collector emitting summary after prefix snapshot)
	if err := w.Write(ctx, records[2]); err != nil {
		t.Fatalf("failed to write summary record: %v", err)
	}
}
