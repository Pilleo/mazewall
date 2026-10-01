package output

import (
	"context"
	"encoding/json"
	"io"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"sync"
)

type JSONL struct {
	mu      sync.Mutex
	encoder *json.Encoder
}

func NewJSONL(writer io.Writer) *JSONL { return &JSONL{encoder: json.NewEncoder(writer)} }
func (s *JSONL) Write(ctx context.Context, record model.Record) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.encoder.Encode(record)
}
