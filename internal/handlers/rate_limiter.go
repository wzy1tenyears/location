package handlers

import (
	"context"
	"time"
)

type hitLimiter interface {
	Hit(ctx context.Context, bucket string, identity string, maxHits int, window time.Duration) (bool, error)
}

type resettableRateLimiter interface {
	hitLimiter
	Clear(ctx context.Context, bucket string, identity string) error
}
