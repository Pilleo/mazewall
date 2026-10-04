package capture

import (
	"context"
	"errors"
	"sync"
	"syscall"
)

// Bounded session-owned retention; a provider failure never depends on a lookup.
type fatalState struct {
	mu                   sync.Mutex
	first, eperm, eacces error
	cancel               context.CancelFunc
}

func (f *fatalState) Record(err error) {
	if err == nil {
		return
	}
	f.mu.Lock()
	if f.first == nil {
		f.first = err
	}
	if f.eperm == nil && errors.Is(err, syscall.EPERM) {
		f.eperm = err
	}
	if f.eacces == nil && errors.Is(err, syscall.EACCES) {
		f.eacces = err
	}
	f.mu.Unlock()
	if f.cancel != nil {
		f.cancel()
	}
}
func (f *fatalState) Err() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	return errors.Join(f.first, f.eperm, f.eacces)
}
