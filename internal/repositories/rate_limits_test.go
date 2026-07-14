package repositories

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"regexp"
	"testing"
	"time"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestRateLimitHitLocksCounterAndRejectsPastBudget(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	bucket := "location_share_unlock"
	identity := "share:203.0.113.10"
	identityHash := testRateIdentityHash(bucket, identity)
	mock.ExpectBegin()
	mock.ExpectExec(`(?s)INSERT INTO api_rate_limits .*ON DUPLICATE KEY UPDATE`).
		WithArgs(bucket, identityHash).
		WillReturnResult(sqlmock.NewResult(0, 0))
	mock.ExpectQuery(`(?s)SELECT window_started_at, hit_count.*FOR UPDATE`).
		WithArgs(bucket, identityHash).
		WillReturnRows(sqlmock.NewRows([]string{"window_started_at", "hit_count"}).AddRow(time.Now(), 5))
	mock.ExpectExec(regexp.QuoteMeta(`UPDATE api_rate_limits
SET hit_count = ?, updated_at = NOW()
WHERE bucket = ? AND identity_hash = ?`)).
		WithArgs(6, bucket, identityHash).
		WillReturnResult(sqlmock.NewResult(0, 1))
	mock.ExpectCommit()

	allowed, err := NewRateLimitRepository(db).Hit(context.Background(), bucket, identity, 5, 10*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if allowed {
		t.Fatal("sixth hit was allowed past a five-hit budget")
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestRateLimitHitResetsExpiredCounter(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	bucket := "app_challenge_start"
	identity := "203.0.113.11"
	identityHash := testRateIdentityHash(bucket, identity)
	mock.ExpectBegin()
	mock.ExpectExec(`(?s)INSERT INTO api_rate_limits .*ON DUPLICATE KEY UPDATE`).
		WithArgs(bucket, identityHash).
		WillReturnResult(sqlmock.NewResult(0, 0))
	mock.ExpectQuery(`(?s)SELECT window_started_at, hit_count.*FOR UPDATE`).
		WithArgs(bucket, identityHash).
		WillReturnRows(sqlmock.NewRows([]string{"window_started_at", "hit_count"}).AddRow(time.Now().Add(-11*time.Minute), 99))
	mock.ExpectExec(regexp.QuoteMeta(`UPDATE api_rate_limits
SET window_started_at = NOW(), hit_count = ?, updated_at = NOW()
WHERE bucket = ? AND identity_hash = ?`)).
		WithArgs(1, bucket, identityHash).
		WillReturnResult(sqlmock.NewResult(0, 1))
	mock.ExpectCommit()

	allowed, err := NewRateLimitRepository(db).Hit(context.Background(), bucket, identity, 20, 10*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if !allowed {
		t.Fatal("first hit in a fresh window was rejected")
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestRateLimitHitRollsBackOnWriteFailure(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	mock.ExpectBegin()
	mock.ExpectExec(`(?s)INSERT INTO api_rate_limits .*ON DUPLICATE KEY UPDATE`).
		WithArgs("login", testRateIdentityHash("login", "203.0.113.12")).
		WillReturnError(errors.New("write failed"))
	mock.ExpectRollback()

	allowed, err := NewRateLimitRepository(db).Hit(context.Background(), "login", "203.0.113.12", 30, 5*time.Minute)
	if err == nil {
		t.Fatal("database failure was ignored")
	}
	if allowed {
		t.Fatal("database failure failed open")
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestRateLimitClearResetsSuccessfulAuthenticationBudget(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	bucket := "login_user_password"
	identity := "203.0.113.13"
	mock.ExpectExec(regexp.QuoteMeta("DELETE FROM api_rate_limits WHERE bucket = ? AND identity_hash = ?")).
		WithArgs(bucket, testRateIdentityHash(bucket, identity)).
		WillReturnResult(sqlmock.NewResult(0, 1))

	if err := NewRateLimitRepository(db).Clear(context.Background(), bucket, identity); err != nil {
		t.Fatal(err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestRateLimitHitRunsBoundedStaleCleanupAtHighVolume(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	bucket := "app_challenge_verify_id"
	identity := "0123456789abcdef0123456789abcdef"
	identityHash := testRateIdentityHash(bucket, identity)
	mock.ExpectBegin()
	mock.ExpectExec(`(?s)INSERT INTO api_rate_limits .*ON DUPLICATE KEY UPDATE`).
		WithArgs(bucket, identityHash).
		WillReturnResult(sqlmock.NewResult(0, 0))
	mock.ExpectQuery(`(?s)SELECT window_started_at, hit_count.*FOR UPDATE`).
		WithArgs(bucket, identityHash).
		WillReturnRows(sqlmock.NewRows([]string{"window_started_at", "hit_count"}).AddRow(time.Now(), 0))
	mock.ExpectExec(regexp.QuoteMeta(`UPDATE api_rate_limits
SET hit_count = ?, updated_at = NOW()
WHERE bucket = ? AND identity_hash = ?`)).
		WithArgs(1, bucket, identityHash).
		WillReturnResult(sqlmock.NewResult(0, 1))
	mock.ExpectCommit()
	mock.ExpectExec(regexp.QuoteMeta("DELETE FROM api_rate_limits WHERE updated_at < ? ORDER BY updated_at ASC LIMIT ?")).
		WithArgs(sqlmock.AnyArg(), rateLimitCleanupBatch).
		WillReturnResult(sqlmock.NewResult(0, 17))

	repo := NewRateLimitRepository(db)
	repo.cleanup.nextCleanup = time.Now().Add(rateLimitCleanupInterval)
	repo.cleanup.hitsSinceCleanup = rateLimitCleanupEvery - 1
	allowed, err := repo.Hit(context.Background(), bucket, identity, 1, 15*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if !allowed {
		t.Fatal("first challenge verification lease was rejected")
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func testRateIdentityHash(bucket string, identity string) string {
	sum := sha256.Sum256([]byte(bucket + "|" + identity))
	return hex.EncodeToString(sum[:])
}
