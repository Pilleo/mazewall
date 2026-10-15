package capture

import (
	"context"
	"time"
)

func fullOwnedContext(ctx context.Context, cfg Config, deadline time.Time) (context.Context, context.CancelFunc) {
	if cfg.DrainFull {
		return context.WithDeadline(context.WithoutCancel(ctx), deadline)
	}
	return ctx, func() {}
}
