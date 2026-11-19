package output

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"hash"
	"io"
	"math"
	"sync"

	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

var (
	ErrShortWrite   = errors.New("short write to JSONL destination")
	ErrInvalidWrite = errors.New("invalid write byte count to JSONL destination")
	ErrOverflow     = errors.New("arithmetic overflow in JSONL accounting")
)

type JSONL struct {
	mu      sync.Mutex
	writer  io.Writer
	hasher  hash.Hash
	records uint64
	bytes   uint64
	err     error
}

func NewJSONL(writer io.Writer) *JSONL {
	return &JSONL{
		writer: writer,
		hasher: sha256.New(),
	}
}

func (s *JSONL) Write(ctx context.Context, record model.Record) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.err != nil {
		return s.err
	}

	data, err := json.Marshal(record)
	if err != nil {
		s.err = err
		return err
	}
	data = append(data, '\n')

	n, writeErr := s.writer.Write(data)
	if writeErr != nil {
		s.err = writeErr
		return writeErr
	}
	if n < 0 || n > len(data) {
		s.err = ErrInvalidWrite
		return ErrInvalidWrite
	}
	if n < len(data) {
		s.err = ErrShortWrite
		return ErrShortWrite
	}

	if s.records == math.MaxUint64 || s.bytes > math.MaxUint64-uint64(n) {
		s.err = ErrOverflow
		return ErrOverflow
	}

	s.records++
	s.bytes += uint64(n)
	s.hasher.Write(data)
	return nil
}

func (s *JSONL) AccountingPrefix() (records uint64, bytes uint64, sha256 string, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.err != nil {
		return s.records, s.bytes, hex.EncodeToString(s.hasher.Sum(nil)), s.err
	}
	return s.records, s.bytes, hex.EncodeToString(s.hasher.Sum(nil)), nil
}
