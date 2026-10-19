//go:build mw_hotspot_qualification

package capture

import (
	"context"
	"errors"
	"io"
	"os"
)

// RunObservedCandidate is internal qualification support, absent from default builds.
func RunObservedCandidate(ctx context.Context, cfg Config, writer io.Writer, control *os.File) error {
	return RunObservedControlled(ctx, cfg, writer, control)
}

// ObservedTargetExited preserves the private watcher cause across the qualification process boundary.
func ObservedTargetExited(err error) bool { return errors.Is(err, errTargetExited) }
